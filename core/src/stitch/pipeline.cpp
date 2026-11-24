// SPDX-License-Identifier: GPL-3.0-or-later
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <sys/stat.h>

#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/photo.hpp>

#include "align.h"
#include "camera.h"
#include "hdri/exr.h"
#include "hdri/io.h"
#include "hdri/merge.h"
#include "hdri/raw.h"
#include "hdri/stitch.h"

namespace hdri {
namespace {

constexpr double kLowScale = 1.0 / 8.0;  // resolution of the low-frequency band
constexpr float kEps = 1e-5f;            // floor for log radiance
constexpr double kDetailSharpness = 60;  // detail-band feather: ~1/60 of the half-frame per e-fold

struct Spot {
    SpotCamera cam;
    cv::Mat radiance;  // CV_32FC3, linear
    cv::Mat low;       // CV_32FC3, blurred log radiance, at kLowScale
};

inline cv::Vec3f sampleBilinear(const cv::Mat& m, double u, double v) {
    const int x0 = std::clamp(static_cast<int>(u), 0, m.cols - 2);
    const int y0 = std::clamp(static_cast<int>(v), 0, m.rows - 2);
    const float tx = static_cast<float>(std::clamp(u - x0, 0.0, 1.0));
    const float ty = static_cast<float>(std::clamp(v - y0, 0.0, 1.0));
    const cv::Vec3f* r0 = m.ptr<cv::Vec3f>(y0) + x0;
    const cv::Vec3f* r1 = m.ptr<cv::Vec3f>(y0 + 1) + x0;
    return (r0[0] * (1 - tx) + r0[1] * tx) * (1 - ty) + (r1[0] * (1 - tx) + r1[1] * tx) * ty;
}

cv::Mat toMat(const ImageF& img) {
    return cv::Mat(img.height, img.width, CV_32FC3, const_cast<float*>(img.data.data())).clone();
}

ImageF toImage(const cv::Mat& m) {
    ImageF img(m.cols, m.rows);
    for (int y = 0; y < m.rows; ++y) std::memcpy(img.px(0, y), m.ptr<float>(y), sizeof(float) * 3 * m.cols);
    return img;
}

/// 8-bit grey image for feature detection: luminance tone-mapped around the image's median.
cv::Mat featureImage(const cv::Mat& radiance, double scale) {
    cv::Mat small;
    cv::resize(radiance, small, cv::Size(), scale, scale, cv::INTER_AREA);
    cv::Mat lum(small.size(), CV_32F);
    std::vector<float> vals;
    vals.reserve(small.total() / 16);
    for (int y = 0; y < small.rows; ++y)
        for (int x = 0; x < small.cols; ++x) {
            const cv::Vec3f p = small.at<cv::Vec3f>(y, x);
            const float l = std::max(0.0f, 0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2]);
            lum.at<float>(y, x) = l;
            if (((x | y) & 3) == 0) vals.push_back(l);
        }
    std::nth_element(vals.begin(), vals.begin() + vals.size() / 2, vals.end());
    const float med = std::max(1e-6f, vals[vals.size() / 2]);
    cv::Mat out(small.size(), CV_8U);
    for (int y = 0; y < small.rows; ++y)
        for (int x = 0; x < small.cols; ++x) {
            // log2 exposure, median at mid-grey, +-6 stops across the 8-bit range.
            const float t = std::log2(std::max(1e-6f, lum.at<float>(y, x) / med));
            out.at<uchar>(y, x) = cv::saturate_cast<uchar>(128.0f + t * (127.0f / 6.0f));
        }
    // Local contrast boost so features are found in flat walls/ceilings as well.
    cv::createCLAHE(3.0, cv::Size(8, 8))->apply(out, out);
    return out;
}

cv::Mat tonemapPreview(const cv::Mat& radiance, int width) {
    cv::Mat small;
    const double s = std::min(1.0, static_cast<double>(width) / radiance.cols);
    cv::resize(radiance, small, cv::Size(), s, s, cv::INTER_AREA);
    double logSum = 0;
    for (int y = 0; y < small.rows; ++y)
        for (int x = 0; x < small.cols; ++x) {
            const cv::Vec3f p = small.at<cv::Vec3f>(y, x);
            logSum += std::log(1e-6 + std::max(0.0f, 0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2]));
        }
    const double key = 0.18 / std::exp(logSum / small.total());
    cv::Mat out(small.size(), CV_8UC3);
    auto aces = [](double x) { return std::clamp(x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); };
    auto srgb = [](double x) { return x <= 0.0031308 ? 12.92 * x : 1.055 * std::pow(x, 1 / 2.4) - 0.055; };
    for (int y = 0; y < small.rows; ++y)
        for (int x = 0; x < small.cols; ++x) {
            const cv::Vec3f p = small.at<cv::Vec3f>(y, x);
            cv::Vec3b& o = out.at<cv::Vec3b>(y, x);
            for (int c = 0; c < 3; ++c) o[2 - c] = cv::saturate_cast<uchar>(255 * srgb(aces(std::max(0.0, p[c] * key))));
        }
    return out;
}

