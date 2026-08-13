package com.mica.music.data

import java.util.Locale

object DsdSupport {
    val extensions = setOf("dsf", "dff", "dsdiff")

    fun isDsdExtension(ext: String): Boolean =
        ext.lowercase() in extensions

    fun isDsdMime(mime: String): Boolean {
        val m = mime.lowercase()
        return "dsd" in m || "dsf" in m || "dsdiff" in m || "dff" in m
    }

    fun isDsfMime(mime: String): Boolean = when (normalizedMime(mime)) {
        "audio/x-dsf", "audio/dsf" -> true
        else -> false
    }

    fun isDffMime(mime: String): Boolean = when (normalizedMime(mime)) {
        "audio/x-dsdiff", "audio/dsdiff", "audio/x-dff", "audio/dff" -> true
        else -> false
    }

    fun isDsdMetadata(metadata: TrackMetadata): Boolean =
        metadata.containerName.equals("DSD", ignoreCase = true) ||
            metadata.bitsPerSample == 1 ||
            isDsdMime(metadata.playbackMimeType)

    fun isDsdSong(song: Song): Boolean =
        isDsdMetadata(song.metadata) ||
            isDsdExtension(song.fileName.substringAfterLast('.', ""))

    /**
     * Identifies the supported DSF container without treating generic DSD evidence as DSF.
     * Explicit container MIME wins over filename fallback so wrapped names such as
     * `track.dsf.dsd` remain DSF, while explicit DFF/DSDIFF stays unsupported.
     */
    fun isDsfSong(song: Song): Boolean {
        val mime = song.metadata.playbackMimeType
        if (isDsfMime(mime)) return true
        if (isDffMime(mime)) return false
        return song.fileName.substringAfterLast('.', "").equals("dsf", ignoreCase = true)
    }

    fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
        "dsf" -> "audio/x-dsf"
        "dff", "dsdiff" -> "audio/x-dsdiff"
        else -> "audio/dsd"
    }

    private fun normalizedMime(mime: String): String =
        mime.substringBefore(';').trim().lowercase()

    fun rateLabel(sampleRateHz: Int): String? {
        if (sampleRateHz <= 0) return null
        val multiple = sampleRateHz / 44_100.0
        val dsd = when {
            kotlin.math.abs(multiple - 64.0) < 1.0 -> "DSD64"
            kotlin.math.abs(multiple - 128.0) < 1.0 -> "DSD128"
            kotlin.math.abs(multiple - 256.0) < 1.0 -> "DSD256"
            kotlin.math.abs(multiple - 512.0) < 1.0 -> "DSD512"
            else -> null
        }
        val mhz = sampleRateHz / 1_000_000.0
        val mhzText = if (kotlin.math.abs(mhz - mhz.toInt()) < 0.05) {
            "${mhz.toInt()} MHz"
        } else {
            String.format(Locale.US, "%.1f MHz", mhz)
        }
        return if (dsd != null) "$dsd ($mhzText)" else mhzText
    }
}
