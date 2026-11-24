// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.session

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sessions live in app-private storage: files/sessions/ID/ holds session.json, the DNGs and outputs. */
class SessionStore(context: Context) {
    private val root = File(context.filesDir, "sessions").apply { mkdirs() }

    fun newSessionId(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun dir(id: String): File = File(root, id).apply { mkdirs() }

    fun save(session: Session) {
        val f = File(dir(session.id), FILE)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(json.encodeToString(Session.serializer(), session))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun load(id: String): Session? = File(File(root, id), FILE).takeIf { it.exists() }?.let {
        runCatching { json.decodeFromString(Session.serializer(), it.readText()) }.getOrNull()
    }

    fun list(): List<Session> =
        root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { load(it.name) }.sortedByDescending { it.createdAtMillis }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    companion object {
        const val FILE = "session.json"
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
