// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <vector>
#include "hdri/image.h"

namespace hdri {

/// One frame of a bracket: scene-linear RGB proportional to sensor counts.
struct BracketFrame {
    const ImageF* image = nullptr;
    /// Relative exposure, e.g. exposureTimeSeconds * iso / 100. Radiance = value / exposure.
    double exposure = 1.0;
    /// Optional per-pixel sensor level in 0..1 (max raw sample before gains) used for weighting and
    /// clip detection. If null, the max RGB channel of [image] is used.
    const std::vector<float>* level = nullptr;
};

struct MergeOptions {
    /// Normalised level at or above which a sample counts as clipped.
    float clipLevel = 0.98f;
    /// Normalised level below which a sample is considered noise.
    float noiseFloor = 0.002f;
};

/// Merges an exposure bracket into one radiance image (units: normalised value per unit exposure).
/// All frames must share dimensions. Returns an empty image on invalid input.
ImageF mergeBrackets(const std::vector<BracketFrame>& frames, const MergeOptions& opts = {});

}  // namespace hdri
