// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.camera

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hdricapture.geom.Quat
import org.hdricapture.session.FrameInfo
import org.hdricapture.session.SpotCapture
import java.io.File
import java.io.FileOutputStream

/** Captures and stores an adaptive exposure bracket for one aim spot. */
class BracketController(
    private val camera: CameraController,
    private val poseAt: (timestampNs: Long) -> Quat,
    private val currentPose: () -> Quat,
) {
    data class Settings(val frames: Int = 5, val stepEv: Double = 2.0, val maxFrameNs: Long = 66_000_000)

    suspend fun captureSpot(spotIndex: Int, sessionDir: File, settings: Settings): SpotCapture {
        val lens = camera.lens
        val info = lens.toLensInfo()
        val limits = BracketLimits(info.minExposureNs, info.maxExposureNs, minOf(settings.maxFrameNs, info.maxExposureNs))
        val metering = camera.metering.value ?: Metering(10_000_000, info.baseIso)

        val frames = mutableListOf<FrameInfo>()
        val stats = mutableMapOf<Long, RawStats>()
        var shading: List<Float>? = null

        suspend fun shoot(exposures: List<Long>) {
            camera.captureBurst(exposures, info.baseIso) { cap ->
                try {
                    val black = blackLevel(cap.result, lens)
                    stats[cap.exposureNs] = withContext(Dispatchers.Default) {
                        RawAnalyzer.analyze(cap.image, black.average().toFloat(), info.whiteLevel)
                    }
                    val name = "s%02d_e%02d.dng".format(spotIndex, frames.size)
                    withContext(Dispatchers.IO) {
                        FileOutputStream(File(sessionDir, name)).use { out ->
                            DngCreator(lens.characteristics, cap.result).use { it.writeImage(out, cap.image) }
                        }
                    }
                    if (shading == null) shading = lensShading(cap.result)
                    val ts = cap.result.get(CaptureResult.SENSOR_TIMESTAMP) ?: cap.image.timestamp
                    frames += FrameInfo(
                        file = name,
                        exposureTimeNs = cap.result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: cap.exposureNs,
                        iso = cap.result.get(CaptureResult.SENSOR_SENSITIVITY) ?: cap.iso,
                        pose = (if (camera.timestampsAreRealtime) poseAt(ts) else currentPose()).toList(),
                        timestampNs = ts,
                        blackLevel = black,
                        neutral = cap.result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map { it.toDouble() },
                        colorTransform = cap.result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { t ->
                            (0 until 9).map { t.getElement(it % 3, it / 3).toDouble() }
                        },
                    )
                } finally {
                    cap.image.close()
                }
            }
        }

        shoot(BracketPlanner.initial(metering.exposureNs, metering.iso, info.baseIso, limits, settings.frames, settings.stepEv))
        var extensions = 0
        while (true) {
            val next = BracketPlanner.nextExtension(stats, limits, extensions, settings.stepEv) ?: break
            if (next in stats) break
            shoot(listOf(next))
            extensions++
        }
        return SpotCapture(
            spotIndex = spotIndex,
            frames = frames.sortedBy { it.exposureTimeNs },
            highlightsClipped = BracketPlanner.highlightsClipped(stats, limits),
            lensShadingMap = shading,
        )
    }

    private fun blackLevel(result: CaptureResult, lens: LensOption): List<Float> =
        result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.toList()
            ?: lens.characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                ?.let { p -> listOf(p.getOffsetForIndex(0, 0), p.getOffsetForIndex(1, 0), p.getOffsetForIndex(0, 1), p.getOffsetForIndex(1, 1)).map { it.toFloat() } }
            ?: listOf(64f, 64f, 64f, 64f)

    private fun lensShading(result: CaptureResult): List<Float>? {
        val map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) ?: return null
        val gains = FloatArray(map.gainFactorCount)
        map.copyGainFactors(gains, 0)
        return listOf(map.rowCount.toFloat(), map.columnCount.toFloat()) + gains.toList()
    }
}
