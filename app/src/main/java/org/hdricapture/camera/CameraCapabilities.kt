// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size
import org.hdricapture.session.LensInfo
import kotlin.math.atan

/** A back-facing camera (or physical lens of a logical camera) usable for HDRI capture. */
data class LensOption(
    /** Camera ID to open. */
    val openId: String,
    /** Physical lens ID when streaming from a lens of a logical multi-camera. */
    val physicalId: String?,
    val characteristics: CameraCharacteristics,
    val rawSize: Size,
    /** FOV of the RAW frame, landscape (long side) and short side, degrees. */
    val fovLongDeg: Double,
    val fovShortDeg: Double,
    val focalLengthMm: Double,
) {
    val id: String get() = physicalId?.let { "$openId:$it" } ?: openId

    /** Portrait FOV, as the user holds the phone: short side across, long side vertical. */
    val portraitHFovDeg: Double get() = fovShortDeg
    val portraitVFovDeg: Double get() = fovLongDeg

    val label: String
        get() = when {
            fovLongDeg >= 90 -> "Ultra-wide"
            fovLongDeg >= 55 -> "Main"
            else -> "Tele"
        } + " · ${"%.0f".format(fovLongDeg)}°"

    fun toLensInfo(): LensInfo {
        val c = characteristics
        val physSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
        val exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)!!
        val cfa = when (c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
            else -> "UNKNOWN"
        }
        fun rational3x3(key: CameraCharacteristics.Key<android.hardware.camera2.params.ColorSpaceTransform>) =
            c.get(key)?.let { t -> (0 until 9).map { t.getElement(it % 3, it / 3).toDouble() } }
        return LensInfo(
            cameraId = openId,
            physicalCameraId = physicalId,
            focalLengthMm = focalLengthMm,
            aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.toDouble() ?: 1.8,
            sensorWidthMm = physSize.width.toDouble(),
            sensorHeightMm = physSize.height.toDouble(),
            rawWidth = rawSize.width,
            rawHeight = rawSize.height,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            intrinsics = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.map { it.toDouble() },
            distortion = c.get(CameraCharacteristics.LENS_DISTORTION)?.map { it.toDouble() },
            cfa = cfa,
            whiteLevel = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023,
            minExposureNs = exposureRange.lower,
            maxExposureNs = exposureRange.upper,
            baseIso = isoRange.lower,
            colorMatrix1 = rational3x3(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1),
            forwardMatrix1 = rational3x3(CameraCharacteristics.SENSOR_FORWARD_MATRIX1),
        )
    }
}

object CameraCapabilities {
    /** Back-facing lenses that support RAW + manual sensor control, widest first. */
    fun findLenses(manager: CameraManager): List<LensOption> {
        val result = mutableListOf<LensOption>()
        val seenPhysical = mutableSetOf<String>()
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()

            if (REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) {
                // Physical lenses (e.g. the ultra-wide) may only be reachable through the logical camera.
                for (pid in c.physicalCameraIds) {
                    val pc = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                    if (pid in manager.cameraIdList) continue // directly openable; handled on its own
                    toOption(id, pid, pc)?.let { result += it; seenPhysical += pid }
                }
            }
            if (REQUEST_AVAILABLE_CAPABILITIES_RAW in caps && REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps) {
                toOption(id, null, c)?.let { result += it }
            }
        }
        // Drop logical cameras that duplicate a physical lens' FOV (keep the direct one).
        return result
            .distinctBy { "%.1f".format(it.fovLongDeg) }
            .sortedByDescending { it.fovLongDeg }
    }

    private fun toOption(openId: String, physicalId: String?, c: CameraCharacteristics): LensOption? {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        if (REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps || REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in caps) return null
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val raw = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height } ?: return null
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()?.toDouble() ?: return null
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        // Physical size covers the full pixel array; scale to the active area.
        val wMm = if (pixelArray != null && active != null) phys.width * active.width() / pixelArray.width else phys.width
        val hMm = if (pixelArray != null && active != null) phys.height * active.height() / pixelArray.height else phys.height
        fun fov(sizeMm: Float) = Math.toDegrees(2 * atan(sizeMm / (2 * focal)))
        return LensOption(openId, physicalId, c, raw, fov(maxOf(wMm, hMm)), fov(minOf(wMm, hMm)), focal)
    }
}
