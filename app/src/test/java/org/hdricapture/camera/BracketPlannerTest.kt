// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BracketPlannerTest {
    private val limits = BracketLimits(minExposureNs = 20_000, maxExposureNs = 1_000_000_000, maxFrameNs = 66_000_000)

    @Test fun initialIsCentredAndConvertedToBaseIso() {
        // Metered 1/100 s at ISO 200 == 1/50 s at ISO 100.
        val e = BracketPlanner.initial(10_000_000, 200, 100, limits, frames = 5, stepEv = 2.0)
        assertEquals(listOf(1_250_000L, 5_000_000L, 20_000_000L, 66_000_000L), e) // longest clamped, 80ms > 66ms
    }

    @Test fun extendsShorterWhileClipped() {
        val captured = mapOf(1_000_000L to RawStats(0.01, 0.0), 4_000_000L to RawStats(0.2, 0.0))
        assertEquals(250_000L, BracketPlanner.nextExtension(captured, limits, 0))
    }

    @Test fun stopsAtSensorMinimumAndFlagsClipping() {
        val captured = mapOf(20_000L to RawStats(0.001, 0.0))
        assertNull(BracketPlanner.nextExtension(captured, limits, 0))
        assertTrue(BracketPlanner.highlightsClipped(captured, limits))
    }

    @Test fun clampsExtensionToMinimum() {
        val captured = mapOf(50_000L to RawStats(0.01, 0.0))
        assertEquals(20_000L, BracketPlanner.nextExtension(captured, limits, 0))
    }

    @Test fun extendsLongerForShadowsWithinFrameLimit() {
        val captured = mapOf(1_000_000L to RawStats(0.0, 0.0), 10_000_000L to RawStats(0.0, 0.5))
        assertEquals(40_000_000L, BracketPlanner.nextExtension(captured, limits, 0))
        val tooLong = mapOf(20_000_000L to RawStats(0.0, 0.5))
        assertNull(BracketPlanner.nextExtension(tooLong, limits, 0))
    }

    @Test fun respectsExtensionBudget() {
        val captured = mapOf(1_000_000L to RawStats(0.5, 0.0))
        assertNull(BracketPlanner.nextExtension(captured, limits, BracketPlanner.MAX_EXTENSIONS))
        assertFalse(BracketPlanner.highlightsClipped(captured, limits))
    }
}
