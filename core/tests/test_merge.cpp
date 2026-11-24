// SPDX-License-Identifier: GPL-3.0-or-later
#include <algorithm>
#include <cmath>

#include "check.h"
#include "hdri/merge.h"

namespace {
// Simulates a linear sensor: value = clamp(radiance * exposure, 0, 1).
hdri::ImageF expose(const hdri::ImageF& radiance, double exposure) {
    hdri::ImageF out(radiance.width, radiance.height);
    for (size_t i = 0; i < out.data.size(); ++i)
        out.data[i] = static_cast<float>(std::clamp(radiance.data[i] * exposure, 0.0, 1.0));
    return out;
}
}  // namespace

TEST_CASE(merge_recovers_radiance_ramp) {
    // Radiance from 1e-3 to 1e2 (17 stops), grey.
    hdri::ImageF radiance(256, 1);
    for (int x = 0; x < radiance.width; ++x) {
        float v = std::pow(10.0f, -3.0f + 5.0f * x / (radiance.width - 1));
        float* p = radiance.px(x, 0);
        p[0] = p[1] = p[2] = v;
    }
    std::vector<hdri::ImageF> imgs;
    std::vector<double> exps;
    for (int ev = -8; ev <= 4; ev += 2) exps.push_back(std::ldexp(1.0, ev));
    for (double e : exps) imgs.push_back(expose(radiance, e));
    std::vector<hdri::BracketFrame> frames;
    for (size_t i = 0; i < imgs.size(); ++i) frames.push_back({&imgs[i], exps[i]});

    hdri::ImageF merged = hdri::mergeBrackets(frames);
    CHECK(merged.width == radiance.width);
    double maxRel = 0;
    for (size_t i = 0; i < merged.data.size(); ++i)
        maxRel = std::max(maxRel, static_cast<double>(std::fabs(merged.data[i] - radiance.data[i]) / radiance.data[i]));
    CHECK(maxRel < 0.01);
}

TEST_CASE(merge_all_clipped_uses_shortest) {
    hdri::ImageF a(1, 1), b(1, 1);
    for (float& v : a.data) v = 1.0f;
    for (float& v : b.data) v = 1.0f;
    std::vector<hdri::BracketFrame> frames{{&a, 1.0}, {&b, 0.25}};
    hdri::ImageF m = hdri::mergeBrackets(frames);
    CHECK(std::fabs(m.data[0] - 4.0f) < 1e-5f);  // 1.0 / 0.25
}

TEST_CASE(merge_rejects_mismatched_sizes) {
    hdri::ImageF a(2, 2), b(3, 2);
    std::vector<hdri::BracketFrame> frames{{&a, 1.0}, {&b, 2.0}};
    CHECK(hdri::mergeBrackets(frames).empty());
}
