// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <vector>

#include <opencv2/core.hpp>

#include "hdri/session.h"

namespace hdri {

/// Pinhole camera with Camera2 lens distortion, oriented in the world (Z up).
/// Image axes: x right, y down, z forward (OpenCV convention).
class SpotCamera {
public:
    SpotCamera() = default;
    /// [scale] maps raw pixel coordinates to the image used here (0.5 for half-res).
    SpotCamera(const LensMeta& lens, const Quatd& devicePose, double scale);

    int width = 0, height = 0;
    double fx = 0, fy = 0, cx = 0, cy = 0;
    std::array<double, 5> dist{};  // k1 k2 k3 p1 p2 (Camera2 order)
    /// Camera-to-world rotation (columns: image right, image down, forward in world coordinates).
    cv::Matx33d C = cv::Matx33d::eye();

    /// Projects a world direction to pixel coordinates. Returns false if behind or outside the image.
    bool project(const cv::Vec3d& worldDir, double& u, double& v) const;
    /// Unit ray in world coordinates through pixel (u, v).
    cv::Vec3d unproject(double u, double v) const;
    /// Unit ray in camera coordinates through pixel (u, v).
    cv::Vec3d unprojectCamera(double u, double v) const;
    cv::Vec3d forward() const { return {C(0, 2), C(1, 2), C(2, 2)}; }

    /// Precomputes the inverse-distortion lookup used by project(). Call after changing intrinsics.
    void buildLookup();

private:
    /// Camera2 LENS_DISTORTION maps distorted (observed) normalised coords to corrected ones.
    void correct(double xd, double yd, double& xc, double& yc) const;
    // Lookup over corrected normalised coords -> distorted normalised coords.
    double lutX0_ = 0, lutY0_ = 0, lutStep_ = 0;
    int lutW_ = 0, lutH_ = 0;
    std::vector<cv::Vec2f> lut_;
};

/// Direction (world, Z up) for yaw (compass degrees, clockwise from +Y) and pitch (degrees up).
cv::Vec3d dirFromYawPitch(double yawDeg, double pitchDeg);

}  // namespace hdri
