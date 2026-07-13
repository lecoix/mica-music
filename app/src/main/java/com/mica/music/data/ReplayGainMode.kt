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

enum class ReplayGainSource {
    OFF,
    TRACK_TAG,
    ALBUM_TAG,
    MISSING_TAG,
}

data class AppliedReplayGain(
    val mode: ReplayGainMode,
    val source: ReplayGainSource,
    val linearFactor: Float,
) {
    init {
        require(linearFactor in 0f..1f)
    }

    val modifiesSignal: Boolean
        get() = linearFactor < 1f
}

object ReplayGainPolicy {
    /** Returns a non-clipping linear gain; missing tags leave the stream unchanged. */
    fun linearGain(tags: ReplayGainTags, mode: ReplayGainMode): Float =
        resolve(tags, mode).linearFactor

    fun resolve(tags: ReplayGainTags, mode: ReplayGainMode): AppliedReplayGain {
        if (mode == ReplayGainMode.OFF) {
            return AppliedReplayGain(mode, ReplayGainSource.OFF, 1f)
        }

        val (db, peak, source) = when (mode) {
            ReplayGainMode.TRACK -> Triple(
                tags.trackGainDb,
                tags.trackPeak,
                ReplayGainSource.TRACK_TAG,
            )
            ReplayGainMode.ALBUM -> Triple(
                tags.albumGainDb,
                tags.albumPeak,
                ReplayGainSource.ALBUM_TAG,
            )
            ReplayGainMode.OFF -> error("handled above")
        }
        if (db == null) {
            return AppliedReplayGain(mode, ReplayGainSource.MISSING_TAG, 1f)
        }
        val requested = Math.pow(10.0, (db / 20f).toDouble()).toFloat()
        val safe = peak?.takeIf { it > 0f }?.let { minOf(requested, 1f / it) } ?: requested
        return AppliedReplayGain(mode, source, safe.coerceIn(0f, 1f))
    }
}