void makeDir(const std::string& d) { ::mkdir(d.c_str(), 0755); }

/// Writes a JPEG with Photo Sphere (GPano) XMP metadata so 360° viewers (Google Photos, Facebook,
/// etc.) show it as an interactive panorama.
bool writePhotoSphereJpeg(const std::string& path, const cv::Mat& bgr) {
    std::vector<uchar> jpg;
    if (!cv::imencode(".jpg", bgr, jpg, {cv::IMWRITE_JPEG_QUALITY, 92}) || jpg.size() < 2) return false;
    char xmp[1024];
    std::snprintf(xmp, sizeof xmp,
                  "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                  "<rdf:Description rdf:about=\"\" xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\">"
                  "<GPano:ProjectionType>equirectangular</GPano:ProjectionType>"
                  "<GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>"
                  "<GPano:CroppedAreaImageWidthPixels>%d</GPano:CroppedAreaImageWidthPixels>"
                  "<GPano:CroppedAreaImageHeightPixels>%d</GPano:CroppedAreaImageHeightPixels>"
                  "<GPano:FullPanoWidthPixels>%d</GPano:FullPanoWidthPixels>"
                  "<GPano:FullPanoHeightPixels>%d</GPano:FullPanoHeightPixels>"
                  "<GPano:CroppedAreaLeftPixels>0</GPano:CroppedAreaLeftPixels>"
                  "<GPano:CroppedAreaTopPixels>0</GPano:CroppedAreaTopPixels>"
                  "</rdf:Description></rdf:RDF></x:xmpmeta>",
                  bgr.cols, bgr.rows, bgr.cols, bgr.rows);
    const std::string ns = "http://ns.adobe.com/xap/1.0/";
    const size_t payload = ns.size() + 1 + std::strlen(xmp);
    const size_t segLen = payload + 2;
    std::vector<uchar> app1 = {0xFF, 0xE1, static_cast<uchar>(segLen >> 8), static_cast<uchar>(segLen & 0xFF)};
    app1.insert(app1.end(), ns.begin(), ns.end());
    app1.push_back(0);
    app1.insert(app1.end(), xmp, xmp + std::strlen(xmp));
    std::FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    bool ok = std::fwrite(jpg.data(), 1, 2, f) == 2 &&                                  // SOI
              std::fwrite(app1.data(), 1, app1.size(), f) == app1.size() &&             // XMP
              std::fwrite(jpg.data() + 2, 1, jpg.size() - 2, f) == jpg.size() - 2;      // rest
    ok = (std::fclose(f) == 0) && ok;
    return ok;
}

}  // namespace

