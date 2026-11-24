// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstdint>
#include <string>
#include <vector>

#include "hdri/image.h"

namespace hdri {

/// Single-channel Bayer mosaic as stored by the sensor.
struct RawImage {
    int width = 0;
    int height = 0;
    std::vector<uint16_t> data;
};

/// Reads the full-resolution CFA image from an uncompressed DNG (as written by Android's DngCreator).
bool readDngRaw(const std::string& path, RawImage& out, std::string* error = nullptr);

/// Per-frame parameters needed to develop RAW into scene-linear RGB.
struct RawParams {
    /// CFA layout of the top-left 2x2 block: "RGGB", "GRBG", "GBRG" or "BGGR".
    std::string cfa = "RGGB";
    /// Black level per 2x2 position, in the order (0,0), (1,0), (0,1), (1,1).
    std::array<float, 4> black{64, 64, 64, 64};
    float white = 1023;
    /// Camera2 lens shading map: rows x cols grid of [R, Geven, Godd, B] gains over the image.
    int shadingRows = 0, shadingCols = 0;
    std::vector<float> shading;
    /// Camera-space white (AS_SHOT_NEUTRAL), R G B; white balance gains are 1/neutral.
    std::array<double, 3> neutral{1, 1, 1};
    /// White-balanced camera RGB -> linear sRGB (row-major). Identity if unknown.
    std::array<double, 9> colorTransform{1, 0, 0, 0, 1, 0, 0, 0, 1};
};

/// Fraction of the white level at or above which a raw sample counts as clipped.
constexpr float kClipLevel = 0.98f;

/// A developed frame: scene-linear sRGB plus the per-pixel max raw level (0..1) for clip-aware merging.
struct DevelopedFrame {
    ImageF rgb;
    std::vector<float> level;
    float clipFraction = 0;
};

/// Develops RAW to half-resolution linear sRGB by 2x2 binning: no demosaic artefacts, and each
/// output pixel is one full CFA quad. Applies black level, lens shading, white balance and the
/// colour transform. Green at sensor white maps to 1.0.
DevelopedFrame developHalfRes(const RawImage& raw, const RawParams& p);

}  // namespace hdri
