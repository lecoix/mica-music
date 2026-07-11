package com.mica.music.media

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaMetadata
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsSync
import com.mica.music.data.Song
import com.mica.music.data.renderStateAt

/** 媒体通知栏歌词：主位歌词、副位「歌名 - 歌手」。 */
object NotificationLyrics {

    data class DisplayOptions(
        val splitEnabled: Boolean,
        val bilingualMode: LyricsBilingualDisplayMode,
    )

    fun displayOptions(context: Context): DisplayOptions =
        DisplayOptions(
            splitEnabled = LyricsPreferences.lyricSplitEnabled(context),
            bilingualMode = LyricsPreferences.lyricsBilingualDisplayMode(context),
        )

    fun subtitle(songTitle: String, artist: String): String =
        when {
            songTitle.isBlank() -> artist
            artist.isBlank() -> songTitle
            else -> "$songTitle - $artist"
        }

    fun lyricIndexForPosition(lyrics: List<LyricLine>, positionMs: Int): Int {
        return lyrics.renderStateAt(positionMs).activeLineIndex
    }

    fun lyricLineText(
        lyrics: List<LyricLine>,
        index: Int,
        display: DisplayOptions,
    ): String? {
        val raw = lyrics.getOrNull(index)?.text?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val rows = LyricDisplayRows.rowsForBilingualDisplayMode(
            text = raw,
            enabled = display.splitEnabled,
            mode = display.bilingualMode,
        )
        return rows.joinToString(" ") { it.text.trim() }.takeIf { it.isNotBlank() } ?: raw
    }

    fun signature(songId: String, lyricIndex: Int, displayLine: String): String =
        "$songId:$lyricIndex:$displayLine"

    fun metadataWithLyric(
        song: Song,
        lyricIndex: Int,
        base: MediaMetadata,
        display: DisplayOptions,
    ): MediaMetadata? {
        val line = lyricLineText(song.lyrics, lyricIndex, display) ?: return null
        return base.buildUpon()
            .setTitle(line)
            .setDisplayTitle(line)
            .setArtist(subtitle(song.title, song.artist))
            .setExtras(ensureCanonicalTitleExtras(base.extras, song.title))
            .build()
    }

    fun defaultPlaybackMetadata(song: Song, base: MediaMetadata): MediaMetadata =
        base.buildUpon()
            .setTitle(song.title)
            .setDisplayTitle(song.title)
            .setArtist(song.artist)
            .setExtras(ensureCanonicalTitleExtras(base.extras, song.title))
            .build()

    internal fun ensureCanonicalTitleExtras(extras: Bundle?, songTitle: String): Bundle {
        val bundle = Bundle(extras ?: Bundle.EMPTY)
        if (songTitle.isNotBlank()) {
            bundle.putString(SongMediaItemCodec.canonicalTitleExtraKey(), songTitle)
        }
        return bundle
    }
}
