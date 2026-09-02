package com.mica.music.media

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaMetadata
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.data.Song
import com.mica.music.lyrics.LyricsDisplayOptions
import com.mica.music.lyrics.LyricsDisplayProjection

/** 媒体通知栏歌词：主位歌词、副位「歌名 - 歌手」。 */
object NotificationLyrics {
    private const val OVERLAY_TOKEN = "mica.notificationLyrics.overlayToken"

    fun displayOptions(context: Context): LyricsDisplayOptions =
        LyricsDisplayOptions(
            splitEnabled = LyricsPreferences.lyricSplitEnabled(context),
            bilingualMode = LyricsPreferences.lyricsBilingualDisplayMode(context),
        )

    fun signature(songId: String, lyricIndex: Int, displayLine: String): String =
        "$songId:$lyricIndex:$displayLine"

    fun metadataWithLyric(
        song: Song,
        line: String,
        base: MediaMetadata,
        overlayToken: String? = null,
    ): MediaMetadata? {
        val displayLine = line.trim().takeIf { it.isNotEmpty() } ?: return null
        return base.buildUpon()
            .setTitle(displayLine)
            .setDisplayTitle(displayLine)
            .setArtist(LyricsDisplayProjection.subtitle(song.title, song.artist))
            .setExtras(ensureCanonicalTitleExtras(base.extras, song.title).apply {
                if (overlayToken == null) remove(OVERLAY_TOKEN) else putString(OVERLAY_TOKEN, overlayToken)
            })
            .build()
    }

    fun defaultPlaybackMetadata(song: Song, base: MediaMetadata): MediaMetadata =
        base.buildUpon()
            .setTitle(song.title)
            .setDisplayTitle(song.title)
            .setArtist(song.artist)
            .setExtras(ensureCanonicalTitleExtras(base.extras, song.title).apply { remove(OVERLAY_TOKEN) })
            .build()

    internal fun overlayToken(metadata: MediaMetadata): String? =
        metadata.extras?.getString(OVERLAY_TOKEN)

    internal fun ensureCanonicalTitleExtras(extras: Bundle?, songTitle: String): Bundle {
        val bundle = Bundle(extras ?: Bundle.EMPTY)
        if (songTitle.isNotBlank()) {
            bundle.putString(SongMediaItemCodec.canonicalTitleExtraKey(), songTitle)
        }
        return bundle
    }
}
