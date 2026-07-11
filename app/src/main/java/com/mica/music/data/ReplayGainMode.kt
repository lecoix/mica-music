package com.mica.music.data

enum class ReplayGainMode(val storageValue: String) {
    OFF("off"),
    TRACK("track"),
    ALBUM("album");

    companion object {
        fun fromStorage(value: String?): ReplayGainMode =
            entries.firstOrNull { it.storageValue == value } ?: OFF
    }
}

object ReplayGainPolicy {
    /** Returns a non-clipping linear gain; missing tags leave the stream unchanged. */
    fun linearGain(tags: ReplayGainTags, mode: ReplayGainMode): Float {
        val (db, peak) = when (mode) {
            ReplayGainMode.TRACK -> tags.trackGainDb to tags.trackPeak
            ReplayGainMode.ALBUM -> tags.albumGainDb to tags.albumPeak
            ReplayGainMode.OFF -> return 1f
        }
        val requested = db?.let { Math.pow(10.0, (it / 20f).toDouble()).toFloat() } ?: return 1f
        val safe = peak?.takeIf { it > 0f }?.let { minOf(requested, 1f / it) } ?: requested
        return safe.coerceIn(0f, 1f)
    }
}
