// SPDX-License-Identifier: GPL-3.0-or-later
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>

#include "hdri/io.h"

namespace hdri {
namespace {

struct FileCloser {
    void operator()(FILE* f) const { if (f) std::fclose(f); }
};
using FilePtr = std::unique_ptr<FILE, FileCloser>;

void floatToRgbe(const float* rgb, uint8_t* out) {
    float r = std::max(rgb[0], 0.0f), g = std::max(rgb[1], 0.0f), b = std::max(rgb[2], 0.0f);
    float v = std::max(r, std::max(g, b));
    if (!(v >= 1e-32f) || !std::isfinite(v)) {  // also catches NaN
        out[0] = out[1] = out[2] = out[3] = 0;
        return;
    }
    int e;
    float scale = std::frexp(v, &e) * 256.0f / v;
    out[0] = static_cast<uint8_t>(std::min(255.0f, r * scale));
    out[1] = static_cast<uint8_t>(std::min(255.0f, g * scale));
    out[2] = static_cast<uint8_t>(std::min(255.0f, b * scale));
    out[3] = static_cast<uint8_t>(e + 128);
}

void rgbeToFloat(const uint8_t* in, float* rgb) {
    if (in[3] == 0) {
        rgb[0] = rgb[1] = rgb[2] = 0.0f;
        return;
    }
    // +0.5 recentres the truncated mantissa.
    float f = std::ldexp(1.0f, static_cast<int>(in[3]) - (128 + 8));
    rgb[0] = (in[0] + 0.5f) * f;
    rgb[1] = (in[1] + 0.5f) * f;
    rgb[2] = (in[2] + 0.5f) * f;
}

// Writes one channel of a scanline with the Radiance RLE scheme.
bool writeRleChannel(FILE* f, const uint8_t* data, int n) {
    int i = 0;
    while (i < n) {
        // Find the next run of >= 4 identical bytes.
        int runStart = i, runLen = 0;
        while (runStart < n) {
            runLen = 1;
            while (runStart + runLen < n && runLen < 127 && data[runStart + runLen] == data[runStart]) ++runLen;
            if (runLen >= 4) break;
            runStart += runLen;
        }
        if (runLen < 4) runStart = n;
        // Literal bytes before the run.
        while (i < runStart) {
            int count = std::min(128, runStart - i);
            uint8_t c = static_cast<uint8_t>(count);
            if (std::fwrite(&c, 1, 1, f) != 1 || std::fwrite(data + i, 1, count, f) != static_cast<size_t>(count)) return false;
            i += count;
        }
        if (runStart < n) {
            uint8_t hdr[2] = {static_cast<uint8_t>(128 + runLen), data[runStart]};
            if (std::fwrite(hdr, 1, 2, f) != 2) return false;
            i = runStart + runLen;
        }
    }
    return true;
}

}  // namespace

bool writeRgbe(const std::string& path, const ImageF& img) {
    if (img.empty() || img.width <= 0 || img.height <= 0) return false;
    FilePtr f(std::fopen(path.c_str(), "wb"));
    if (!f) return false;
    std::fprintf(f.get(), "#?RADIANCE\n# Made with HDRI Sphere\nFORMAT=32-bit_rle_rgbe\n\n-Y %d +X %d\n",
                 img.height, img.width);

    const int w = img.width;
    const bool rle = w >= 8 && w < 32768;
    std::vector<uint8_t> rgbe(static_cast<size_t>(w) * 4), channel(w);
    for (int y = 0; y < img.height; ++y) {
        for (int x = 0; x < w; ++x) floatToRgbe(img.px(x, y), &rgbe[x * 4]);
        if (!rle) {
            if (std::fwrite(rgbe.data(), 1, rgbe.size(), f.get()) != rgbe.size()) return false;
            continue;
        }
        uint8_t hdr[4] = {2, 2, static_cast<uint8_t>(w >> 8), static_cast<uint8_t>(w & 0xff)};
        if (std::fwrite(hdr, 1, 4, f.get()) != 4) return false;
        for (int c = 0; c < 4; ++c) {
            for (int x = 0; x < w; ++x) channel[x] = rgbe[x * 4 + c];
            if (!writeRleChannel(f.get(), channel.data(), w)) return false;
        }
    }
    return std::fflush(f.get()) == 0;
}

bool readRgbe(const std::string& path, ImageF& out) {
    FilePtr f(std::fopen(path.c_str(), "rb"));
    if (!f) return false;
    char line[256];
    if (!std::fgets(line, sizeof line, f.get()) || std::strncmp(line, "#?", 2) != 0) return false;
    // Header lines until the empty line.
    while (std::fgets(line, sizeof line, f.get())) {
        if (line[0] == '\n') break;
        if (std::strncmp(line, "FORMAT=", 7) == 0 && std::strncmp(line + 7, "32-bit_rle_rgbe", 15) != 0) return false;
    }
    int w = 0, h = 0;
    if (std::fscanf(f.get(), "-Y %d +X %d", &h, &w) != 2 || w <= 0 || h <= 0) return false;
    std::fgetc(f.get());  // newline

    out = ImageF(w, h);
    std::vector<uint8_t> rgbe(static_cast<size_t>(w) * 4);
    for (int y = 0; y < h; ++y) {
        uint8_t hdr[4];
        if (std::fread(hdr, 1, 4, f.get()) != 4) return false;
        if (hdr[0] == 2 && hdr[1] == 2 && ((hdr[2] << 8) | hdr[3]) == w && w >= 8 && w < 32768) {
            for (int c = 0; c < 4; ++c) {
                int x = 0;
                while (x < w) {
                    int count = std::fgetc(f.get());
                    if (count == EOF) return false;
                    if (count > 128) {
                        count -= 128;
                        int v = std::fgetc(f.get());
                        if (v == EOF || x + count > w) return false;
                        for (int k = 0; k < count; ++k) rgbe[(x++) * 4 + c] = static_cast<uint8_t>(v);
                    } else {
                        if (count == 0 || x + count > w) return false;
                        for (int k = 0; k < count; ++k) {
                            int v = std::fgetc(f.get());
                            if (v == EOF) return false;
                            rgbe[(x++) * 4 + c] = static_cast<uint8_t>(v);
                        }
                    }
                }
            }
        } else {
            std::memcpy(rgbe.data(), hdr, 4);
            if (std::fread(rgbe.data() + 4, 1, rgbe.size() - 4, f.get()) != rgbe.size() - 4) return false;
        }
        for (int x = 0; x < w; ++x) rgbeToFloat(&rgbe[x * 4], out.px(x, y));
    }
    return true;
}

}  // namespace hdri
