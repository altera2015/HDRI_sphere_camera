// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.camera

import kotlin.math.pow
import kotlin.math.roundToLong

/** Statistics of one RAW frame, sampled sparsely. */
data class RawStats(
    /** Fraction of samples at or near the white level. */
    val clippedFraction: Double,
    /** Fraction of samples within 1% of the black level (deep shadow / noise). */
    val shadowFraction: Double,
)

/** Exposure limits for a bracket. Exposure is varied by time only, at base ISO. */
data class BracketLimits(
    val minExposureNs: Long,
    val maxExposureNs: Long,
    /** Upper bound for a single frame, e.g. ~1/15 s handheld, longer on a tripod. */
    val maxFrameNs: Long,
)

/** Pure logic for choosing bracket exposures. */
object BracketPlanner {
    const val CLIP_THRESHOLD = 1e-4
    const val SHADOW_THRESHOLD = 0.2
    const val MAX_EXTENSIONS = 4

    /**
     * Initial bracket centred on the metered exposure.
     * [meteredExposureNs]/[meteredIso] come from preview auto-exposure; they are converted to base ISO.
     */
    fun initial(
        meteredExposureNs: Long,
        meteredIso: Int,
        baseIso: Int,
        limits: BracketLimits,
        frames: Int = 5,
        stepEv: Double = 2.0,
    ): List<Long> {
        require(frames >= 1 && stepEv > 0)
        val centre = meteredExposureNs.toDouble() * meteredIso / baseIso
        val half = (frames - 1) / 2.0
        val upper = minOf(limits.maxExposureNs, limits.maxFrameNs)
        return (0 until frames)
            .map { (centre * 2.0.pow((it - half) * stepEv)).roundToLong().coerceIn(limits.minExposureNs, upper) }
            .distinct()
            .sorted()
    }

    /**
     * Given the frames captured so far (exposure → stats), returns the next exposure to add, or null.
     * Highlights take priority: they carry most of the light in an environment map.
     */
    fun nextExtension(captured: Map<Long, RawStats>, limits: BracketLimits, extensionsSoFar: Int, stepEv: Double = 2.0): Long? {
        if (captured.isEmpty() || extensionsSoFar >= MAX_EXTENSIONS) return null
        val factor = 2.0.pow(stepEv)
        val shortest = captured.keys.min()
        if (captured.getValue(shortest).clippedFraction > CLIP_THRESHOLD && shortest > limits.minExposureNs) {
            return maxOf(limits.minExposureNs, (shortest / factor).roundToLong())
        }
        val longest = captured.keys.max()
        val upper = minOf(limits.maxExposureNs, limits.maxFrameNs)
        val next = (longest * factor).roundToLong()
        if (captured.getValue(longest).shadowFraction > SHADOW_THRESHOLD && next <= upper) return next
        return null
    }

    /** True if even the shortest possible exposure still clips (sun, bare bulbs). */
    fun highlightsClipped(captured: Map<Long, RawStats>, limits: BracketLimits): Boolean {
        val shortest = captured.keys.minOrNull() ?: return false
        return shortest <= limits.minExposureNs && captured.getValue(shortest).clippedFraction > CLIP_THRESHOLD
    }
}
