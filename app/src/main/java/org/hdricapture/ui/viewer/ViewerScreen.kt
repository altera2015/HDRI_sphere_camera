// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.ui.viewer

import android.content.Context
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.hdricapture.geom.CameraFrame
import org.hdricapture.sensors.OrientationTracker
import java.io.File
import kotlin.math.pow

/**
 * Look around a stitched panorama by moving the phone. Drag to pan, pinch to zoom, slider for exposure.
 */
@Composable
fun ViewerScreen(hdrFile: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val tracker = remember { OrientationTracker(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager) }
    var status by remember { mutableStateOf("Loading…") }
    var autoExposure by remember { mutableFloatStateOf(1f) }
    var ev by remember { mutableFloatStateOf(0f) }

    val renderer = remember {
        PanoramaRenderer(hdrFile.absolutePath, pose = { tracker.state.value.pose }) { ok, auto ->
            status = if (ok) "" else "Could not load ${hdrFile.name}"
            autoExposure = auto
        }
    }
    renderer.exposure = autoExposure * 2f.pow(ev)

    fun recentre() {
        // Make the current viewing direction the panorama centre (its first captured spot).
        renderer.headingDeg = CameraFrame.fromDevicePose(tracker.state.value.pose).forward.toYawPitch().first.toFloat()
        renderer.yawOffsetDeg = 0f
        renderer.pitchOffsetDeg = 0f
    }

    DisposableEffect(Unit) {
        tracker.start()
        onDispose { tracker.stop() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (renderer.headingDeg.isNaN()) recentre()
                    val degPerPx = renderer.fovDeg / size.height
                    renderer.yawOffsetDeg -= pan.x * degPerPx
                    renderer.pitchOffsetDeg = (renderer.pitchOffsetDeg + pan.y * degPerPx).coerceIn(-80f, 80f)
                    renderer.fovDeg = (renderer.fovDeg / zoom).coerceIn(20f, 110f)
                }
            },
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
            onRelease = { it.onPause() },
        )
        // Centre on the first pose sample once the sensors have produced one.
        val pose by tracker.state.let { flow ->
            androidx.compose.runtime.produceState(initialValue = flow.value) { flow.collect { value = it } }
        }
        if (renderer.headingDeg.isNaN() && pose.timestampNs != 0L) recentre()

        Row(
            Modifier.fillMaxWidth().align(Alignment.TopStart).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(status, color = Color.White, modifier = Modifier.weight(1f))
            IconButton(onClick = ::recentre) { Icon(Icons.Default.CenterFocusStrong, "Re-centre", tint = Color.White) }
        }
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Exposure ${"%+.1f".format(ev)} EV", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Slider(value = ev, onValueChange = { ev = it }, valueRange = -6f..6f)
            Text(
                "Move the phone to look around · drag to pan · pinch to zoom",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(400.dp),
            )
        }
    }
}
