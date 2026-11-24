// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Latest auto-exposure values from the preview, used to centre each bracket. */
data class Metering(val exposureNs: Long, val iso: Int)

/** A RAW frame and its metadata. The consumer must close [image]. */
class RawCapture(val image: Image, val result: CaptureResult, val exposureNs: Long, val iso: Int)

/**
 * Owns the Camera2 device and session for one lens: an auto-exposed preview stream plus a RAW
 * ImageReader for manual-exposure bursts.
 */
class CameraController(private val manager: CameraManager, val lens: LensOption) {

    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private val _metering = MutableStateFlow<Metering?>(null)
    val metering: StateFlow<Metering?> = _metering

    private val chars: CameraCharacteristics get() = lens.characteristics

    @SuppressLint("MissingPermission") // checked by the UI before opening
    suspend fun open(preview: Surface) {
        previewSurface = preview
        val dev = suspendCancellableCoroutine { cont ->
            manager.openCamera(lens.openId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = cont.resume(camera)
                override fun onDisconnected(camera: CameraDevice) {
                    android.util.Log.e(TAG, "camera ${camera.id} disconnected")
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    android.util.Log.e(TAG, "camera ${camera.id} error $error")
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error"))
                }
            })
        }
        device = dev

        val reader = ImageReader.newInstance(lens.rawSize.width, lens.rawSize.height, ImageFormat.RAW_SENSOR, MAX_RAW_IMAGES)
        rawReader = reader

        val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(reader.surface)).onEach { cfg ->
            lens.physicalId?.let { cfg.setPhysicalCameraId(it) }
        }
        session = suspendCancellableCoroutine { cont ->
            val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) = cont.resume(s)
                    override fun onConfigureFailed(s: CameraCaptureSession) = cont.resumeWithException(
                        IllegalStateException("This lens can't stream RAW alongside the preview. Try another lens."),
                    )
                })
            dev.createCaptureSession(cfg)
        }
        startPreview()
    }

    private fun requestBuilder(template: Int): CaptureRequest.Builder {
        val dev = device ?: error("Camera not open")
        return lens.physicalId?.let { dev.createCaptureRequest(template, setOf(it)) } ?: dev.createCaptureRequest(template)
    }

    /** Sets [key] on the logical request and, for physical-lens streams, on the physical sub-request too. */
    private fun <T> CaptureRequest.Builder.setBoth(key: CaptureRequest.Key<T>, value: T) {
        set(key, value)
        lens.physicalId?.let { setPhysicalCameraKey(key, value, it) }
    }

    private fun startPreview() {
        val s = session ?: return
        val b = requestBuilder(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(previewSurface!!)
        b.setBoth(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        b.setBoth(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        s.setRepeatingRequest(b.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val r = physicalResult(result)
                val t = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val iso = r.get(CaptureResult.SENSOR_SENSITIVITY)
                if (t != null && iso != null) _metering.value = Metering(t, iso)
            }
        }, handler)
    }

    private fun physicalResult(result: TotalCaptureResult): CaptureResult {
        val pid = lens.physicalId ?: return result
        val physical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result.physicalCameraTotalResults[pid]
        } else {
            @Suppress("DEPRECATION")
            result.physicalCameraResults[pid]
        }
        return physical ?: result
    }

    /**
     * Captures one RAW frame per exposure at a fixed ISO with focus and white balance locked.
     * Frames are delivered to [onFrame] in capture order as they arrive; it must close the image.
     */
    suspend fun captureBurst(exposuresNs: List<Long>, iso: Int, onFrame: suspend (RawCapture) -> Unit) {
        val s = session ?: error("Session not open")
        val reader = rawReader!!
        val focus = chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
        val minFrame = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, lens.rawSize) ?: 0L

        val requests = exposuresNs.map { exp ->
            requestBuilder(CameraDevice.TEMPLATE_MANUAL).apply {
                addTarget(reader.surface)
                setBoth(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                setBoth(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                setBoth(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                setBoth(CaptureRequest.CONTROL_AWB_LOCK, true)
                setBoth(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
                setBoth(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                setBoth(CaptureRequest.SENSOR_SENSITIVITY, iso)
                setBoth(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exp, minFrame))
                setBoth(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                setBoth(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }.build()
        }

        // Pair images with results by sensor timestamp.
        val pending = exposuresNs.indices.associateWith { CompletableDeferred<RawCapture>() }
        val results = mutableMapOf<Long, Pair<Int, CaptureResult>>()
        val images = mutableMapOf<Long, Image>()
        fun tryPair(ts: Long) {
            val r = results[ts] ?: return
            val img = images.remove(ts) ?: return
            results.remove(ts)
            val idx = r.first
            pending.getValue(idx).complete(RawCapture(img, r.second, exposuresNs[idx], iso))
        }
        reader.setOnImageAvailableListener({ rd ->
            val img = rd.acquireNextImage() ?: return@setOnImageAvailableListener
            images[img.timestamp] = img
            tryPair(img.timestamp)
        }, handler)
        s.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val idx = requests.indexOf(request)
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                results[ts] = idx to physicalResult(result)
                tryPair(ts)
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                android.util.Log.e(TAG, "burst capture failed: reason ${failure.reason}")
                pending[requests.indexOf(request)]?.completeExceptionally(IllegalStateException("Capture failed (${failure.reason})"))
            }
        }, handler)

        try {
            val timeoutMs = 3000 + exposuresNs.sum() / 1_000_000 * 2
            withTimeout(timeoutMs) {
                for (i in exposuresNs.indices) onFrame(pending.getValue(i).await())
            }
        } finally {
            reader.setOnImageAvailableListener(null, null)
            handler.post { images.values.forEach { it.close() }; images.clear() }
        }
    }

    /** True if sensor timestamps share the clock of sensor events (elapsedRealtimeNanos). */
    val timestampsAreRealtime: Boolean
        get() = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    fun close() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { rawReader?.close() }
        session = null
        device = null
        rawReader = null
        thread.quitSafely()
    }

    companion object {
        /** Preview buffer size with the RAW aspect ratio so the preview shows the full capture FOV. */
        fun previewSize(lens: LensOption): Size {
            val map = lens.characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val target = lens.rawSize.width.toDouble() / lens.rawSize.height
            val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty()
            return sizes
                .filter { it.width <= 1920 && it.height <= 1440 }
                .filter { kotlin.math.abs(it.width.toDouble() / it.height - target) < 0.02 }
                .maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { kotlin.math.abs(it.width.toDouble() / it.height - target) }!!
        }

        /** RAW buffers are large (~24 MB at 12 MP); frames are saved and closed as they arrive. */
        const val MAX_RAW_IMAGES = 6
        private const val TAG = "HdriCamera"
    }
}
