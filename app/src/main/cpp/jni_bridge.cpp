// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <android/log.h>

#include <string>
#include <thread>

#include <GLES3/gl3.h>
#include <cmath>
#include <cstring>
#include <vector>

#include <opencv2/core.hpp>

#include "hdri/io.h"

#include "hdri/stitch.h"

namespace {
std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string jsonEscape(const std::string& s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += c; }
        else if (c == '\n') o += "\\n";
        else o += c;
    }
    return o;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_hdricapture_processing_NativeBridge_nativeVersion(JNIEnv* env, jobject) {
    std::string v = "hdricore 0.1.0, OpenCV " CV_VERSION;
    return env->NewStringUTF(v.c_str());
}

/// Runs the pipeline. [progress] must have a method `void onProgress(String stage, float fraction)`.
/// Returns a JSON object: {ok, error, files[], pairs, rmsBefore, rmsAfter, holes}.
extern "C" JNIEXPORT jstring JNICALL
Java_org_hdricapture_processing_NativeBridge_nativeProcess(JNIEnv* env, jobject, jstring jdir, jstring joutDir,
                                                           jint width, jboolean hdr, jboolean exr, jobject progress) {
    hdri::SessionMeta session;
    std::string err;
    std::string json;
    if (!hdri::loadSession(str(env, jdir), session, &err)) {
        json = "{\"ok\":false,\"error\":\"" + jsonEscape(err) + "\"}";
        return env->NewStringUTF(json.c_str());
    }
    hdri::StitchOptions opts;
    opts.outWidth = width;
    opts.outDir = str(env, joutDir);
    opts.writeHdr = hdr;
    opts.writeExr = exr;

    jclass cls = env->GetObjectClass(progress);
    jmethodID onProgress = env->GetMethodID(cls, "onProgress", "(Ljava/lang/String;F)V");
    // Progress is reported from worker threads too; only forward calls made on this (attached) thread.
    const auto self = std::this_thread::get_id();
    auto res = hdri::processSession(session, opts, [&](const std::string& stage, float f) {
        if (std::this_thread::get_id() != self || !onProgress) return;
        jstring s = env->NewStringUTF(stage.c_str());
        env->CallVoidMethod(progress, onProgress, s, f);
        env->DeleteLocalRef(s);
        if (env->ExceptionCheck()) env->ExceptionClear();
    });
    __android_log_print(ANDROID_LOG_INFO, "hdricore", "stitch ok=%d pairs=%d rms %.3f -> %.3f holes=%.3f",
                        res.ok, res.matchedPairs, res.rmsBeforeDeg, res.rmsAfterDeg, res.holeFraction);
    json = "{\"ok\":" + std::string(res.ok ? "true" : "false") + ",\"error\":\"" + jsonEscape(res.error) + "\",\"files\":[";
    for (size_t i = 0; i < res.files.size(); ++i) json += (i ? ",\"" : "\"") + jsonEscape(res.files[i]) + "\"";
    json += "],\"pairs\":" + std::to_string(res.matchedPairs) + ",\"rmsBefore\":" + std::to_string(res.rmsBeforeDeg) +
            ",\"rmsAfter\":" + std::to_string(res.rmsAfterDeg) + ",\"holes\":" + std::to_string(res.holeFraction) + "}";
    return env->NewStringUTF(json.c_str());
}

namespace {
// IEEE 754 float -> half, round-to-nearest; clamps to the largest finite half.
uint16_t toHalf(float f) {
    if (!(f > 0)) return 0;  // negatives / NaN -> 0 (radiance is non-negative)
    if (f >= 65504.0f) return 0x7bff;
    uint32_t x;
    std::memcpy(&x, &f, 4);
    const int e = static_cast<int>((x >> 23) & 0xff) - 127 + 15;
    uint32_t m = x & 0x7fffff;
    if (e <= 0) {  // subnormal half
        if (e < -10) return 0;
        m |= 0x800000;
        const int shift = 14 - e;
        uint32_t h = m >> shift;
        if ((m >> (shift - 1)) & 1) ++h;
        return static_cast<uint16_t>(h);
    }
    uint32_t h = (static_cast<uint32_t>(e) << 10) | (m >> 13);
    if (m & 0x1000) ++h;  // round
    return static_cast<uint16_t>(h);
}
}  // namespace

/// Loads an .hdr file into the currently bound GL_TEXTURE_2D as RGBA16F. Must run on a GL thread.
/// Returns [width, height, auto exposure multiplier] or null on failure.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_hdricapture_processing_NativeBridge_nativeUploadHdrTexture(JNIEnv* env, jobject, jstring jpath) {
    hdri::ImageF img;
    if (!hdri::readRgbe(str(env, jpath), img)) return nullptr;
    const size_t n = static_cast<size_t>(img.width) * img.height;
    std::vector<uint16_t> half(n * 4);
    double logSum = 0;
    size_t cnt = 0;
    for (size_t i = 0; i < n; ++i) {
        const float* p = &img.data[i * 3];
        half[i * 4 + 0] = toHalf(p[0]);
        half[i * 4 + 1] = toHalf(p[1]);
        half[i * 4 + 2] = toHalf(p[2]);
        half[i * 4 + 3] = 0x3c00;  // 1.0
        if ((i & 15) == 0) {
            logSum += std::log(1e-4 + 0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2]);
            ++cnt;
        }
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, img.width, img.height, 0, GL_RGBA, GL_HALF_FLOAT, half.data());
    if (glGetError() != GL_NO_ERROR) return nullptr;
    const float key = static_cast<float>(0.18 / std::exp(logSum / std::max<size_t>(cnt, 1)));
    jfloatArray out = env->NewFloatArray(3);
    const float vals[3] = {static_cast<float>(img.width), static_cast<float>(img.height), key};
    env->SetFloatArrayRegion(out, 0, 3, vals);
    return out;
}
