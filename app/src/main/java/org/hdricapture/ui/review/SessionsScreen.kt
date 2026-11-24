// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.ui.review

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.PanoramaPhotosphere
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.log2
import kotlin.math.max
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hdricapture.export.RawSetExporter
import org.hdricapture.processing.StitchWorker
import org.hdricapture.session.Session
import org.hdricapture.session.SessionStore
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(onBack: () -> Unit, onView: (hdrPath: String) -> Unit) {
    val context = LocalContext.current
    val store = remember { SessionStore(context) }
    val exporter = remember { RawSetExporter(context) }
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(store.list()) }
    var busy by remember { mutableStateOf<String?>(null) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (sessions.isEmpty()) {
            Text("No captures yet.", Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { s ->
                SessionCard(
                    s,
                    outputDir = File(store.dir(s.id), "output"),
                    exporting = busy == s.id,
                    onView = onView,
                    onCreate = { width ->
                        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        StitchWorker.enqueue(context, s.id, width)
                    },
                    onExport = {
                        busy = s.id
                        scope.launch {
                            runCatching { exporter.share(exporter.export(s, store.dir(s.id))) }
                            busy = null
                        }
                    },
                    onDelete = {
                        WorkManager.getInstance(context).cancelUniqueWork(StitchWorker.workName(s.id))
                        store.delete(s.id)
                        sessions = store.list()
                    },
                )
            }
        }
    }
}

private data class Resolution(val label: String, val width: Int, val note: String)

private val RESOLUTIONS = listOf(
    Resolution("2K", 2048, "2048 × 1024 · quick, fine for lighting and previews"),
    Resolution("4K", 4096, "4096 × 2048 · good default for backgrounds and reflections"),
    Resolution("8K", 8192, "8192 × 4096 · full detail of the ultra-wide, larger files"),
)

