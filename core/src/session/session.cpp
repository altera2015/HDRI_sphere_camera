// SPDX-License-Identifier: GPL-3.0-or-later
#include <fstream>

#include "hdri/session.h"
#include "json.hpp"

namespace hdri {

bool loadSession(const std::string& dir, SessionMeta& out, std::string* error) {
    using nlohmann::json;
    std::ifstream in(dir + "/session.json");
    if (!in) {
        if (error) *error = "cannot open " + dir + "/session.json";
        return false;
    }
    try {
        json j = json::parse(in);
        out = SessionMeta{};
        out.dir = dir;
        out.id = j.value("id", "");
        out.headingDeg = j.value("headingDeg", 0.0);
        out.tripod = j.value("rigMode", "HANDHELD") == "TRIPOD";
        const auto& L = j.at("lens");
        auto& lens = out.lens;
        lens.rawWidth = L.at("rawWidth");
        lens.rawHeight = L.at("rawHeight");
        lens.sensorOrientation = L.value("sensorOrientation", 90);
        lens.focalLengthMm = L.at("focalLengthMm");
        lens.sensorWidthMm = L.at("sensorWidthMm");
        lens.sensorHeightMm = L.at("sensorHeightMm");
        lens.cfa = L.value("cfa", "RGGB");
        lens.whiteLevel = L.value("whiteLevel", 1023.0f);
        if (L.contains("intrinsics") && L["intrinsics"].is_array() && L["intrinsics"].size() >= 4) {
            const auto& k = L["intrinsics"];
            lens.fx = k[0]; lens.fy = k[1]; lens.cx = k[2]; lens.cy = k[3];
            lens.hasIntrinsics = lens.fx > 0;
        }
        if (!lens.hasIntrinsics) {
            // Fall back to the nominal focal length over the sensor size.
            const double longMm = std::max(lens.sensorWidthMm, lens.sensorHeightMm);
            lens.fx = lens.fy = lens.focalLengthMm / longMm * std::max(lens.rawWidth, lens.rawHeight);
            lens.cx = lens.rawWidth / 2.0;
            lens.cy = lens.rawHeight / 2.0;
        }
        if (L.contains("distortion") && L["distortion"].is_array() && L["distortion"].size() >= 5) {
            for (int i = 0; i < 5; ++i) lens.distortion[i] = L["distortion"][i];
        }

        std::vector<std::pair<double, double>> plan;
        for (const auto& s : j.at("spots")) plan.emplace_back(s.at("yawDeg"), s.at("pitchDeg"));

        for (const auto& c : j.at("captures")) {
            SpotMeta sp;
            sp.spotIndex = c.at("spotIndex");
            if (sp.spotIndex >= 0 && sp.spotIndex < static_cast<int>(plan.size())) {
                sp.yawDeg = plan[sp.spotIndex].first;
                sp.pitchDeg = plan[sp.spotIndex].second;
            }
            sp.highlightsClipped = c.value("highlightsClipped", false);
            if (c.contains("lensShadingMap") && c["lensShadingMap"].is_array() && c["lensShadingMap"].size() > 2) {
                const auto& m = c["lensShadingMap"];
                sp.shadingRows = static_cast<int>(m[0].get<double>());
                sp.shadingCols = static_cast<int>(m[1].get<double>());
                for (size_t i = 2; i < m.size(); ++i) sp.shading.push_back(m[i].get<float>());
            }
            for (const auto& f : c.at("frames")) {
                FrameMeta fm;
                fm.file = f.at("file");
                fm.exposureTimeNs = f.at("exposureTimeNs");
                fm.iso = f.at("iso");
                const auto& q = f.at("pose");
                fm.pose = {q[0], q[1], q[2], q[3]};
                const auto& b = f.at("blackLevel");
                for (int i = 0; i < 4 && i < static_cast<int>(b.size()); ++i) fm.blackLevel[i] = b[i];
                if (f.contains("neutral") && f["neutral"].is_array())
                    for (int i = 0; i < 3; ++i) fm.neutral[i] = f["neutral"][i];
                if (f.contains("colorTransform") && f["colorTransform"].is_array())
                    for (int i = 0; i < 9; ++i) fm.colorTransform[i] = f["colorTransform"][i];
                sp.frames.push_back(fm);
            }
            if (!sp.frames.empty()) out.spots.push_back(std::move(sp));
        }
    } catch (const std::exception& e) {
        if (error) *error = std::string("bad session.json: ") + e.what();
        return false;
    }
    return true;
}

RawParams rawParamsFor(const SessionMeta& s, const SpotMeta& spot, const FrameMeta& f) {
    RawParams p;
    p.cfa = s.lens.cfa;
    p.black = f.blackLevel;
    p.white = s.lens.whiteLevel;
    p.shadingRows = spot.shadingRows;
    p.shadingCols = spot.shadingCols;
    p.shading = spot.shading;
    p.neutral = f.neutral;
    p.colorTransform = f.colorTransform;
    return p;
}

}  // namespace hdri
