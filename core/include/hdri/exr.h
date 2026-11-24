// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <string>
#include "hdri/image.h"

namespace hdri {
/// Writes an OpenEXR file (RGB, half float, ZIP compression).
bool writeExr(const std::string& path, const ImageF& img, std::string* error = nullptr);
}  // namespace hdri
