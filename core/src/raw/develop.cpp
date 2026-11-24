// SPDX-License-Identifier: GPL-3.0-or-later
#include <algorithm>
#include <cmath>

#include "hdri/raw.h"

namespace hdri {
namespace {

// Maps each 2x2 position p = (y & 1) * 2 + (x & 1) to a shading/colour channel:
// 0 = R, 1 = G (even row), 2 = G (odd row), 3 = B.
std::array<int, 4> cfaLayout(const std::string& cfa) {
    if (cfa == "GRBG") return {1, 0, 3, 2};
    if (cfa == "GBRG") return {1, 3, 0, 2};
    if (cfa == "BGGR") return {3, 1, 2, 0};
    return {0, 1, 2, 3};  // RGGB
}

// Bilinearly samples the shading map's gain for channel c at normalised image coords (u, v).
float shadingGain(const RawParams& p, int c, float u, float v) {
    if (p.shadingRows < 2 || p.shadingCols < 2 ||
        p.shading.size() < static_cast<size_t>(p.shadingRows) * p.shadingCols * 4) return 1.0f;
    const float fx = u * (p.shadingCols - 1), fy = v * (p.shadingRows - 1);
    const int x0 = std::clamp(static_cast<int>(fx), 0, p.shadingCols - 2);
    const int y0 = std::clamp(static_cast<int>(fy), 0, p.shadingRows - 2);
    const float tx = fx - x0, ty = fy - y0;
    auto g = [&](int x, int y) { return p.shading[(static_cast<size_t>(y) * p.shadingCols + x) * 4 + c]; };
    return (g(x0, y0) * (1 - tx) + g(x0 + 1, y0) * tx) * (1 - ty) +
           (g(x0, y0 + 1) * (1 - tx) + g(x0 + 1, y0 + 1) * tx) * ty;
}

}  // namespace

DevelopedFrame developHalfRes(const RawImage& raw, const RawParams& p) {
    DevelopedFrame out;
    const int w = raw.width / 2, h = raw.height / 2;
    if (w <= 0 || h <= 0) return out;
    out.rgb = ImageF(w, h);
    out.level.assign(static_cast<size_t>(w) * h, 0.0f);
    const auto layout = cfaLayout(p.cfa);
    // White balance gains normalised to green.
    const double gR = p.neutral[1] / std::max(1e-6, p.neutral[0]);
    const double gB = p.neutral[1] / std::max(1e-6, p.neutral[2]);
    const auto& M = p.colorTransform;
    size_t clipped = 0;

    std::vector<float> rowGains(static_cast<size_t>(w) * 4);
    for (int y = 0; y < h; ++y) {
        const float v = (y + 0.5f) / h;
        for (int x = 0; x < w; ++x) {
            const float u = (x + 0.5f) / w;
            for (int c = 0; c < 4; ++c) rowGains[x * 4 + c] = shadingGain(p, c, u, v);
        }
        for (int x = 0; x < w; ++x) {
            float ch[4] = {0, 0, 0, 0};
            float maxLevel = 0;
            for (int pos = 0; pos < 4; ++pos) {
                const int sx = 2 * x + (pos & 1), sy = 2 * y + (pos >> 1);
                const float s = raw.data[static_cast<size_t>(sy) * raw.width + sx];
                const float n = (s - p.black[pos]) / (p.white - p.black[pos]);
                maxLevel = std::max(maxLevel, n);
                const int c = layout[pos];
                ch[c] = n * rowGains[x * 4 + c];
            }
            const double r = ch[0] * gR, g = 0.5 * (ch[1] + ch[2]), b = ch[3] * gB;
            float* d = out.rgb.px(x, y);
            d[0] = static_cast<float>(M[0] * r + M[1] * g + M[2] * b);
            d[1] = static_cast<float>(M[3] * r + M[4] * g + M[5] * b);
            d[2] = static_cast<float>(M[6] * r + M[7] * g + M[8] * b);
            out.level[static_cast<size_t>(y) * w + x] = maxLevel;
            if (maxLevel >= kClipLevel) ++clipped;
        }
    }
    out.clipFraction = static_cast<float>(clipped) / (static_cast<float>(w) * h);
    return out;
}

}  // namespace hdri
