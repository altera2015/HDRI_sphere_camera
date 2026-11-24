// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.processing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.hdricapture.session.SessionStore
import java.io.File

/** Runs the native stitch in a foreground worker so long 8K jobs survive leaving the app. */
class StitchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val width = inputData.getInt(KEY_WIDTH, 4096)
        val store = SessionStore(applicationContext)
        val dir = store.dir(id)
        val outDir = File(dir, "output").apply { mkdirs() }
        setForeground(foregroundInfo("Starting", 0f))

        val res = withContext(Dispatchers.Default) {
            NativeBridge.process(dir.absolutePath, outDir.absolutePath, width, hdr = true, exr = true) { stage, f ->
                runBlocking {
                    setProgress(workDataOf(KEY_STAGE to stage, KEY_FRACTION to f))
                    runCatching { setForeground(foregroundInfo(stage, f)) }
                }
            }
        }
        if (!res.ok) return Result.failure(workDataOf(KEY_ERROR to res.error))
        val published = runCatching { publish(id, res.files.map(::File)) }.getOrDefault(emptyList())
        return Result.success(
            workDataOf(
                KEY_FILES to res.files.toTypedArray(),
                KEY_PUBLISHED to published.toTypedArray(),
                KEY_SUMMARY to "aligned ${res.pairs} pairs, error %.2f° → %.2f°".format(res.rmsBefore, res.rmsAfter),
            ),
        )
    }

    /** Copies results to shared storage: HDR/EXR to Download/HDRI, the preview to Pictures/HDRI. */
    private fun publish(id: String, files: List<File>): List<String> {
        val resolver = applicationContext.contentResolver
        // The small preview is only for in-app thumbnails.
        return files.filter { it.extension != "json" && !it.name.endsWith("_preview.jpg") }.mapNotNull { f ->
            val isJpeg = f.extension == "jpg"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "hdri-$id-${f.name}")
                put(MediaStore.MediaColumns.MIME_TYPE, if (isJpeg) "image/jpeg" else "application/octet-stream")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (isJpeg) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/HDRI",
                )
            }
            val collection = if (isJpeg) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: return@mapNotNull null
            resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            "${values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)}/${values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)}"
        }
    }

    private fun foregroundInfo(stage: String, fraction: Float): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "HDRI processing", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Creating HDRI")
            .setContentText(stage)
            .setProgress(100, (fraction * 100).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_SESSION = "session"
        const val KEY_WIDTH = "width"
        const val KEY_STAGE = "stage"
        const val KEY_FRACTION = "fraction"
        const val KEY_ERROR = "error"
        const val KEY_FILES = "files"
        const val KEY_PUBLISHED = "published"
        const val KEY_SUMMARY = "summary"
        private const val CHANNEL = "processing"
        private const val NOTIFICATION_ID = 1

        fun workName(sessionId: String) = "stitch-$sessionId"

        fun enqueue(context: Context, sessionId: String, width: Int) {
            val req = OneTimeWorkRequestBuilder<StitchWorker>()
                .setInputData(workDataOf(KEY_SESSION to sessionId, KEY_WIDTH to width))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(sessionId), ExistingWorkPolicy.REPLACE, req)
        }
    }
}
