package com.mica.music.data

/**
 * Mica-owned offline loudness analysis. This is intentionally stored separately from file metadata
 * ReplayGain tags so rescans never pretend generated values came from the source file.
 */
data class LoudnessAnalysis(
    val integratedLufs: Float? = null,
    /** Linear full-scale sample peak, where 1.0 == 0 dBFS. */
    val samplePeak: Float? = null,
    /** Gain needed to reach [TARGET_LUFS] before peak-safety clamping. */
    val trackGainDb: Float? = null,
    /** Source identity captured when this analysis was produced. */
    val sourceSizeBytes: Long = 0L,
    val sourceModifiedMs: Long = 0L,
    val analyzerRevision: Int = 0,
) {
    val isValid: Boolean
        get() = integratedLufs?.isFinite() == true &&
            samplePeak?.let { it.isFinite() && it >= 0f } == true &&
            trackGainDb?.isFinite() == true &&
            analyzerRevision == CURRENT_ANALYZER_REVISION

    fun matchesSource(sizeBytes: Long, modifiedMs: Long): Boolean =
        isValid && sourceSizeBytes == sizeBytes && sourceModifiedMs == modifiedMs

    fun matches(song: Song): Boolean = matchesSource(song.sizeBytes, song.dateModifiedMs)

    fun asReplayGainFallback(): ReplayGainTags = if (isValid) {
        ReplayGainTags(trackGainDb = trackGainDb, trackPeak = samplePeak)
    } else {
        ReplayGainTags()
    }

    companion object {
        /** ReplayGain 2.0 / EBU R128 reference target. */
        const val TARGET_LUFS = -18f
        const val CURRENT_ANALYZER_REVISION = 1
    }
}

fun Song.effectiveReplayGainTags(): ReplayGainTags {
    if (replayGain.trackGainDb != null) return replayGain
    val fallback = loudnessAnalysis.takeIf { it.matches(this) }?.asReplayGainFallback() ?: return replayGain
    return replayGain.copy(
        trackGainDb = fallback.trackGainDb,
        trackPeak = fallback.trackPeak,
    )
}
