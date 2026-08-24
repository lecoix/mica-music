package com.mica.music.data

/** Shared playback-position interpretation for every lyrics surface. */
data class LyricsRenderState(
    val lyrics: List<LyricLine>,
    val document: LyricsDocument,
    val positionMs: Int,
    val hasTimedLyrics: Boolean,
    val activeLineIndex: Int,
    val timeline: LyricsTimelineSnapshot,
    val positionRevision: Long = 0L,
)

/** Stable runtime owner for a canonical lyrics document and its compatibility view. */
class LyricsSession(val document: LyricsDocument) {
    val lyrics: List<LyricLine> = document.toLegacyLyricLines()
    val hasTimedLyrics: Boolean = LyricsSync.hasTimedLyrics(lyrics)
    private val timelineEngine = LyricsTimelineEngine(document)

    fun snapshotAt(
        positionMs: Int,
        effectiveOffsetMs: Int = 0,
        positionRevision: Long = 0L,
    ): LyricsRenderState {
        val lyricsPositionMs = LyricsTiming.effectivePositionMs(positionMs, effectiveOffsetMs)
        return LyricsRenderState(
            lyrics = lyrics,
            document = document,
            positionMs = lyricsPositionMs,
            hasTimedLyrics = hasTimedLyrics,
            activeLineIndex = if (hasTimedLyrics) {
                LyricsSync.indexForPosition(lyrics, lyricsPositionMs)
            } else {
                -1
            },
            timeline = timelineEngine.snapshotAt(lyricsPositionMs),
            positionRevision = positionRevision,
        )
    }
}

fun List<LyricLine>.renderStateAt(positionMs: Int): LyricsRenderState =
    LyricsSession(toLyricsDocumentCompat()).snapshotAt(positionMs)

fun LyricsDocument.renderStateAt(positionMs: Int): LyricsRenderState =
    LyricsSession(this).snapshotAt(positionMs)
