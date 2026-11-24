// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.hdricapture.session.RigMode
import org.hdricapture.settings.AppSettings

@Composable
fun CaptureScreen(onOpenSessions: () -> Unit, vm: CaptureViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    val ui by vm.ui.collectAsStateWithLifecycle()
    val pose by vm.tracker.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopBar(ui, vm, onOpenSessions)

        val lens = ui.lens
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when {
                !granted -> Text("Camera permission is needed to capture.", color = Color.White)
                ui.phase == Phase.ERROR && lens == null -> Text(ui.message ?: "Error", color = Color.White, modifier = Modifier.padding(24.dp))
                lens != null -> {
                    val aspect = lens.rawSize.height.toFloat() / lens.rawSize.width // portrait
                    Box(Modifier.fillMaxWidth().aspectRatio(aspect)) {
                        key(lens.id) { CameraPreview(vm) }
                        AimOverlay(
                            pose = pose.pose,
                            spots = ui.spots,
                            done = ui.done,
                            targetIndex = ui.targetIndex,
                            headingDeg = ui.headingDeg,
                            hFovDeg = lens.portraitHFovDeg,
                            holdProgress = ui.holdProgress,
                            modifier = Modifier.fillMaxSize(),
                        )
                        CoverageMap(ui.spots, ui.done, ui.targetIndex,
                            Modifier.align(Alignment.BottomStart).padding(8.dp).width(120.dp).height(60.dp))
                        HintBanner(ui, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                    }
                }
            }
        }

        BottomBar(ui, vm, onOpenSessions)
    }

    ui.message?.let { msg ->
        if (ui.phase != Phase.ERROR || ui.lens != null) {
            AlertDialog(
                onDismissRequest = vm::dismissMessage,
                confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } },
                text = { Text(msg) },
            )
        }
    }
    HandheldTip(ui.rigMode)
}

@Composable
private fun CameraPreview(vm: CaptureViewModel) {
    val size = remember { vm.previewSize() }
    DisposableEffect(Unit) { onDispose { vm.onSurfaceDestroyed() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        size?.let { st.setDefaultBufferSize(it.width, it.height) }
                        vm.onSurfaceReady(Surface(st))
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        vm.onSurfaceDestroyed()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
    )
}

@Composable
private fun TopBar(ui: CaptureUiState, vm: CaptureViewModel, onOpenSessions: () -> Unit) {
    val locked = ui.done.isNotEmpty()
    var confirmLens by remember { mutableStateOf<org.hdricapture.camera.LensOption?>(null) }
    confirmLens?.let { l ->
        AlertDialog(
            onDismissRequest = { confirmLens = null },
            title = { Text("Switch to ${l.label}?") },
            text = { Text("This starts a new capture. The ${ui.done.size} spots taken so far stay in Sessions as an incomplete capture.") },
            confirmButton = { TextButton(onClick = { vm.selectLens(l); confirmLens = null }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { confirmLens = null }) { Text("Cancel") } },
        )
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ui.lenses.forEach { l ->
            FilterChip(
                selected = l.id == ui.lens?.id,
                onClick = { if (locked && l.id != ui.lens?.id) confirmLens = l else vm.selectLens(l) },
                label = { Text(l.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
        Spacer(Modifier.width(12.dp))
        FilterChip(
            selected = ui.rigMode == RigMode.TRIPOD,
            onClick = { vm.setRigMode(if (ui.rigMode == RigMode.TRIPOD) RigMode.HANDHELD else RigMode.TRIPOD) },
            label = { Text("Tripod", style = MaterialTheme.typography.labelSmall) },
        )
    }
}

@Composable
private fun HintBanner(ui: CaptureUiState, modifier: Modifier) {
    val text = when (ui.phase) {
        Phase.STARTING -> "Starting camera…"
        Phase.CAPTURING -> "Capturing bracket — hold still"
        Phase.FINISHED -> "All spots captured!"
        Phase.ERROR -> ui.message ?: "Error"
        Phase.AIMING -> when (ui.hint) {
            Hint.AIM -> "Aim the crosshair at the yellow circle"
            Hint.LEVEL -> "Level the phone"
            Hint.HOLD_STILL -> "Hold still…"
            Hint.CAPTURING -> "Capturing…"
            Hint.STEP_ASIDE -> "Last shot: hold the phone out over where you stood and aim straight down"
        }
    }
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun BottomBar(ui: CaptureUiState, vm: CaptureViewModel, onOpenSessions: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabeledIconButton(Icons.Default.Collections, "Sessions", onClick = onOpenSessions)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${ui.done.size} / ${ui.spots.size} spots",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            if (ui.manualAvailable && ui.phase == Phase.AIMING) {
                OutlinedButton(onClick = vm::shutter) { Text("Capture now") }
            }
        }
        when {
            ui.phase == Phase.FINISHED -> LabeledIconButton(Icons.Default.Refresh, "New capture", onClick = vm::newSession)
            ui.done.isEmpty() -> LabeledIconButton(Icons.Default.Explore, "Re-centre", onClick = vm::recentre)
            else -> LabeledIconButton(Icons.Default.Check, "Finish") {
                val n = ui.done.size
                if (vm.finish() != null) android.widget.Toast.makeText(ctx, "Saved $n spots to Sessions", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun LabeledIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(88.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun HandheldTip(rig: RigMode) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var show by remember { mutableStateOf(rig == RigMode.HANDHELD && !settings.handheldTipShown) }
    if (!show) return
    AlertDialog(
        onDismissRequest = { show = false; settings.handheldTipShown = true },
        confirmButton = { TextButton(onClick = { show = false; settings.handheldTipShown = true }) { Text("Got it") } },
        title = { Text("Handheld tips") },
        text = {
            Text(
                "• Turn around the camera lens, not around your body: keep the phone in one spot in space and rotate it.\n" +
                    "• Hold still when the circle fills up; each spot takes a short burst.\n" +
                    "• Nearby objects show seams if the phone moves; distant scenery is forgiving.\n" +
                    "• For best results use a tripod with a panoramic head and switch to Tripod mode.",
            )
        },
    )
}


