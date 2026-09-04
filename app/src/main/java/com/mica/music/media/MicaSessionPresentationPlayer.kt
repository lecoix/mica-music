package com.mica.music.media

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.mica.music.data.Song
import com.mica.music.lyrics.LyricsDisplayProjection

internal interface NotificationLyricsPresentationSink {
    fun publishLyric(song: Song, line: String)
    fun clear()
}

/**
 * EXPERIMENTAL single-session metadata presentation for notification and Bluetooth AVRCP lyrics.
 *
 * The wrapped playback player remains the only queue/control authority. This layer changes only
 * the metadata snapshot exposed by MediaSession, avoiding the second active MediaSessionCompat
 * previously used for NetEase-style car lyrics.
 */
@OptIn(UnstableApi::class)
internal class MicaSessionPresentationPlayer(
    private val sourcePlayer: Player,
) : ForwardingSimpleBasePlayer(sourcePlayer), NotificationLyricsPresentationSink {
    private data class LyricPresentation(
        val mediaId: String,
        val title: String,
        val secondaryText: String,
    )

    private var lyricPresentation: LyricPresentation? = null

    fun wraps(player: Player): Boolean = sourcePlayer === player

    override fun publishLyric(song: Song, line: String) {
        val title = line.trim().takeIf(String::isNotEmpty) ?: return
        val next = LyricPresentation(
            mediaId = song.id,
            title = title,
            secondaryText = LyricsDisplayProjection.subtitle(song.title, song.artist),
        )
        if (lyricPresentation == next) return
        lyricPresentation = next
        invalidateState()
    }

    override fun clear() {
        if (lyricPresentation == null) return
        lyricPresentation = null
        invalidateState()
    }

    override fun getState(): SimpleBasePlayer.State {
        val state = super.getState()
        val currentItem = sourcePlayer.currentMediaItem ?: return state
        val lyric = lyricPresentation?.takeIf { it.mediaId == currentItem.mediaId } ?: return state
        val metadata = state.currentMetadata.buildUpon()
            .setTitle(lyric.title)
            .setDisplayTitle(lyric.title)
            .setArtist(lyric.secondaryText)
            .setSubtitle(lyric.secondaryText)
            .build()
        return state.buildUpon()
            .setPlaylist(state.timeline, state.currentTracks, metadata)
            .build()
    }
}