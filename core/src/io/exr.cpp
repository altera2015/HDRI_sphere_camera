// SPDX-License-Identifier: GPL-3.0-or-later
#define TINYEXR_IMPLEMENTATION
#define TINYEXR_USE_MINIZ 1
#define TINYEXR_USE_THREAD 0
#include "miniz.h"
#include "tinyexr.h"

#include "hdri/exr.h"

namespace hdri {

bool writeExr(const std::string& path, const ImageF& img, std::string* error) {
    const char* err = nullptr;
    int rc = SaveEXR(img.data.data(), img.width, img.height, 3, /*save_as_fp16=*/1, path.c_str(), &err);
    if (rc != TINYEXR_SUCCESS) {
        if (error) *error = err ? err : "EXR write failed";
        if (err) FreeEXRErrorMessage(err);
        return false;
    }
    return true;
}

}  // namespace hdri
