// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.plan

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.tan

class LensSpotCountTest {
    /** Spot count for a 4:3 sensor given its long-side FOV, held in portrait. */
    private fun count(longFov: Double): Int {
        val short = Math.toDegrees(2 * atan(tan(Math.toRadians(longFov / 2)) * 3 / 4))
        return AimSpotPlanner.plan(short, longFov).size.also { println("long FOV $longFov° -> $it spots") }
    }

    @Test fun pixelLensesAgainstLimit() {
        assertTrue(count(104.0) <= 30) // ultra-wide
        assertTrue(count(70.0) <= 30) // main
        assertTrue(count(19.0) > 30) // telephoto
    }
}
