// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.session

import kotlinx.serialization.Serializable
import org.hdricapture.plan.AimSpot

/**
 * Everything the native pipeline (and desktop tools) need to reconstruct the environment map.
 * Persisted as `session.json` next to the DNG files.
 */
@Serializable
data class Session(
    val formatVersion: Int = 1,
    val id: String,
    val createdAtMillis: Long,
    val app: String = "HDRI Sphere",
    val rigMode: RigMode,
    /** Compass heading (degrees) of the first spot; spot yaws are relative to it. */
    val headingDeg: Double,
    val lens: LensInfo,
    val spots: List<AimSpot>,
    val captures: List<SpotCapture> = emptyList(),
)

@Serializable
enum class RigMode { HANDHELD, TRIPOD }

@Serializable
data class LensInfo(
    val cameraId: String,
    val physicalCameraId: String? = null,
    val focalLengthMm: Double,
    val aperture: Double = 1.8,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    /** RAW image size in pixels (landscape, sensor orientation). */
    val rawWidth: Int,
    val rawHeight: Int,
    /** Clockwise rotation from sensor to device-natural (portrait) orientation. */
    val sensorOrientation: Int,
    /** [fx, fy, cx, cy, s] in active-array pixels, if the device reports it. */
    val intrinsics: List<Double>? = null,
    /** Camera2 LENS_DISTORTION [k1, k2, k3, p1, p2], if reported. */
    val distortion: List<Double>? = null,
    /** CFA arrangement name: RGGB, GRBG, GBRG, BGGR. */
    val cfa: String,
    val whiteLevel: Int,
    val minExposureNs: Long,
    val maxExposureNs: Long,
    val baseIso: Int,
    /** Color matrices from the static characteristics (row-major 3x3), used if a frame lacks them. */
    val colorMatrix1: List<Double>? = null,
    val forwardMatrix1: List<Double>? = null,
)

@Serializable
data class SpotCapture(
    val spotIndex: Int,
    val frames: List<FrameInfo>,
    /** Highlights still clipped at the sensor's shortest exposure (e.g. the sun). */
    val highlightsClipped: Boolean = false,
    /** Lens shading gain map: [rows, cols, then rows*cols*4 floats in R, Geven, Godd, B order]. */
    val lensShadingMap: List<Float>? = null,
)

@Serializable
data class FrameInfo(
    /** DNG file name relative to the session directory. */
    val file: String,
    val exposureTimeNs: Long,
    val iso: Int,
    /** Device→world quaternion [w, x, y, z] at the capture timestamp. */
    val pose: List<Double>,
    val timestampNs: Long,
    /** Per-CFA-channel black level for this frame. */
    val blackLevel: List<Float>,
    /** AS_SHOT_NEUTRAL (camera-space white), R G B. */
    val neutral: List<Double>? = null,
    /** COLOR_CORRECTION_TRANSFORM: camera RGB → linear sRGB (row-major 3x3). */
    val colorTransform: List<Double>? = null,
) {
    /** Exposure relative to 1 s at ISO 100. */
    val relativeExposure: Double get() = exposureTimeNs * 1e-9 * iso / 100.0
}
