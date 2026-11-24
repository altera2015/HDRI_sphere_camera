// SPDX-License-Identifier: GPL-3.0-or-later
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>

#include "check.h"
#include "hdri/io.h"

static std::string tmpPath(const char* name) {
    const char* dir = std::getenv("TMPDIR");
    return std::string(dir ? dir : "/tmp") + "/" + name;
}

TEST_CASE(rgbe_roundtrip_wide_range) {
    // Width 64 exercises RLE; values span 12 orders of magnitude plus flat runs.
    hdri::ImageF img(64, 16);
    for (int y = 0; y < img.height; ++y)
        for (int x = 0; x < img.width; ++x) {
            float* p = img.px(x, y);
            float v = (x < 20) ? 0.5f : std::pow(10.0f, (x - 40) / 4.0f + y * 0.1f);
            p[0] = v; p[1] = v * 0.5f; p[2] = v * 0.25f;
        }
    std::string path = tmpPath("hdri_test_roundtrip.hdr");
    CHECK(hdri::writeRgbe(path, img));
    hdri::ImageF back;
    CHECK(hdri::readRgbe(path, back));
    CHECK(back.width == img.width && back.height == img.height);
    if (back.width != img.width || back.height != img.height) return;
    double maxRel = 0;
    for (size_t i = 0; i < img.data.size(); i += 3) {
        // RGBE shares the exponent, so measure error relative to the pixel's max channel.
        double ref = img.data[i];
        for (int c = 0; c < 3; ++c) maxRel = std::max(maxRel, std::fabs(back.data[i + c] - img.data[i + c]) / ref);
    }
    CHECK(maxRel < 0.01);
    std::remove(path.c_str());
}

TEST_CASE(rgbe_zero_negative_nan_become_black) {
    hdri::ImageF img(8, 1);
    img.px(0, 0)[0] = -1.0f;
    img.px(1, 0)[1] = std::nanf("");
    std::string path = tmpPath("hdri_test_black.hdr");
    CHECK(hdri::writeRgbe(path, img));
    hdri::ImageF back;
    CHECK(hdri::readRgbe(path, back));
    for (float v : back.data) CHECK(v == 0.0f);
    std::remove(path.c_str());
}