@Composable
private fun ResolutionDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var selected by remember { mutableIntStateOf(4096) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Output resolution") },
        text = {
            Column {
                RESOLUTIONS.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { selected = r.width }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == r.width, onClick = { selected = r.width })
                        Column {
                            Text(r.label, style = MaterialTheme.typography.titleSmall)
                            Text(r.note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(selected) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(outputDir: File, sessionId: String, onDismiss: () -> Unit, onRawSet: () -> Unit) {
    val context = LocalContext.current
    val exr = File(outputDir, "environment.exr")
    val hdr = File(outputDir, "environment.hdr")
    // Full-resolution 360° JPEG; older outputs only have the small preview.
    val jpg = File(outputDir, "environment.jpg").takeIf { it.exists() } ?: File(outputDir, "environment_preview.jpg")
    fun size(f: File) = "%.1f MB".format(f.length() / 1e6)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Share", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (!exr.exists() && !hdr.exists()) {
                Text(
                    "Create the HDRI first to share EXR / HDR files.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            if (exr.exists()) ShareRow("OpenEXR (.exr)", "Half float · Unreal, Blender, Unity · ${size(exr)}") {
                onDismiss(); shareFile(context, exr, "image/x-exr", "hdri-$sessionId.exr")
            }
            if (hdr.exists()) ShareRow("Radiance HDR (.hdr)", "RGBE · Godot, Unity, most tools · ${size(hdr)}") {
                onDismiss(); shareFile(context, hdr, "image/vnd.radiance", "hdri-$sessionId.hdr")
            }
            if (jpg.exists()) ShareRow("JPEG (.jpg)", "Tone-mapped 360° photo · Google Photos, social media · ${size(jpg)}") {
                onDismiss(); shareFile(context, jpg, "image/jpeg", "hdri-$sessionId.jpg")
            }
            ShareRow("Raw set (.zip)", "All DNG frames, session.json and a Hugin project") { onRawSet() }
        }
    }
}

@Composable
private fun ShareRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Serializable
private data class StitchStats(
    val width: Int = 0,
    val dynamicRangeStops: Double = 0.0,
    val lumP1: Double = 0.0,
    val lumMedian: Double = 0.0,
    val lumP99: Double = 0.0,
    val lumMax: Double = 0.0,
    val pairs: Int = 0,
    val rmsAfterDeg: Double = 0.0,
)

private val statsJson = Json { ignoreUnknownKeys = true }

private fun loadStats(dir: File): StitchStats? = File(dir, "stats.json").takeIf { it.exists() }?.let {
    runCatching { statsJson.decodeFromString(StitchStats.serializer(), it.readText()) }.getOrNull()
}

/** Compact "×N" formatting for luminance ratios. */
private fun ratio(x: Double): String = when {
    x >= 10_000 -> "%.0fk×".format(x / 1000)
    x >= 100 -> "%.0f×".format(x)
    else -> "%.1f×".format(x)
}

/** Horizontal bar of the luminance range on a log2 scale: p1..p99 solid, up to max lighter, median tick. */
@Composable
private fun DynamicRangeBar(st: StitchStats) {
    val lo = log2(max(st.lumP1, 1e-9))
    val span = max(1e-6, log2(max(st.lumMax, 1e-9)) - lo)
    fun pos(v: Double) = ((log2(max(v, 1e-9)) - lo) / span).toFloat().coerceIn(0f, 1f)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val h = size.height
        drawRoundRect(track, cornerRadius = CornerRadius(h / 2))
        drawRoundRect(primary.copy(alpha = 0.35f), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
        drawRoundRect(primary, size = Size(size.width * pos(st.lumP99), h), cornerRadius = CornerRadius(h / 2))
        val m = size.width * pos(st.lumMedian)
        drawLine(Color.White, Offset(m, 0f), Offset(m, h), strokeWidth = 4f)
    }
}

@Composable
private fun SessionCard(
    s: Session,
    outputDir: File,
    exporting: Boolean,
    onView: (String) -> Unit,
    onCreate: (Int) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val work by remember(s.id) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(StitchWorker.workName(s.id)).map { it.firstOrNull() }
    }.collectAsState(initial = null)
    val running = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED
    val preview = File(outputDir, "environment_preview.jpg")

    // Reload the preview when a job finishes.
    var bitmap by remember(s.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(s.id, work?.state) {
        bitmap = withContext(Dispatchers.IO) {
            if (preview.exists()) BitmapFactory.decodeFile(preview.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) else null
        }
    }

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var confirmDelete by remember { mutableStateOf(false) }
            var menuOpen by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DateFormat.getDateTimeInstance().format(Date(s.createdAtMillis)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            enabled = !running,
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                }
            }
            val frames = s.captures.sumOf { it.frames.size }
            Text(
                "${s.captures.size} / ${s.spots.size} spots · $frames RAW frames · ${"%.1f".format(s.lens.focalLengthMm)} mm" +
                    if (s.captures.any { it.highlightsClipped }) " · highlights clipped" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            bitmap?.let {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(8.dp)).clickable(enabled = !running) {
                        File(outputDir, "environment.hdr").takeIf { f -> f.exists() }?.let { f -> onView(f.absolutePath) }
                    },
                ) {
                    Image(it.asImageBitmap(), "Open panorama viewer", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Icon(
                        Icons.Default.PanoramaPhotosphere, null, tint = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(6.dp),
                    )
                }
            }
            val stats = remember(s.id, work?.state) { loadStats(outputDir) }
            stats?.let { st ->
                Text(
                    "Dynamic range %.1f stops · brightest %s median · %d×%d".format(
                        st.dynamicRangeStops, ratio(st.lumMax / max(st.lumMedian, 1e-9)), st.width, st.width / 2,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                DynamicRangeBar(st)
            }
            when (work?.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                    val stage = work?.progress?.getString(StitchWorker.KEY_STAGE) ?: "Queued"
                    val f = work?.progress?.getFloat(StitchWorker.KEY_FRACTION, 0f) ?: 0f
                    Text(stage, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                }
                WorkInfo.State.FAILED -> Text(
                    "Failed: ${work?.outputData?.getString(StitchWorker.KEY_ERROR) ?: "unknown error"}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                WorkInfo.State.SUCCEEDED -> Text(
                    (work?.outputData?.getString(StitchWorker.KEY_SUMMARY) ?: "") + "\nSaved to Download/HDRI and Pictures/HDRI",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Unit
            }
            var pickResolution by remember { mutableStateOf(false) }
            var showShare by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { pickResolution = true }, enabled = !running && s.captures.isNotEmpty()) {
                    Text(if (preview.exists()) "Re-create HDRI" else "Create HDRI")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showShare = true }, enabled = !running && !exporting) {
                    if (exporting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Share, "Share")
                }
            }
            if (pickResolution) {
                ResolutionDialog(
                    onDismiss = { pickResolution = false },
                    onPick = { w -> pickResolution = false; onCreate(w) },
                )
            }
            if (showShare) {
                ShareSheet(
                    outputDir = outputDir,
                    sessionId = s.id,
                    onDismiss = { showShare = false },
                    onRawSet = { showShare = false; onExport() },
                )
            }
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete this capture?") },
                    text = { Text("All RAW frames and generated HDRIs of this session will be removed. Copies already saved to Download/HDRI and Pictures/HDRI are kept.") },
                    confirmButton = {
                        TextButton(onClick = { confirmDelete = false; onDelete() }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
                )
            }
        }
    }
}

private fun shareFile(context: Context, file: File, mime: String, name: String = file.name) {
    if (!file.exists()) return
    // Share under a descriptive name: copy into the export cache (FileProvider serves the file name).
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val named = File(dir, name)
    if (named.absolutePath != file.absolutePath) file.copyTo(named, overwrite = true)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", named)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share $name"))
}