StitchResult processSession(const SessionMeta& session, const StitchOptions& opts, const ProgressFn& progress) {
    StitchResult res;
    auto report = [&](const std::string& s, float f) { if (progress) progress(s, f); };
    if (session.spots.empty()) {
        res.error = "session has no captures";
        return res;
    }
    const std::string outDir = opts.outDir.empty() ? session.dir + "/output" : opts.outDir;
    makeDir(outDir);

    // 1. Develop and merge each spot.
    std::vector<Spot> spots(session.spots.size());
    for (size_t si = 0; si < session.spots.size(); ++si) {
        const SpotMeta& sm = session.spots[si];
        report("Merging exposures", 0.5f * si / session.spots.size());
        std::vector<DevelopedFrame> dev(sm.frames.size());
        std::string err;
        bool failed = false;
        cv::parallel_for_(cv::Range(0, static_cast<int>(sm.frames.size())), [&](const cv::Range& r) {
            for (int k = r.start; k < r.end; ++k) {
                RawImage raw;
                std::string e;
                if (!readDngRaw(session.dir + "/" + sm.frames[k].file, raw, &e)) {
                    failed = true;
                    err = sm.frames[k].file + ": " + e;
                    continue;
                }
                dev[k] = developHalfRes(raw, rawParamsFor(session, sm, sm.frames[k]));
            }
        });
        if (failed) {
            res.error = err;
            return res;
        }
        std::vector<BracketFrame> frames;
        for (size_t k = 0; k < dev.size(); ++k)
            frames.push_back({&dev[k].rgb, sm.frames[k].relativeExposure(), &dev[k].level});
        ImageF merged = mergeBrackets(frames);
        if (merged.empty()) {
            res.error = "bracket merge failed for spot " + std::to_string(sm.spotIndex);
            return res;
        }
        Spot& sp = spots[si];
        sp.radiance = toMat(merged);
        // Pose: use the median-exposure frame (closest to the moment of aiming).
        sp.cam = SpotCamera(session.lens, sm.frames[sm.frames.size() / 2].pose, 0.5);
        if (sp.cam.width != sp.radiance.cols) {
            sp.cam.width = sp.radiance.cols;
            sp.cam.height = sp.radiance.rows;
            sp.cam.buildLookup();
        }
        cv::resize(sp.radiance, sp.low, cv::Size(), kLowScale, kLowScale, cv::INTER_AREA);
        cv::max(sp.low, kEps, sp.low);
        cv::log(sp.low, sp.low);
        cv::GaussianBlur(sp.low, sp.low, cv::Size(0, 0), 2.0);
        if (opts.writeSpotImages) {
            char name[64];
            std::snprintf(name, sizeof name, "/spot%02d.hdr", sm.spotIndex);
            writeRgbe(outDir + name, merged);
        }
    }

    // 2. Refine poses.
    if (opts.refinePoses && spots.size() > 1) {
        report("Aligning", 0.5f);
        const double gs = std::min(1.0, 1200.0 / spots[0].radiance.cols);
        std::vector<cv::Mat> gray;
        std::vector<SpotCamera> cams;
        for (auto& s : spots) {
            gray.push_back(featureImage(s.radiance, gs));
            cams.push_back(s.cam);
        }
        AlignStats st = refinePoses(cams, gray, gs);
        res.matchedPairs = st.pairs;
        res.rmsBeforeDeg = st.rmsBeforeDeg;
        res.rmsAfterDeg = st.rmsAfterDeg;
        for (size_t k = 0; k < spots.size(); ++k) spots[k].cam = cams[k];
    }

    // 3. Render the equirectangular panorama: low band feathered across all spots, high band from
    //    the spot whose centre is closest (sharp, no ghosting from averaging misaligned detail).
    report("Rendering", 0.6f);
    const int W = std::max(256, opts.outWidth & ~1), H = W / 2;
    cv::Mat out(H, W, CV_32FC3, cv::Scalar::all(0));
    cv::Mat hole(H, W, CV_8U, cv::Scalar(0));
    const double cosLimit = std::cos(std::atan(std::hypot(spots[0].cam.width, spots[0].cam.height) / (2 * spots[0].cam.fx)) + 0.05);
    std::atomic<int> rowsDone{0};
    std::atomic<long long> holes{0};
    cv::parallel_for_(cv::Range(0, H), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            const double lat = 90.0 - (y + 0.5) * 180.0 / H;
            cv::Vec3f* row = out.ptr<cv::Vec3f>(y);
            for (int x = 0; x < W; ++x) {
                const double lon = (x + 0.5) * 360.0 / W - 180.0;
                const cv::Vec3d d = dirFromYawPitch(session.headingDeg + lon, lat);
                // Two-band blend in log space. Low band: wide feather (w = e^2) across all spots, which
                // hides vignetting/exposure differences. Detail (log ratio to each spot's own low band):
                // steep feather (exponential in e), so transitions are narrow (little ghosting) but not hard cuts.
                cv::Vec3d lowAcc(0, 0, 0), detAcc(0, 0, 0);
                double wsum = 0, dsum = 0;
                for (size_t k = 0; k < spots.size(); ++k) {
                    const SpotCamera& c = spots[k].cam;
                    if (d.dot(c.forward()) < cosLimit) continue;
                    double u, v;
                    if (!c.project(d, u, v)) continue;
                    // Normalised distance to the nearest image edge, 0 at the edge, 1 at the centre.
                    const double e = std::min(std::min(u, c.width - 1 - u) / (0.5 * c.width),
                                              std::min(v, c.height - 1 - v) / (0.5 * c.height));
                    if (e <= 0) continue;
                    const double lu = (u + 0.5) * kLowScale - 0.5, lv = (v + 0.5) * kLowScale - 0.5;
                    const cv::Vec3f l = sampleBilinear(spots[k].low, lu, lv);
                    const double w = e * e;
                    lowAcc += w * cv::Vec3d(l[0], l[1], l[2]);
                    wsum += w;
                    // Steep feather: weights relative to the best spot fall off fast, so blending only
                    // happens within a narrow band around the midline between two spots.
                    const double wd = std::exp(kDetailSharpness * (e - 1.0));
                    const cv::Vec3f full = sampleBilinear(spots[k].radiance, u, v);
                    for (int ch = 0; ch < 3; ++ch) detAcc[ch] += wd * (std::log(std::max(full[ch], kEps)) - l[ch]);
                    dsum += wd;
                }
                if (wsum <= 0) {
                    hole.at<uchar>(y, x) = 255;
                    ++holes;
                    continue;
                }
                for (int ch = 0; ch < 3; ++ch) row[x][ch] = static_cast<float>(std::exp(lowAcc[ch] / wsum + detAcc[ch] / dsum));
            }
            const int done = ++rowsDone;
            if ((done & 63) == 0) report("Rendering", 0.6f + 0.3f * done / H);
        }
    });
    res.holeFraction = static_cast<double>(holes) / (static_cast<double>(W) * H);

    // 4. Fill uncovered areas (typically the nadir under a handheld shooter) by inpainting at low res.
    if (holes > 0) {
        report("Filling gaps", 0.9f);
        const double s = std::min(1.0, 1024.0 / W);
        cv::Mat small, smallHole;
        cv::resize(out, small, cv::Size(), s, s, cv::INTER_AREA);
        cv::resize(hole, smallHole, small.size(), 0, 0, cv::INTER_NEAREST);
        cv::dilate(smallHole, smallHole, cv::Mat(), cv::Point(-1, -1), 2);
        std::vector<cv::Mat> ch;
        cv::split(small, ch);
        for (auto& c : ch) {
            cv::Mat lg;
            cv::log(c + 1e-4f, lg);
            cv::inpaint(lg, smallHole, lg, 8, cv::INPAINT_TELEA);
            cv::exp(lg, c);
        }
        cv::merge(ch, small);
        cv::Mat big;
        cv::resize(small, big, out.size(), 0, 0, cv::INTER_LINEAR);
        big.copyTo(out, hole);
    }

    // 5. Luminance statistics, weighting rows by cos(latitude) so polar rows don't dominate.
    {
        std::vector<std::pair<float, float>> lw;
        lw.reserve(static_cast<size_t>(W) * H / 4);
        for (int y = 0; y < H; y += 2) {
            const float w = static_cast<float>(std::cos((90.0 - (y + 0.5) * 180.0 / H) * CV_PI / 180));
            const cv::Vec3f* row = out.ptr<cv::Vec3f>(y);
            for (int x = 0; x < W; x += 2) {
                const float l = 0.2126f * row[x][0] + 0.7152f * row[x][1] + 0.0722f * row[x][2];
                lw.emplace_back(l, w);
                res.lumMax = std::max(res.lumMax, static_cast<double>(l));
            }
        }
        std::sort(lw.begin(), lw.end());
        double total = 0;
        for (auto& e : lw) total += e.second;
        double acc = 0;
        bool p1 = false, p50 = false, p99 = false;
        for (auto& e : lw) {
            acc += e.second;
            if (!p1 && acc >= 0.01 * total) { res.lumP1 = e.first; p1 = true; }
            if (!p50 && acc >= 0.5 * total) { res.lumMedian = e.first; p50 = true; }
            if (!p99 && acc >= 0.99 * total) { res.lumP99 = e.first; p99 = true; }
        }
        res.dynamicRangeStops = std::log2(std::max(res.lumMax, 1e-9) / std::max(res.lumP1, 1e-9));
    }

    // 6. Write outputs.
    report("Writing files", 0.95f);
    const std::string base = outDir + "/" + opts.baseName;
    ImageF img = toImage(out);
    if (opts.writeHdr) {
        if (!writeRgbe(base + ".hdr", img)) { res.error = "failed to write .hdr"; return res; }
        res.files.push_back(base + ".hdr");
    }
    if (opts.writeExr) {
        std::string e;
        if (!writeExr(base + ".exr", img, &e)) { res.error = "failed to write .exr: " + e; return res; }
        res.files.push_back(base + ".exr");
    }
    if (opts.writeJpeg) {
        // Full-resolution tone-mapped JPEG with 360° metadata, plus a small preview for thumbnails.
        if (!writePhotoSphereJpeg(base + ".jpg", tonemapPreview(out, W))) {
            res.error = "failed to write jpeg";
            return res;
        }
        res.files.push_back(base + ".jpg");
        if (!cv::imwrite(base + "_preview.jpg", tonemapPreview(out, opts.previewWidth), {cv::IMWRITE_JPEG_QUALITY, 88})) {
            res.error = "failed to write preview";
            return res;
        }
        res.files.push_back(base + "_preview.jpg");
    }
    {
        // Persist stats so the app can show them without re-reading the HDR.
        std::FILE* f = std::fopen((outDir + "/stats.json").c_str(), "w");
        if (f) {
            std::fprintf(f,
                         "{\"width\":%d,\"height\":%d,\"dynamicRangeStops\":%.2f,\"lumP1\":%.6g,\"lumMedian\":%.6g,"
                         "\"lumP99\":%.6g,\"lumMax\":%.6g,\"pairs\":%d,\"rmsBeforeDeg\":%.3f,\"rmsAfterDeg\":%.3f,"
                         "\"holeFraction\":%.4f}\n",
                         W, H, res.dynamicRangeStops, res.lumP1, res.lumMedian, res.lumP99, res.lumMax, res.matchedPairs,
                         res.rmsBeforeDeg, res.rmsAfterDeg, res.holeFraction);
            std::fclose(f);
            res.files.push_back(outDir + "/stats.json");
        }
    }
    report("Done", 1.0f);
    res.ok = true;
    return res;
}

}  // namespace hdri
