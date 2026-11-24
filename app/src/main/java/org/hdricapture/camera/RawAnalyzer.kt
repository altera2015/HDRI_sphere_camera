// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.camera

import android.media.Image
import java.nio.ByteOrder

object RawAnalyzer {
    /**
     * Samples a RAW_SENSOR image (16-bit, little-endian) on a sparse grid. The step is even in both
     * directions offset by one so all four CFA channels get sampled.
     */
    fun analyze(image: Image, blackLevel: Float, whiteLevel: Int, step: Int = 8): RawStats {
        val plane = image.planes[0]
        val buf = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride // 2 for RAW_SENSOR
        val clipLevel = whiteLevel * 0.98f
        val shadowLevel = blackLevel + (whiteLevel - blackLevel) * 0.01f
        var n = 0L
        var clipped = 0L
        var shadow = 0L
        var y = 0
        while (y < image.height) {
            var x = (y / step) and 1 // alternate column parity per sampled row
            val rowBase = y * rowStride
            while (x < image.width) {
                val v = buf.getShort(rowBase + x * pixelStride).toInt() and 0xffff
                if (v >= clipLevel) clipped++
                if (v <= shadowLevel) shadow++
                n++
                x += step + 1
            }
            y += step + 1
        }
        if (n == 0L) return RawStats(0.0, 0.0)
        return RawStats(clipped.toDouble() / n, shadow.toDouble() / n)
    }
}
