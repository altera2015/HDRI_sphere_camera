// SPDX-License-Identifier: GPL-3.0-or-later
// Pose refinement: SIFT matches between overlapping spots, rotation-only RANSAC per pair, then a
// global Gauss-Newton adjustment of all camera rotations with a weak prior towards the gyro poses.
#include "align.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <random>

#include <opencv2/calib3d.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/imgproc.hpp>

namespace hdri {
namespace {

struct PairMatches {
    int i = 0, j = 0;
    std::vector<cv::Vec3d> ri, rj;  // unit rays in each camera's frame
};

cv::Matx33d kabsch(const std::vector<cv::Vec3d>& a, const std::vector<cv::Vec3d>& b, const std::vector<int>& idx) {
    cv::Matx33d H = cv::Matx33d::zeros();
    for (int k : idx) H += a[k] * b[k].t();
    cv::Mat w, u, vt;
    cv::SVD::compute(cv::Mat(H), w, u, vt);
    cv::Matx33d U(u), Vt(vt);
    cv::Matx33d R = Vt.t() * U.t();
    if (cv::determinant(R) < 0) {
        cv::Matx33d D = cv::Matx33d::eye();
        D(2, 2) = -1;
        R = Vt.t() * D * U.t();
    }
    return R;  // R * a ≈ b
}

double rotationAngle(const cv::Matx33d& R) {
    return std::acos(std::clamp((cv::trace(R) - 1) / 2, -1.0, 1.0));
}

cv::Matx33d expMap(const cv::Vec3d& w) {
    cv::Matx33d R;
    cv::Rodrigues(w, R);
    return R;
}

cv::Vec3d logMap(const cv::Matx33d& R) {
    cv::Vec3d w;
    cv::Rodrigues(R, w);
    return w;
}

cv::Matx33d skew(const cv::Vec3d& v) { return {0, -v[2], v[1], v[2], 0, -v[0], -v[1], v[0], 0}; }

}  // namespace

AlignStats refinePoses(std::vector<SpotCamera>& cams, const std::vector<cv::Mat>& gray, double grayScale) {
    AlignStats stats;
    const int n = static_cast<int>(cams.size());
    if (n < 2) return stats;

    auto sift = cv::SIFT::create(4000, 3, 0.02);
    std::vector<std::vector<cv::KeyPoint>> kps(n);
    std::vector<cv::Mat> desc(n);
    cv::parallel_for_(cv::Range(0, n), [&](const cv::Range& r) {
        for (int k = r.start; k < r.end; ++k) sift->detectAndCompute(gray[k], cv::noArray(), kps[k], desc[k]);
    });

    // Angular threshold for inliers: ~4 pixels in the feature image (tolerates a little parallax).
    const double thresh = 4.0 / (cams[0].fx * grayScale);
    // Camera field of view across the long side, for picking overlapping pairs.
    const double fovLong = 2 * std::atan(std::max(cams[0].width, cams[0].height) / (2 * cams[0].fx));

    std::vector<std::pair<int, int>> candidates;
    for (int i = 0; i < n; ++i)
        for (int j = i + 1; j < n; ++j) {
            const double ang = std::acos(std::clamp(cams[i].forward().dot(cams[j].forward()), -1.0, 1.0));
            if (ang < fovLong * 0.95) candidates.emplace_back(i, j);
        }

    std::vector<PairMatches> pairs(candidates.size());
    std::vector<char> keep(candidates.size(), 0);
    cv::parallel_for_(cv::Range(0, static_cast<int>(candidates.size())), [&](const cv::Range& range) {
        cv::BFMatcher matcher(cv::NORM_L2);
        std::mt19937 rng(1234);
        for (int c = range.start; c < range.end; ++c) {
            const auto [i, j] = candidates[c];
            if (desc[i].empty() || desc[j].empty()) continue;
            std::vector<std::vector<cv::DMatch>> knn;
            matcher.knnMatch(desc[i], desc[j], knn, 2);
            PairMatches pm;
            pm.i = i;
            pm.j = j;
            for (const auto& m : knn) {
                if (m.size() < 2 || m[0].distance > 0.75f * m[1].distance) continue;
                const auto& a = kps[i][m[0].queryIdx].pt;
                const auto& b = kps[j][m[0].trainIdx].pt;
                pm.ri.push_back(cams[i].unprojectCamera(a.x / grayScale, a.y / grayScale));
                pm.rj.push_back(cams[j].unprojectCamera(b.x / grayScale, b.y / grayScale));
            }
            const int m = static_cast<int>(pm.ri.size());
            if (std::getenv("HDRI_DEBUG")) std::fprintf(stderr, "pair %d-%d: %zu/%zu kp, %d ratio matches\n", i, j, kps[i].size(), kps[j].size(), m);
            if (m < 20) continue;

            // Gyro-predicted relative rotation (camera i frame -> camera j frame).
            const cv::Matx33d Rgyro = cams[j].C.t() * cams[i].C;
            std::vector<int> best;
            std::uniform_int_distribution<int> pick(0, m - 1);
            for (int it = 0; it < 400; ++it) {
                std::vector<int> s{pick(rng), pick(rng), pick(rng)};
                if (s[0] == s[1] || s[1] == s[2] || s[0] == s[2]) continue;
                const cv::Matx33d R = kabsch(pm.ri, pm.rj, s);
                // Reject hypotheses far from the gyro estimate (repetitive textures).
                if (rotationAngle(R * Rgyro.t()) > 10 * CV_PI / 180) continue;
                std::vector<int> inl;
                for (int k = 0; k < m; ++k)
                    if (cv::norm(R * pm.ri[k] - pm.rj[k]) < thresh) inl.push_back(k);
                if (inl.size() > best.size()) best.swap(inl);
            }
            if (std::getenv("HDRI_DEBUG")) std::fprintf(stderr, "pair %d-%d: %zu inliers\n", i, j, best.size());
            if (best.size() < 25) continue;
            PairMatches out;
            out.i = i;
            out.j = j;
            for (int k : best) {
                out.ri.push_back(pm.ri[k]);
                out.rj.push_back(pm.rj[k]);
            }
            pairs[c] = std::move(out);
            keep[c] = 1;
        }
    });
    std::vector<PairMatches> good;
    for (size_t c = 0; c < pairs.size(); ++c)
        if (keep[c]) good.push_back(std::move(pairs[c]));
    stats.pairs = static_cast<int>(good.size());
    if (good.empty()) return stats;

    auto rms = [&]() {
        double s = 0;
        size_t cnt = 0;
        for (const auto& p : good)
            for (size_t k = 0; k < p.ri.size(); ++k) {
                s += cv::norm(cams[p.i].C * p.ri[k] - cams[p.j].C * p.rj[k], cv::NORM_L2SQR);
                ++cnt;
            }
        return std::sqrt(s / std::max<size_t>(cnt, 1)) * 180 / CV_PI;
    };
    stats.rmsBeforeDeg = rms();

    const std::vector<SpotCamera> initial = cams;
    const double huber = thresh * 2;
    const double priorSigma = 3.0 * CV_PI / 180;
    const double lambda = (thresh / priorSigma) * std::sqrt(20.0);

    for (int iter = 0; iter < 15; ++iter) {
        cv::Mat H = cv::Mat::zeros(3 * n, 3 * n, CV_64F), g = cv::Mat::zeros(3 * n, 1, CV_64F);
        auto addBlock = [&](int a, int b, const cv::Matx33d& M) {
            for (int r = 0; r < 3; ++r)
                for (int c = 0; c < 3; ++c) H.at<double>(3 * a + r, 3 * b + c) += M(r, c);
        };
        auto addGrad = [&](int a, const cv::Vec3d& v) {
            for (int r = 0; r < 3; ++r) g.at<double>(3 * a + r) += v[r];
        };
        for (const auto& p : good) {
            for (size_t k = 0; k < p.ri.size(); ++k) {
                const cv::Vec3d vi = cams[p.i].C * p.ri[k], vj = cams[p.j].C * p.rj[k];
                const cv::Vec3d e = vi - vj;
                const double en = cv::norm(e);
                const double w = en <= huber ? 1.0 : huber / en;
                const cv::Matx33d Ji = -skew(vi), Jj = skew(vj);
                addBlock(p.i, p.i, w * Ji.t() * Ji);
                addBlock(p.j, p.j, w * Jj.t() * Jj);
                addBlock(p.i, p.j, w * Ji.t() * Jj);
                addBlock(p.j, p.i, w * Jj.t() * Ji);
                addGrad(p.i, w * Ji.t() * e);
                addGrad(p.j, w * Jj.t() * e);
            }
        }
        for (int k = 0; k < n; ++k) {
            const cv::Vec3d phi = logMap(cams[k].C * initial[k].C.t());
            addBlock(k, k, lambda * lambda * cv::Matx33d::eye());
            addGrad(k, lambda * lambda * phi);
        }
        cv::Mat delta;
        if (!cv::solve(H, -g, delta, cv::DECOMP_CHOLESKY)) cv::solve(H, -g, delta, cv::DECOMP_SVD);
        double maxStep = 0;
        for (int k = 0; k < n; ++k) {
            const cv::Vec3d d(delta.at<double>(3 * k), delta.at<double>(3 * k + 1), delta.at<double>(3 * k + 2));
            cams[k].C = expMap(d) * cams[k].C;
            maxStep = std::max(maxStep, cv::norm(d));
        }
        if (maxStep < 1e-7) break;
    }
    stats.rmsAfterDeg = rms();
    return stats;
}

}  // namespace hdri
