// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.hdricapture.geom.Quat
import kotlin.math.sqrt

/**
 * Tracks the device pose (rotation vector, gyro-fused) and angular speed (gyroscope).
 * Keeps a short history so capture timestamps can be matched to the pose at exposure time.
 */
class OrientationTracker(private val sensorManager: SensorManager) : SensorEventListener {

    data class State(val pose: Quat = Quat.IDENTITY, val angularSpeedDegS: Double = 0.0, val timestampNs: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val rotMatrix = FloatArray(9)
    private val history = ArrayDeque<Pair<Long, Quat>>()

    fun start() {
        // GAME_ROTATION_VECTOR is not affected by magnetic disturbances, which matters indoors.
        // Absolute heading is not needed: all yaws are relative to the first spot.
        val rv = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensorManager.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = sensorManager.unregisterListener(this)

    /** Pose closest to [timestampNs] (SystemClock.elapsedRealtimeNanos base, same as sensor events). */
    @Synchronized
    fun poseAt(timestampNs: Long): Quat =
        history.minByOrNull { kotlin.math.abs(it.first - timestampNs) }?.second ?: _state.value.pose

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                val q = Quat.fromRotationMatrix(rotMatrix)
                synchronized(this) {
                    history.addLast(event.timestamp to q)
                    while (history.size > 200) history.removeFirst()
                }
                _state.value = _state.value.copy(pose = q, timestampNs = event.timestamp)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                val speed = Math.toDegrees(sqrt((x * x + y * y + z * z).toDouble()))
                // Light smoothing so a single spike doesn't block or allow triggering.
                val prev = _state.value.angularSpeedDegS
                _state.value = _state.value.copy(angularSpeedDegS = prev * 0.7 + speed * 0.3)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
