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

    fun isDsdMetadata(metadata: TrackMetadata): Boolean =
        metadata.containerName.equals("DSD", ignoreCase = true) ||
            metadata.bitsPerSample == 1 ||
            isDsdMime(metadata.playbackMimeType)

    fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
        "dsf" -> "audio/x-dsf"
        "dff", "dsdiff" -> "audio/x-dsdiff"
        else -> "audio/dsd"
    }

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
