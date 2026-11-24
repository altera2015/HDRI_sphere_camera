// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <vector>

#include <opencv2/core.hpp>

#include "camera.h"

namespace hdri {

struct AlignStats {
    int pairs = 0;
    double rmsBeforeDeg = 0, rmsAfterDeg = 0;
};

/// Refines camera rotations in place. [gray] are 8-bit feature images, [grayScale] = gray size / camera size.
AlignStats refinePoses(std::vector<SpotCamera>& cams, const std::vector<cv::Mat>& gray, double grayScale);

}  // namespace hdri
