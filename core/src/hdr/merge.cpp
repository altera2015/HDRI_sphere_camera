// SPDX-License-Identifier: GPL-3.0-or-later
#include <algorithm>
#include <cmath>

#include "hdri/merge.h"

namespace hdri {
namespace {

// Hat weight on the brightest channel: favours mid-tones, zero at clip and in noise.
float hatWeight(float z, const MergeOptions& o) {
    if (z >= o.clipLevel || z <= o.noiseFloor) return 0.0f;
    float t = (z - o.noiseFloor) / (o.clipLevel - o.noiseFloor);  // 0..1
    return 1.0f - std::pow(2.0f * t - 1.0f, 12.0f);               // flat-topped hat
}

float levelOf(const BracketFrame& fr, size_t p) {
    if (fr.level) return (*fr.level)[p];
    const float* s = &fr.image->data[p * 3];
    return std::max(s[0], std::max(s[1], s[2]));
}

}  // namespace

ImageF mergeBrackets(const std::vector<BracketFrame>& frames, const MergeOptions& opts) {
    if (frames.empty() || !frames[0].image) return {};
    const int w = frames[0].image->width, h = frames[0].image->height;
    for (const auto& fr : frames) {
        if (!fr.image || fr.image->width != w || fr.image->height != h || !(fr.exposure > 0)) return {};
        if (fr.level && fr.level->size() != static_cast<size_t>(w) * h) return {};
    }
    // Indices of the shortest and longest exposure, for fallback when every sample is rejected.
    size_t shortest = 0, longest = 0;
    for (size_t i = 1; i < frames.size(); ++i) {
        if (frames[i].exposure < frames[shortest].exposure) shortest = i;
        if (frames[i].exposure > frames[longest].exposure) longest = i;
    }

    ImageF out(w, h);
    const size_t n = static_cast<size_t>(w) * h;
    for (size_t p = 0; p < n; ++p) {
        double acc[3] = {0, 0, 0}, wsum = 0;
        for (const auto& fr : frames) {
            const float* s = &fr.image->data[p * 3];
            float zmax = levelOf(fr, p);
            float wt = hatWeight(zmax, opts);
            if (wt <= 0) continue;
            // Weight by exposure too: longer exposures have better SNR for the same value.
            double we = wt * fr.exposure;
            for (int c = 0; c < 3; ++c) acc[c] += we * (s[c] / fr.exposure);
            wsum += we;
        }
        float* d = &out.data[p * 3];
        if (wsum > 0) {
            for (int c = 0; c < 3; ++c) d[c] = static_cast<float>(acc[c] / wsum);
        } else {
            // All clipped -> best guess is the shortest exposure; all dark -> the longest.
            const auto& sh = frames[shortest];
            bool clipped = levelOf(sh, p) >= opts.clipLevel;
            const auto& fr = clipped ? sh : frames[longest];
            const float* v = &fr.image->data[p * 3];
            for (int c = 0; c < 3; ++c) d[c] = static_cast<float>(std::max(0.0f, v[c]) / fr.exposure);
        }
    }
    return out;
}

}  // namespace hdri
