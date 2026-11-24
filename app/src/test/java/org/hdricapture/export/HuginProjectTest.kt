// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.export

import org.hdricapture.geom.Quat
import org.hdricapture.geom.Vec3
import org.hdricapture.plan.AimSpotPlanner
import org.hdricapture.session.FrameInfo
import org.hdricapture.session.LensInfo
import org.hdricapture.session.RigMode
import org.hdricapture.session.Session
import org.hdricapture.session.SessionStore
import org.hdricapture.session.SpotCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HuginProjectTest {
    /** Level portrait pose facing north. */
    private val levelNorth = Quat.axisAngle(Vec3(1.0, 0.0, 0.0), Math.PI / 2)

    @Test fun levelPortraitShotHasZeroYawPitchAndQuarterTurnRoll() {
        val o = HuginProject.orientation(levelNorth, sensorOrientationDeg = 90, headingDeg = 0.0)
        assertEquals(0.0, o.yawDeg, 1e-6)
        assertEquals(0.0, o.pitchDeg, 1e-6)
        assertEquals(90.0, kotlin.math.abs(o.rollDeg), 1e-6)
    }

    @Test fun yawIsRelativeToHeadingAndPositiveToTheRight() {
        // Turn right (clockwise from above) by 30°: rotate about world Z by -30°.
        val pose = Quat.axisAngle(Vec3.UP, Math.toRadians(-30.0)) * levelNorth
        val o = HuginProject.orientation(pose, 90, headingDeg = 10.0)
        assertEquals(20.0, o.yawDeg, 1e-6)
    }

    private fun sampleSession() = Session(
        id = "test",
        createdAtMillis = 0,
        rigMode = RigMode.HANDHELD,
        headingDeg = 0.0,
        lens = LensInfo(
            cameraId = "0", focalLengthMm = 2.0, sensorWidthMm = 5.6, sensorHeightMm = 4.2,
            rawWidth = 4000, rawHeight = 3000, sensorOrientation = 90, cfa = "RGGB", whiteLevel = 1023,
            minExposureNs = 10_000, maxExposureNs = 1_000_000_000, baseIso = 50,
        ),
        spots = AimSpotPlanner.plan(75.0, 95.0),
        captures = listOf(
            SpotCapture(0, listOf(
                FrameInfo("s00_e00.dng", 1_000_000, 50, levelNorth.toList(), 1, listOf(64f, 64f, 64f, 64f)),
                FrameInfo("s00_e01.dng", 4_000_000, 50, levelNorth.toList(), 2, listOf(64f, 64f, 64f, 64f)),
            )),
        ),
    )

    @Test fun ptoHasOneImageLinePerFrameAndStacks() {
        val pto = HuginProject.generate(sampleSession())
        val images = pto.lines().filter { it.startsWith("i ") }
        assertEquals(2, images.size)
        assertTrue(images.all { " j0 " in it })
        assertTrue(images[0].contains("n\"s00_e00.tiff\""))
        assertTrue(images[1].contains("v=0"))
    }

    @Test fun sessionJsonRoundTrips() {
        val s = sampleSession()
        val json = SessionStore.json.encodeToString(Session.serializer(), s)
        assertEquals(s, SessionStore.json.decodeFromString(Session.serializer(), json))
    }
}
