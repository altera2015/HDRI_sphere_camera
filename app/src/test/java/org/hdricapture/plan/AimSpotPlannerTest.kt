// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.plan

import org.hdricapture.geom.CameraFrame
import org.hdricapture.geom.Quat
import org.hdricapture.geom.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

class AimSpotPlannerTest {

    /** True if [dir] falls inside the frame of a level camera aimed at [spot]. */
    private fun covers(spot: AimSpot, dir: Vec3, hFov: Double, vFov: Double): Boolean {
        val cam = CameraFrame.looking(spot.direction(0.0))
        val z = dir dot cam.forward
        if (z <= 0) return false
        val ax = Math.toDegrees(atan2(dir dot cam.right, z))
        val ay = Math.toDegrees(atan2(dir dot cam.up, z))
        if (spot.isPole) {
            // Roll is arbitrary at the poles: only the inscribed disc is guaranteed.
            val r = Math.toDegrees(kotlin.math.acos(z.coerceIn(-1.0, 1.0)))
            return r <= min(hFov, vFov) / 2
        }
        return abs(ax) <= hFov / 2 && abs(ay) <= vFov / 2
    }

    private fun randomDir(rng: Random): Vec3 {
        while (true) {
            val v = Vec3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian())
            if (v.length() > 1e-6) return v.normalized()
        }
    }

    private fun assertFullCoverage(hFov: Double, vFov: Double) {
        val spots = AimSpotPlanner.plan(hFov, vFov)
        val rng = Random(42)
        repeat(20_000) {
            val d = randomDir(rng)
            assertTrue(
                "uncovered dir ${d.toYawPitch()} with fov $hFov x $vFov (${spots.size} spots)",
                spots.any { covers(it, d, hFov, vFov) },
            )
        }
    }

    @Test fun ultraWideCoversSphere() = assertFullCoverage(75.0, 95.0)

    @Test fun mainLensCoversSphere() = assertFullCoverage(52.0, 66.0)

    @Test fun narrowLensCoversSphere() = assertFullCoverage(38.0, 50.0)

    @Test fun spotCountsAreReasonable() {
        val uw = AimSpotPlanner.plan(75.0, 95.0).size
        val main = AimSpotPlanner.plan(52.0, 66.0).size
        assertTrue("ultra-wide spots: $uw", uw in 8..24)
        assertTrue("main spots: $main", main in 20..50)
    }

    @Test fun orderStartsAtHorizonAndEndsWithNadir() {
        val spots = AimSpotPlanner.plan(52.0, 66.0)
        assertEquals(-90.0, spots.last().pitchDeg, 1e-9)
        assertTrue(abs(spots.first().pitchDeg) < 40)
        assertEquals(spots.indices.toList(), spots.map { it.index })
    }
}

class SpotProjectorTest {
    @Test fun centreSpotProjectsToViewCentre() {
        // Device lying in portrait, camera facing north, level.
        // Device -Z must map to world +Y (north) and device +Y to world +Z (up): rotate +90° about X.
        val pose = Quat.axisAngle(Vec3(1.0, 0.0, 0.0), Math.PI / 2)
        val north = Vec3.fromYawPitch(0.0, 0.0)
        val p = SpotProjector(1000f, 2000f, 60.0).project(pose, north)
        assertTrue(p.inFront)
        assertEquals(500f, p.x, 1e-3f)
        assertEquals(1000f, p.y, 1e-3f)
        assertEquals(0.0, SpotProjector.angleToDeg(pose, north), 1e-6)
        assertEquals(0.0, SpotProjector.rollDeg(pose), 1e-6)
    }

    @Test fun spotToTheRightAndAboveProjectsAccordingly() {
        val pose = Quat.axisAngle(Vec3(1.0, 0.0, 0.0), Math.PI / 2)
        val p = SpotProjector(1000f, 2000f, 60.0).project(pose, Vec3.fromYawPitch(10.0, 5.0))
        assertTrue(p.x > 500f && p.y < 1000f)
    }

    @Test fun yawPitchRoundTrip() {
        for (yaw in listOf(0.0, 45.0, 179.0, 270.0)) for (pitch in listOf(-60.0, 0.0, 30.0)) {
            val (y, p) = Vec3.fromYawPitch(yaw, pitch).toYawPitch()
            assertEquals(yaw, y, 1e-9)
            assertEquals(pitch, p, 1e-9)
        }
    }
}
