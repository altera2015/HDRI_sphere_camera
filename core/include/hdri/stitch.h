// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <functional>
#include <string>
#include <vector>

#include "hdri/session.h"

namespace hdri {

struct StitchOptions {
    /// Equirectangular output width; height is width / 2.
    int outWidth = 4096;
    std::string outDir;       // defaults to <session>/output
    std::string baseName = "environment";
    bool writeHdr = true;
    bool writeExr = true;
    bool writeJpeg = true;     // tone-mapped preview
    int previewWidth = 2048;
    /// Refine gyro poses with feature matching.
    bool refinePoses = true;
    /// Also write each spot's merged radiance (debugging).
    bool writeSpotImages = false;
};

struct StitchResult {
    bool ok = false;
    std::string error;
    std::vector<std::string> files;
    /// Diagnostics: pose refinement.
    int matchedPairs = 0;
    double rmsBeforeDeg = 0, rmsAfterDeg = 0;
    double holeFraction = 0;
    /// Luminance statistics of the panorama (solid-angle weighted).
    double lumP1 = 0, lumMedian = 0, lumP99 = 0, lumMax = 0;
    /// log2(max / p1): usable dynamic range in stops.
    double dynamicRangeStops = 0;
};

/// stage: short label; fraction: 0..1 overall progress.
using ProgressFn = std::function<void(const std::string& stage, float fraction)>;

/// Runs the full pipeline: develop + merge each spot, refine poses, render equirect, write files.
StitchResult processSession(const SessionMeta& session, const StitchOptions& opts, const ProgressFn& progress = {});

}  // namespace hdri
