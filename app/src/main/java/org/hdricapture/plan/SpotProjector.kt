// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.plan

import org.hdricapture.geom.CameraFrame
import org.hdricapture.geom.Quat
import org.hdricapture.geom.Vec3
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.tan

/** Pinhole projection of world directions onto the preview, for the AR overlay. */
class SpotProjector(
    private val viewWidth: Float,
    private val viewHeight: Float,
    /** Horizontal FOV of the preview as displayed (portrait), degrees. */
    hFovDeg: Double,
) {
    private val focalPx = (viewWidth / 2) / tan(Math.toRadians(hFovDeg / 2))

    data class Projection(val x: Float, val y: Float, val inFront: Boolean)

    /** Screen position of [dir] for a device at [pose]. Behind the camera, the point is mirrored to the edge. */
    fun project(pose: Quat, dir: Vec3): Projection {
        val cam = CameraFrame.fromDevicePose(pose)
        val z = dir dot cam.forward
        val x = dir dot cam.right
        val y = dir dot cam.up
        if (z <= 1e-3) {
            // Behind: push far off-screen in the right direction so arrows still point sensibly.
            val big = 1e4f
            return Projection(viewWidth / 2 + (x * big).toFloat(), viewHeight / 2 - (y * big).toFloat(), false)
        }
        return Projection(
            (viewWidth / 2 + focalPx * x / z).toFloat(),
            (viewHeight / 2 - focalPx * y / z).toFloat(),
            true,
        )
    }

    companion object {
        /** Angle in degrees between the camera's optical axis and [dir]. */
        fun angleToDeg(pose: Quat, dir: Vec3): Double {
            val f = CameraFrame.fromDevicePose(pose).forward
            return Math.toDegrees(acos((f dot dir.normalized()).coerceIn(-1.0, 1.0)))
        }

        /**
         * Roll error in degrees: angle between the camera's up vector and the world-up direction
         * projected onto the image plane. 0 = level portrait.
         */
        fun rollDeg(pose: Quat): Double {
            val cam = CameraFrame.fromDevicePose(pose)
            val upX = Vec3.UP dot cam.right
            val upY = Vec3.UP dot cam.up
            return Math.toDegrees(atan2(upX, upY))
        }
    }
}
