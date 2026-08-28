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
    TRACK_SCAN,
    ALBUM_TAG,
    MISSING_TAG,
}

data class AppliedReplayGain(
    val mode: ReplayGainMode,
    val source: ReplayGainSource,
    val linearFactor: Float,
) {
    init {
        require(linearFactor.isFinite() && linearFactor >= 0f)
    }

    val modifiesSignal: Boolean
        get() = linearFactor != 1f
}

object ReplayGainPolicy {
    private const val MAX_LINEAR_FACTOR = 16f

    /** Returns a non-clipping linear gain; missing tags leave the stream unchanged. */
    fun linearGain(tags: ReplayGainTags, mode: ReplayGainMode): Float =
        resolve(tags, mode).linearFactor

    fun resolve(
        tags: ReplayGainTags,
        mode: ReplayGainMode,
        loudnessAnalysis: LoudnessAnalysis? = null,
    ): AppliedReplayGain {
        if (mode == ReplayGainMode.OFF) {
            return AppliedReplayGain(mode, ReplayGainSource.OFF, 1f)
        }

        val (db, peak, source) = when (mode) {
            ReplayGainMode.TRACK -> if (tags.trackGainDb != null) {
                Triple(tags.trackGainDb, tags.trackPeak, ReplayGainSource.TRACK_TAG)
            } else {
                Triple(
                    loudnessAnalysis?.trackGainDb,
                    loudnessAnalysis?.samplePeak,
                    ReplayGainSource.TRACK_SCAN,
                )
            }
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
        val peakSafe = if (source == ReplayGainSource.TRACK_SCAN) {
            // Mica-owned scans run through the shared PCM linked limiter, so preserve the R128 target
            // gain here and let the real signal determine transient limiting. Static sample-peak
            // pre-clamping would otherwise defeat normalization for dynamic tracks peaking near 0 dBFS.
            requested
        } else {
            peak?.takeIf { it > 0f }?.let { minOf(requested, 1f / it) } ?: requested
        }
        return AppliedReplayGain(mode, source, peakSafe.coerceIn(0f, MAX_LINEAR_FACTOR))
    }
}
