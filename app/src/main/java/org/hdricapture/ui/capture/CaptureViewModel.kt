// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.ui.capture

import android.app.Application
import android.content.Context
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.hdricapture.camera.BracketController
import org.hdricapture.camera.CameraCapabilities
import org.hdricapture.camera.CameraController
import org.hdricapture.camera.LensOption
import org.hdricapture.geom.CameraFrame
import org.hdricapture.plan.AimSpot
import org.hdricapture.plan.AimSpotPlanner
import org.hdricapture.plan.SpotProjector
import org.hdricapture.sensors.OrientationTracker
import org.hdricapture.session.RigMode
import org.hdricapture.session.Session
import org.hdricapture.session.SessionStore
import org.hdricapture.settings.AppSettings
import kotlin.math.abs

enum class Phase { STARTING, AIMING, CAPTURING, FINISHED, ERROR }

enum class Hint { AIM, LEVEL, HOLD_STILL, CAPTURING, STEP_ASIDE }

data class CaptureUiState(
    val phase: Phase = Phase.STARTING,
    val lenses: List<LensOption> = emptyList(),
    val lens: LensOption? = null,
    val rigMode: RigMode = RigMode.HANDHELD,
    val spots: List<AimSpot> = emptyList(),
    val done: Set<Int> = emptySet(),
    val targetIndex: Int? = null,
    val headingDeg: Double = 0.0,
    val hint: Hint = Hint.AIM,
    /** 0..1 progress of the hold-still timer. */
    val holdProgress: Float = 0f,
    val message: String? = null,
    val sessionId: String? = null,
    /** True when roughly aimed at a spot for a while but auto-trigger hasn't fired: offer a manual capture. */
    val manualAvailable: Boolean = false,
)

class CaptureViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        /** Lenses needing more aim spots than this (telephotos) are not offered. */
        const val MAX_SPOTS = 30
    }

    private val settings = AppSettings(app)
    private val store = SessionStore(app)
    private val cameraManager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val tracker = OrientationTracker(app.getSystemService(Context.SENSOR_SERVICE) as SensorManager)

    private val _ui = MutableStateFlow(CaptureUiState())
    val ui: StateFlow<CaptureUiState> = _ui

    private var camera: CameraController? = null
    private var session: Session? = null
    private var triggerJob: Job? = null
    private var stableSinceMs = 0L
    private var nearSinceMs = 0L
    private var nearTarget = -1

    init {
        // Only offer lenses that can cover the sphere in a reasonable number of shots.
        val lenses = runCatching { CameraCapabilities.findLenses(cameraManager) }.getOrDefault(emptyList())
            .filter { spotCount(it) <= MAX_SPOTS }
        if (lenses.isEmpty()) {
            _ui.value = CaptureUiState(phase = Phase.ERROR, message = "This phone has no back camera with RAW and manual exposure control.")
        } else {
            val lens = lenses.firstOrNull { it.id == settings.lensId } ?: lenses.first()
            _ui.value = CaptureUiState(lenses = lenses, lens = lens, rigMode = settings.rigMode)
        }
    }

    /** Preview buffer size for the selected lens (the UI sizes the TextureView buffer with it). */
    fun previewSize() = _ui.value.lens?.let { CameraController.previewSize(it) }

    fun onSurfaceReady(surface: Surface) {
        val lens = _ui.value.lens ?: return
        if (camera != null) return
        val cam = CameraController(cameraManager, lens)
        camera = cam
        tracker.start()
        viewModelScope.launch {
            try {
                cam.open(surface)
                startSession(lens)
            } catch (e: Exception) {
                _ui.update { it.copy(phase = Phase.ERROR, message = e.message ?: e.toString()) }
            }
        }
    }

    fun onSurfaceDestroyed() {
        triggerJob?.cancel()
        tracker.stop()
        camera?.close()
        camera = null
        if (_ui.value.phase == Phase.AIMING) _ui.update { it.copy(phase = Phase.STARTING) }
    }

    /** Switches lens. Mid-capture this starts a new session; spots taken so far stay as an incomplete one. */
    fun selectLens(lens: LensOption) {
        if (lens.id == _ui.value.lens?.id) return
        if (_ui.value.done.isNotEmpty()) session = null
        settings.lensId = lens.id
        _ui.update { it.copy(lens = lens) }
    }



    fun setRigMode(mode: RigMode) {
        settings.rigMode = mode
        _ui.update { it.copy(rigMode = mode) }
    }

    private fun startSession(lens: LensOption) {
        val existing = session
        if (existing == null || existing.lens.cameraId != lens.openId || existing.lens.physicalCameraId != lens.physicalId) {
            val spots = AimSpotPlanner.plan(lens.portraitHFovDeg, lens.portraitVFovDeg, settings.overlap.toDouble())
            // Spot 0 lies straight ahead of wherever the user is pointing now.
            val heading = CameraFrame.fromDevicePose(tracker.state.value.pose).forward.toYawPitch().first
            session = Session(
                id = store.newSessionId(),
                createdAtMillis = System.currentTimeMillis(),
                rigMode = settings.rigMode,
                headingDeg = heading,
                lens = lens.toLensInfo(),
                spots = spots,
            )
            _ui.update { it.copy(spots = spots, done = emptySet(), headingDeg = heading, sessionId = null) }
        }
        _ui.update { it.copy(phase = Phase.AIMING) }
        triggerJob?.cancel()
        triggerJob = viewModelScope.launch { tracker.state.collect { onPose(it) } }
    }

    /** Re-centres the spot grid on the current direction. Only before the first capture. */
    fun recentre() {
        val s = session ?: return
        if (s.captures.isNotEmpty()) return
        val heading = CameraFrame.fromDevicePose(tracker.state.value.pose).forward.toYawPitch().first
        session = s.copy(headingDeg = heading)
        _ui.update { it.copy(headingDeg = heading) }
    }

    private fun onPose(state: OrientationTracker.State) {
        val ui = _ui.value
        if (ui.phase != Phase.AIMING) return
        val pending = ui.spots.filter { it.index !in ui.done }
        if (pending.isEmpty()) return
        // Target: nearest pending spot, but keep the nadir for last.
        val candidates = pending.filter { it.pitchDeg > -89.9 }.ifEmpty { pending }
        val target = candidates.minBy { SpotProjector.angleToDeg(state.pose, it.direction(ui.headingDeg)) }
        val angle = SpotProjector.angleToDeg(state.pose, target.direction(ui.headingDeg))
        val roll = SpotProjector.rollDeg(state.pose)

        val tripod = ui.rigMode == RigMode.TRIPOD
        val aimTol = if (tripod) 1.5 else 3.0
        val speedTol = if (tripod) 1.0 else 4.0
        val rollTol = if (tripod) 2.0 else 6.0

        val now = SystemClock.elapsedRealtime()
        val hint = when {
            angle > aimTol -> if (target.pitchDeg < -89.9 && !tripod) Hint.STEP_ASIDE else Hint.AIM
            !target.isPole && abs(roll) > rollTol -> Hint.LEVEL
            else -> Hint.HOLD_STILL
        }
        val steady = hint == Hint.HOLD_STILL && state.angularSpeedDegS < speedTol
        if (!steady) stableSinceMs = 0L else if (stableSinceMs == 0L) stableSinceMs = now
        val held = if (stableSinceMs == 0L) 0L else now - stableSinceMs
        val holdMs = if (tripod) 500L else 350L
        // Roughly aimed (within 10°) at the same spot for 3 s without triggering: offer manual capture.
        if (angle < 10 && target.index == nearTarget) {
            if (nearSinceMs == 0L) nearSinceMs = now
        } else {
            nearSinceMs = if (angle < 10) now else 0L
            nearTarget = target.index
        }
        val manual = nearSinceMs != 0L && now - nearSinceMs > 3000
        _ui.update {
            it.copy(
                targetIndex = target.index,
                hint = hint,
                holdProgress = (held.toFloat() / holdMs).coerceAtMost(1f),
                manualAvailable = manual,
            )
        }
        if (steady && held >= holdMs) captureSpot(target.index)
    }

    /** Manual shutter: captures the nearest pending spot if the camera is roughly aimed at it. */
    fun shutter() {
        val ui = _ui.value
        val idx = ui.targetIndex ?: return
        val spot = ui.spots[idx]
        if (SpotProjector.angleToDeg(tracker.state.value.pose, spot.direction(ui.headingDeg)) < 10) captureSpot(idx)
    }

    private fun captureSpot(index: Int) {
        val cam = camera ?: return
        val s = session ?: return
        stableSinceMs = 0L
        nearSinceMs = 0L
        _ui.update { it.copy(phase = Phase.CAPTURING, hint = Hint.CAPTURING, manualAvailable = false) }
        viewModelScope.launch {
            try {
                val tripod = s.rigMode == RigMode.TRIPOD
                val bracket = BracketController(cam, tracker::poseAt) { tracker.state.value.pose }
                val capture = bracket.captureSpot(
                    index,
                    store.dir(s.id),
                    BracketController.Settings(
                        frames = settings.bracketFrames,
                        stepEv = settings.bracketStepEv.toDouble(),
                        maxFrameNs = if (tripod) 1_000_000_000 else 66_000_000,
                    ),
                )
                val updated = (session ?: s).let { cur ->
                    cur.copy(rigMode = settings.rigMode, captures = cur.captures.filter { it.spotIndex != index } + capture)
                }
                session = updated
                store.save(updated)
                val done = _ui.value.done + index
                val finished = done.size == _ui.value.spots.size
                _ui.update {
                    it.copy(
                        done = done,
                        phase = if (finished) Phase.FINISHED else Phase.AIMING,
                        sessionId = updated.id,
                        message = if (capture.highlightsClipped) "Very bright light source: highlights clipped at the shortest exposure." else null,
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("HdriCapture", "capture failed", e)
                _ui.update { it.copy(phase = Phase.AIMING, message = "Capture failed: ${e.message}") }
            }
        }
    }

    /**
     * Ends the session early (e.g. skipping the nadir): saves it to Sessions and starts a fresh one at
     * 0 / N spots. Returns the saved session id, or null if nothing was captured.
     */
    fun finish(): String? {
        val s = session ?: return null
        if (s.captures.isEmpty()) return null
        store.save(s)
        newSession()
        return s.id
    }

    /** Starts over with a fresh session on the same lens. */
    fun newSession() {
        session = null
        _ui.value.lens?.let { if (camera != null) startSession(it) }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    private fun spotCount(lens: LensOption) =
        AimSpotPlanner.plan(lens.portraitHFovDeg, lens.portraitVFovDeg, settings.overlap.toDouble()).size

    override fun onCleared() {
        onSurfaceDestroyed()
    }
}
