// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <vector>

namespace hdri {

/// Interleaved linear RGB float image (scene-referred).
struct ImageF {
    int width = 0;
    int height = 0;
    std::vector<float> data;  // width * height * 3

    ImageF() = default;
    ImageF(int w, int h) : width(w), height(h), data(static_cast<size_t>(w) * h * 3, 0.0f) {}

    float* px(int x, int y) { return &data[(static_cast<size_t>(y) * width + x) * 3]; }
    const float* px(int x, int y) const { return &data[(static_cast<size_t>(y) * width + x) * 3]; }
    bool empty() const { return data.empty(); }
};

}  // namespace hdri
