// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.plan

import kotlinx.serialization.Serializable
import org.hdricapture.geom.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** A direction on the sphere to capture. Yaw is relative to the session's start heading. */
@Serializable
data class AimSpot(val index: Int, val yawDeg: Double, val pitchDeg: Double) {
    val isPole: Boolean get() = abs(pitchDeg) > 89.0
    fun direction(headingDeg: Double): Vec3 = Vec3.fromYawPitch(headingDeg + yawDeg, pitchDeg)
}

/**
 * Lays out aim spots so that frames with the given field of view cover the whole sphere.
 *
 * FOVs are for the image as the user holds the phone (portrait): [hFovDeg] across, [vFovDeg] up/down.
 * [overlap] is the fraction of each frame shared with its neighbours (needed for feature matching
 * and seam blending).
 */
object AimSpotPlanner {
    fun plan(hFovDeg: Double, vFovDeg: Double, overlap: Double = 0.3): List<AimSpot> {
        require(hFovDeg in 10.0..179.0 && vFovDeg in 10.0..179.0) { "FOV out of range" }
        require(overlap in 0.0..0.8)
        val hStep = hFovDeg * (1 - overlap)
        val vStep = vFovDeg * (1 - overlap)
        // A pole shot covers roughly a disc whose radius is half the smaller FOV.
        val capRadius = min(hFovDeg, vFovDeg) / 2 * (1 - overlap / 2)
        val bandLow = -90.0 + capRadius
        val bandHigh = 90.0 - capRadius
        val rows = max(1, ceil((bandHigh - bandLow) / vStep).toInt())
        val rowHeight = (bandHigh - bandLow) / rows

        // Capture order: rows at/above the horizon going up, zenith, rows below going down, nadir.
        // Horizon first gives the best-matching reference frames; nadir last so the user can step aside.
        val centres = (0 until rows).map { bandLow + (it + 0.5) * rowHeight }
        val upper = centres.filter { it >= -1e-6 }.sorted()
        val lower = centres.filter { it < -1e-6 }.sortedDescending()

        val spots = mutableListOf<AimSpot>()
        fun addRow(centre: Double, stagger: Boolean) {
            val lo = centre - rowHeight / 2
            val hi = centre + rowHeight / 2
            // The latitude nearest the equator in this row needs the most yaw coverage.
            val worstLat = if (lo <= 0 && hi >= 0) 0.0 else min(abs(lo), abs(hi))
            val count = max(1, ceil(360.0 * cos(Math.toRadians(worstLat)) / hStep).toInt())
            val offset = if (stagger) 180.0 / count else 0.0
            for (i in 0 until count) spots += AimSpot(0, (offset + i * 360.0 / count) % 360.0, centre)
        }
        var rowNo = 0
        upper.forEach { addRow(it, rowNo++ % 2 == 1) }
        spots += AimSpot(0, 0.0, 90.0)
        lower.forEach { addRow(it, rowNo++ % 2 == 1) }
        spots += AimSpot(0, 0.0, -90.0)
        return spots.mapIndexed { i, s -> s.copy(index = i) }
    }
}
