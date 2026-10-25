package com.mica.music.data

/** Shared word timing only; callers retain their existing text geometry and animation. */
internal fun lyricCueProgress(
    cues: List<LyricCue>,
    cueIndex: Int,
    positionMs: Int,
    lineEndMs: Int?,
    nextLineTimeMs: Int?,
    fallbackDurationMs: Int,
): Float {
    val cue = cues[cueIndex]
    val start = cue.timeMs.toLong()
    val ownEnd = cue.endTimeMs?.takeIf { it > start }
    val nextStart = cues.getOrNull(cueIndex + 1)?.timeMs?.takeIf { it > start }
    val lineEnd = lineEndMs?.takeIf { it > start }
    val inferredEnd = when {
        nextStart != null && lineEnd != null -> minOf(nextStart, lineEnd)
        else -> nextStart ?: lineEnd
    }
    val end = (ownEnd ?: inferredEnd ?: nextLineTimeMs?.takeIf { it > start })?.toLong()
        ?: (start + fallbackDurationMs.coerceAtLeast(1).toLong())
    return ((positionMs.toLong() - start).toDouble() / (end - start)).toFloat().coerceIn(0f, 1f)
}
