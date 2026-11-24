// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <string>
#include <vector>

#include "hdri/raw.h"

namespace hdri {

struct Quatd {
    double w = 1, x = 0, y = 0, z = 0;
};

struct FrameMeta {
    std::string file;
    long long exposureTimeNs = 0;
    int iso = 100;
    Quatd pose;  // device -> world
    std::array<float, 4> blackLevel{64, 64, 64, 64};
    std::array<double, 3> neutral{1, 1, 1};
    std::array<double, 9> colorTransform{1, 0, 0, 0, 1, 0, 0, 0, 1};
    double relativeExposure() const { return exposureTimeNs * 1e-9 * iso / 100.0; }
};

struct SpotMeta {
    int spotIndex = 0;
    double yawDeg = 0, pitchDeg = 0;
    bool highlightsClipped = false;
    int shadingRows = 0, shadingCols = 0;
    std::vector<float> shading;
    std::vector<FrameMeta> frames;
};

struct LensMeta {
    int rawWidth = 0, rawHeight = 0;
    int sensorOrientation = 90;
    double focalLengthMm = 0, sensorWidthMm = 0, sensorHeightMm = 0;
    bool hasIntrinsics = false;
    double fx = 0, fy = 0, cx = 0, cy = 0;
    std::array<double, 5> distortion{0, 0, 0, 0, 0};  // Camera2 order: k1 k2 k3 p1 p2
    std::string cfa = "RGGB";
    float whiteLevel = 1023;
};

struct SessionMeta {
    std::string id;
    std::string dir;
    double headingDeg = 0;
    bool tripod = false;
    LensMeta lens;
    std::vector<SpotMeta> spots;  // captured spots only, in capture order
};

/// Loads session.json from [dir]. Returns false with [error] set on failure.
bool loadSession(const std::string& dir, SessionMeta& out, std::string* error = nullptr);

/// Builds RAW development parameters for one frame.
RawParams rawParamsFor(const SessionMeta& s, const SpotMeta& spot, const FrameMeta& f);

}  // namespace hdri
