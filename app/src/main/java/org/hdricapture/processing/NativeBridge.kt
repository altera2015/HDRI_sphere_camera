// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.processing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JNI entry points into the native HDR/stitching core (`core/`). */
object NativeBridge {
    init {
        System.loadLibrary("hdricapture")
    }

    fun interface Progress {
        fun onProgress(stage: String, fraction: Float)
    }

    @Serializable
    data class Result(
        val ok: Boolean,
        val error: String = "",
        val files: List<String> = emptyList(),
        val pairs: Int = 0,
        val rmsBefore: Double = 0.0,
        val rmsAfter: Double = 0.0,
        val holes: Double = 0.0,
    )

    private external fun nativeVersion(): String
    private external fun nativeProcess(sessionDir: String, outDir: String, width: Int, hdr: Boolean, exr: Boolean, progress: Progress): String

    private external fun nativeUploadHdrTexture(path: String): FloatArray?

    val version: String by lazy { nativeVersion() }

    /**
     * Decodes an .hdr file into the currently bound GL_TEXTURE_2D (RGBA16F). Call on the GL thread.
     * Returns (width, height, autoExposure) or null.
     */
    fun uploadHdrTexture(path: String): Triple<Int, Int, Float>? =
        nativeUploadHdrTexture(path)?.let { Triple(it[0].toInt(), it[1].toInt(), it[2]) }

    /** Blocking: develops, merges, aligns and stitches a session. Run off the main thread. */
    fun process(sessionDir: String, outDir: String, width: Int, hdr: Boolean, exr: Boolean, progress: Progress): Result =
        Json { ignoreUnknownKeys = true }.decodeFromString(Result.serializer(), nativeProcess(sessionDir, outDir, width, hdr, exr, progress))
}
