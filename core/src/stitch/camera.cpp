// SPDX-License-Identifier: GPL-3.0-or-later
#include "camera.h"

#include <cmath>

namespace hdri {

namespace {
cv::Vec3d rotate(const Quatd& q, const cv::Vec3d& v) {
    const cv::Vec3d u(q.x, q.y, q.z);
    const cv::Vec3d t = 2.0 * u.cross(v);
    return v + q.w * t + u.cross(t);
}
}  // namespace

cv::Vec3d dirFromYawPitch(double yawDeg, double pitchDeg) {
    const double y = yawDeg * CV_PI / 180, p = pitchDeg * CV_PI / 180;
    return {std::cos(p) * std::sin(y), std::cos(p) * std::cos(y), std::sin(p)};
}

SpotCamera::SpotCamera(const LensMeta& lens, const Quatd& pose, double scale) {
    width = static_cast<int>(lens.rawWidth * scale);
    height = static_cast<int>(lens.rawHeight * scale);
    fx = lens.fx * scale;
    fy = lens.fy * scale;
    // Pixel-centre convention: raw pixel i covers [i, i+1); binned pixel j covers raw [2j, 2j+2).
    cx = lens.cx * scale;
    cy = lens.cy * scale;
    dist = lens.distortion;

    // Sensor axes in device coordinates. Device: X right, Y up (portrait), Z out of the screen.
    // The stored image must be rotated clockwise by sensorOrientation to appear upright, so image
    // "up" is device +Y rotated by -sensorOrientation about the optical axis.
    const double a = -lens.sensorOrientation * CV_PI / 180;
    const cv::Vec3d upDev(-std::sin(a), std::cos(a), 0);
    const cv::Vec3d rightDev(std::cos(a), std::sin(a), 0);
    const cv::Vec3d fwdDev(0, 0, -1);
    const cv::Vec3d r = rotate(pose, rightDev), d = rotate(pose, -upDev), f = rotate(pose, fwdDev);
    C = cv::Matx33d(r[0], d[0], f[0], r[1], d[1], f[1], r[2], d[2], f[2]);
    buildLookup();
}

void SpotCamera::correct(double xd, double yd, double& xc, double& yc) const {
    const double k1 = dist[0], k2 = dist[1], k3 = dist[2], p1 = dist[3], p2 = dist[4];
    const double r2 = xd * xd + yd * yd;
    const double radial = 1 + r2 * (k1 + r2 * (k2 + r2 * k3));
    xc = xd * radial + 2 * p1 * xd * yd + p2 * (r2 + 2 * xd * xd);
    yc = yd * radial + p1 * (r2 + 2 * yd * yd) + 2 * p2 * xd * yd;
}

void SpotCamera::buildLookup() {
    // Range of corrected coords: map the image corners (plus margin) through correct().
    double xmin = 1e9, xmax = -1e9, ymin = 1e9, ymax = -1e9;
    for (int i = 0; i <= 20; ++i)
        for (int j = 0; j <= 20; ++j) {
            const double u = -0.1 * width + 1.2 * width * i / 20, v = -0.1 * height + 1.2 * height * j / 20;
            double xc, yc;
            correct((u - cx) / fx, (v - cy) / fy, xc, yc);
            xmin = std::min(xmin, xc); xmax = std::max(xmax, xc);
            ymin = std::min(ymin, yc); ymax = std::max(ymax, yc);
        }
    lutStep_ = std::max(xmax - xmin, ymax - ymin) / 400.0;
    lutX0_ = xmin;
    lutY0_ = ymin;
    lutW_ = static_cast<int>((xmax - xmin) / lutStep_) + 2;
    lutH_ = static_cast<int>((ymax - ymin) / lutStep_) + 2;
    lut_.resize(static_cast<size_t>(lutW_) * lutH_);
    for (int j = 0; j < lutH_; ++j)
        for (int i = 0; i < lutW_; ++i) {
            const double xc = lutX0_ + i * lutStep_, yc = lutY0_ + j * lutStep_;
            // Newton-free fixed-point inversion: distortion is mild, so this converges quickly.
            double xd = xc, yd = yc;
            for (int it = 0; it < 20; ++it) {
                double fx2, fy2;
                correct(xd, yd, fx2, fy2);
                xd -= fx2 - xc;
                yd -= fy2 - yc;
            }
            lut_[static_cast<size_t>(j) * lutW_ + i] = cv::Vec2f(static_cast<float>(xd), static_cast<float>(yd));
        }
}

bool SpotCamera::project(const cv::Vec3d& w, double& u, double& v) const {
    // Camera coords = C^T * w.
    const double X = C(0, 0) * w[0] + C(1, 0) * w[1] + C(2, 0) * w[2];
    const double Y = C(0, 1) * w[0] + C(1, 1) * w[1] + C(2, 1) * w[2];
    const double Z = C(0, 2) * w[0] + C(1, 2) * w[1] + C(2, 2) * w[2];
    if (Z <= 1e-6) return false;
    const double xc = X / Z, yc = Y / Z;
    const double fi = (xc - lutX0_) / lutStep_, fj = (yc - lutY0_) / lutStep_;
    if (fi < 0 || fj < 0 || fi >= lutW_ - 1 || fj >= lutH_ - 1) return false;
    const int i = static_cast<int>(fi), j = static_cast<int>(fj);
    const float ti = static_cast<float>(fi - i), tj = static_cast<float>(fj - j);
    const cv::Vec2f* row0 = &lut_[static_cast<size_t>(j) * lutW_ + i];
    const cv::Vec2f* row1 = row0 + lutW_;
    const cv::Vec2f d = (row0[0] * (1 - ti) + row0[1] * ti) * (1 - tj) + (row1[0] * (1 - ti) + row1[1] * ti) * tj;
    u = fx * d[0] + cx;
    v = fy * d[1] + cy;
    return u >= 0 && v >= 0 && u <= width - 1 && v <= height - 1;
}

cv::Vec3d SpotCamera::unprojectCamera(double u, double v) const {
    double xc, yc;
    correct((u - cx) / fx, (v - cy) / fy, xc, yc);
    return cv::normalize(cv::Vec3d(xc, yc, 1.0));
}

cv::Vec3d SpotCamera::unproject(double u, double v) const { return C * unprojectCamera(u, v); }

}  // namespace hdri
