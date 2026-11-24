// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.geom

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coordinate conventions (same as Android's rotation-vector sensor):
 * - World: X = east, Y = north (magnetic), Z = up.
 * - Device: X = right, Y = top of the screen in portrait, Z = out of the screen.
 * - The back camera looks along device -Z; in portrait its "up" is device +Y.
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(this dot this)
    fun normalized(): Vec3 = length().let { if (it > 0) this * (1.0 / it) else this }

    companion object {
        val UP = Vec3(0.0, 0.0, 1.0)

        /** Unit direction from yaw (degrees clockwise from north, as a compass) and pitch (degrees above horizon). */
        fun fromYawPitch(yawDeg: Double, pitchDeg: Double): Vec3 {
            val y = Math.toRadians(yawDeg)
            val p = Math.toRadians(pitchDeg)
            return Vec3(cos(p) * sin(y), cos(p) * cos(y), sin(p))
        }
    }

    /** Inverse of [fromYawPitch]: returns (yawDeg in [0,360), pitchDeg). */
    fun toYawPitch(): Pair<Double, Double> {
        val n = normalized()
        val yaw = Math.toDegrees(atan2(n.x, n.y)).let { if (it < 0) it + 360.0 else it }
        return yaw to Math.toDegrees(asin(n.z.coerceIn(-1.0, 1.0)))
    }
}

/** Unit quaternion (w, x, y, z) rotating device-frame vectors into the world frame. */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    fun rotate(v: Vec3): Vec3 {
        // v' = q v q*, expanded.
        val u = Vec3(x, y, z)
        val t = (u cross v) * 2.0
        return v + t * w + (u cross t)
    }

    fun conjugate() = Quat(w, -x, -y, -z)

    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n > 0) Quat(w / n, x / n, y / n, z / n) else IDENTITY
    }

    fun toList(): List<Double> = listOf(w, x, y, z)

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        fun fromList(l: List<Double>) = Quat(l[0], l[1], l[2], l[3])

        fun axisAngle(axis: Vec3, angleRad: Double): Quat {
            val a = axis.normalized()
            val s = sin(angleRad / 2)
            return Quat(cos(angleRad / 2), a.x * s, a.y * s, a.z * s)
        }

        /** Builds the device→world rotation from the 3x3 row-major matrix of SensorManager.getRotationMatrix. */
        fun fromRotationMatrix(m: FloatArray): Quat {
            val m00 = m[0].toDouble(); val m01 = m[1].toDouble(); val m02 = m[2].toDouble()
            val m10 = m[3].toDouble(); val m11 = m[4].toDouble(); val m12 = m[5].toDouble()
            val m20 = m[6].toDouble(); val m21 = m[7].toDouble(); val m22 = m[8].toDouble()
            val tr = m00 + m11 + m22
            return when {
                tr > 0 -> {
                    val s = sqrt(tr + 1.0) * 2
                    Quat(0.25 * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
                }
                m00 > m11 && m00 > m22 -> {
                    val s = sqrt(1.0 + m00 - m11 - m22) * 2
                    Quat((m21 - m12) / s, 0.25 * s, (m01 + m10) / s, (m02 + m20) / s)
                }
                m11 > m22 -> {
                    val s = sqrt(1.0 + m11 - m00 - m22) * 2
                    Quat((m02 - m20) / s, (m01 + m10) / s, 0.25 * s, (m12 + m21) / s)
                }
                else -> {
                    val s = sqrt(1.0 + m22 - m00 - m11) * 2
                    Quat((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25 * s)
                }
            }.normalized()
        }
    }
}

/** Camera axes in the world frame for a given device pose (portrait, back camera). */
data class CameraFrame(val forward: Vec3, val up: Vec3, val right: Vec3) {
    companion object {
        fun fromDevicePose(q: Quat) = CameraFrame(
            forward = q.rotate(Vec3(0.0, 0.0, -1.0)),
            up = q.rotate(Vec3(0.0, 1.0, 0.0)),
            right = q.rotate(Vec3(1.0, 0.0, 0.0)),
        )

        /** Ideal level camera looking along [forward]; at the poles "up" falls back to [poleUp]. */
        fun looking(forward: Vec3, poleUp: Vec3 = Vec3(0.0, 1.0, 0.0)): CameraFrame {
            val f = forward.normalized()
            var right = f cross Vec3.UP
            if (right.length() < 1e-6) right = f cross poleUp
            right = right.normalized()
            return CameraFrame(f, (right cross f).normalized(), right)
        }
    }
}
