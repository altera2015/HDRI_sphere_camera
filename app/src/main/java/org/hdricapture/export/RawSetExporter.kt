// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hdricapture.session.Session
import org.hdricapture.session.SessionStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packs a session's DNGs, metadata and a Hugin project into a zip and shares it. */
class RawSetExporter(private val context: Context) {

    suspend fun export(session: Session, sessionDir: File): File = withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() } // keep only the latest export in cache
        val zip = File(outDir, "hdri-${session.id}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            fun put(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry("${session.id}/$name"))
                z.write(bytes)
                z.closeEntry()
            }
            File(sessionDir, SessionStore.FILE).takeIf { it.exists() }?.let { put(it.name, it.readBytes()) }
            for (cap in session.captures) for (frame in cap.frames) {
                val f = File(sessionDir, frame.file)
                if (!f.exists()) continue
                // DNGs are already compressed poorly by zip; store them to save time.
                val entry = ZipEntry("${session.id}/${f.name}").apply { method = ZipEntry.DEFLATED }
                z.setLevel(1)
                z.putNextEntry(entry)
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            z.setLevel(6)
            put("project.pto", HuginProject.generate(session).toByteArray())
            put("README.txt", README.toByteArray())
        }
        zip
    }

    fun share(zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Export raw set").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private val README = """
            HDRI Sphere raw set
            ====================

            sNN_eMM.dng   RAW frame MM of aim spot NN (linear sensor data, DNG).
            session.json  Lens calibration, exposure and device orientation (quaternion) of every frame.
            project.pto   Hugin project with initial orientations, exposures and one stack per spot.

            Stitching with Hugin
            --------------------
            1. Convert the DNGs to linear 16-bit TIFFs with the same base name, e.g.:
                 dcraw -T -4 -w *.dng          (writes sNN_eMM.tiff)
            2. Open project.pto in Hugin. Images start at their gyro orientation.
            3. Align (control points + optimise positions), then stitch as "High dynamic range" to EXR.

            Or use the hdri-stitch CLI from the HDRI Sphere repository, which reads session.json directly.
        """.trimIndent()
    }
}
