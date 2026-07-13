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

/** Stable runtime owner for a canonical lyrics document and its compatibility view. */
class LyricsSession(val document: LyricsDocument) {
    val lyrics: List<LyricLine> = document.toLegacyLyricLines()
    val hasTimedLyrics: Boolean = LyricsSync.hasTimedLyrics(lyrics)
    private val timelineEngine = LyricsTimelineEngine(document)

    fun snapshotAt(positionMs: Int): LyricsRenderState = LyricsRenderState(
        lyrics = lyrics,
        document = document,
        positionMs = positionMs,
        hasTimedLyrics = hasTimedLyrics,
        activeLineIndex = if (hasTimedLyrics) LyricsSync.indexForPosition(lyrics, positionMs) else -1,
        timeline = timelineEngine.snapshotAt(positionMs),
    )
}

fun List<LyricLine>.renderStateAt(positionMs: Int): LyricsRenderState =
    LyricsSession(toLyricsDocumentCompat()).snapshotAt(positionMs)

fun LyricsDocument.renderStateAt(positionMs: Int): LyricsRenderState =
    LyricsSession(this).snapshotAt(positionMs)
