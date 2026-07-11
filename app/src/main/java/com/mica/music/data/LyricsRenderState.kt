package com.mica.music.data

/** Shared playback-position interpretation for every lyrics surface. */
data class LyricsRenderState(
    val lyrics: List<LyricLine>,
    val document: LyricsDocument,
    val positionMs: Int,
    val hasTimedLyrics: Boolean,
    val activeLineIndex: Int,
    val timeline: LyricsTimelineSnapshot,
)

fun List<LyricLine>.renderStateAt(positionMs: Int): LyricsRenderState {
    val document = toLyricsDocumentCompat()
    val hasTimedLyrics = LyricsSync.hasTimedLyrics(this)
    return LyricsRenderState(
        lyrics = this,
        document = document,
        positionMs = positionMs,
        hasTimedLyrics = hasTimedLyrics,
        activeLineIndex = if (hasTimedLyrics) LyricsSync.indexForPosition(this, positionMs) else -1,
        timeline = LyricsTimelineEngine(document).snapshotAt(positionMs),
    )
}
