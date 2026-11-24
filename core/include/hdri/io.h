// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <string>
#include "hdri/image.h"

namespace hdri {

/// Writes a Radiance RGBE (.hdr) file, run-length encoded.
bool writeRgbe(const std::string& path, const ImageF& img);

/// Reads a Radiance RGBE (.hdr) file (RLE or flat). Used by tests and the CLI.
bool readRgbe(const std::string& path, ImageF& out);

}  // namespace hdri
