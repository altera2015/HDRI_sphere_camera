// SPDX-License-Identifier: GPL-3.0-or-later
// hdri-stitch: runs the HDRI Sphere pipeline on a session directory (session.json + DNGs).
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include "hdri/stitch.h"

static void usage() {
    std::fprintf(stderr,
                 "usage: hdri-stitch <session_dir> [options]\n"
                 "  -w <width>     output width (default 4096; height = width/2)\n"
                 "  -o <dir>       output directory (default <session_dir>/output)\n"
                 "  -n <name>      base file name (default environment)\n"
                 "  --no-refine    use gyro poses only\n"
                 "  --spots        also write each spot's merged radiance\n"
                 "  --no-exr / --no-hdr / --no-jpeg\n");
}

int main(int argc, char** argv) {
    if (argc < 2) { usage(); return 2; }
    hdri::StitchOptions opts;
    std::string dir = argv[1];
    for (int i = 2; i < argc; ++i) {
        std::string a = argv[i];
        auto next = [&]() -> const char* { if (i + 1 >= argc) { usage(); std::exit(2); } return argv[++i]; };
        if (a == "-w") opts.outWidth = std::atoi(next());
        else if (a == "-o") opts.outDir = next();
        else if (a == "-n") opts.baseName = next();
        else if (a == "--no-refine") opts.refinePoses = false;
        else if (a == "--spots") opts.writeSpotImages = true;
        else if (a == "--no-exr") opts.writeExr = false;
        else if (a == "--no-hdr") opts.writeHdr = false;
        else if (a == "--no-jpeg") opts.writeJpeg = false;
        else { usage(); return 2; }
    }

    hdri::SessionMeta session;
    std::string err;
    if (!hdri::loadSession(dir, session, &err)) {
        std::fprintf(stderr, "error: %s\n", err.c_str());
        return 1;
    }
    std::printf("session %s: %zu spots captured\n", session.id.c_str(), session.spots.size());

    const auto t0 = std::chrono::steady_clock::now();
    std::string lastStage;
    auto res = hdri::processSession(session, opts, [&](const std::string& stage, float f) {
        if (stage != lastStage) {
            const double t = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
            std::printf("[%5.1fs] %3.0f%% %s\n", t, f * 100, stage.c_str());
            std::fflush(stdout);
            lastStage = stage;
        }
    });
    if (!res.ok) {
        std::fprintf(stderr, "error: %s\n", res.error.c_str());
        return 1;
    }
    std::printf("alignment: %d pairs, rms %.3f° -> %.3f°\n", res.matchedPairs, res.rmsBeforeDeg, res.rmsAfterDeg);
    std::printf("uncovered (filled): %.2f%%\n", res.holeFraction * 100);
    std::printf("dynamic range: %.1f stops (luminance p1 %.3g, median %.3g, p99 %.3g, max %.3g)\n", res.dynamicRangeStops,
                res.lumP1, res.lumMedian, res.lumP99, res.lumMax);
    for (const auto& f : res.files) std::printf("wrote %s\n", f.c_str());
    return 0;
}
