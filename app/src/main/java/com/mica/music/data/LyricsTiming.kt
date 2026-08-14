package com.mica.music.data

const val MAX_LYRICS_OFFSET_MS = 5_000
const val MIN_LYRICS_OFFSET_MS = -MAX_LYRICS_OFFSET_MS

/** User-facing lyrics timing. Positive values make lyrics appear earlier. */
object LyricsTiming {
    fun normalizeLayer(offsetMs: Int): Int =
        offsetMs.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)

    fun effectiveOffsetMs(globalOffsetMs: Int, songOffsetMs: Int): Int =
        normalizeLayer(globalOffsetMs) + normalizeLayer(songOffsetMs)

    fun effectivePositionMs(playbackPositionMs: Int, effectiveOffsetMs: Int): Int =
        (playbackPositionMs.toLong() + effectiveOffsetMs.toLong())
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()

    fun seekPositionMs(lyricTimeMs: Int, effectiveOffsetMs: Int): Int =
        (lyricTimeMs.toLong() - effectiveOffsetMs.toLong())
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
}
