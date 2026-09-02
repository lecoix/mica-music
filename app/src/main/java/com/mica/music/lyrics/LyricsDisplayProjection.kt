package com.mica.music.lyrics

import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsSession
import com.mica.music.data.renderStateAt

data class LyricsDisplayOptions(
    val splitEnabled: Boolean,
    val bilingualMode: LyricsBilingualDisplayMode,
    val wordByWordEnabled: Boolean = true,
    val hideTranslationWhenWordByWordEnabled: Boolean = false,
)

/** Pure lyric-display rules shared by media projections and in-app presentation. */
object LyricsDisplayProjection {
    fun subtitle(songTitle: String, artist: String): String =
        when {
            songTitle.isBlank() -> artist
            artist.isBlank() -> songTitle
            else -> "$songTitle - $artist"
        }

    fun lyricIndexForPosition(lyrics: List<LyricLine>, positionMs: Int): Int =
        lyrics.renderStateAt(positionMs).activeLineIndex

    fun lyricIndexForPosition(session: LyricsSession, positionMs: Int): Int =
        session.snapshotAt(positionMs).activeLineIndex

    fun lyricLineText(
        lyrics: List<LyricLine>,
        index: Int,
        display: LyricsDisplayOptions,
    ): String? {
        val raw = lyrics.getOrNull(index)?.text?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val rows = LyricDisplayRows.rowsForBilingualDisplayMode(
            text = raw,
            enabled = display.splitEnabled,
            mode = display.bilingualMode,
        )
        return rows.joinToString(" ") { it.text.trim() }.takeIf { it.isNotBlank() } ?: raw
    }
}