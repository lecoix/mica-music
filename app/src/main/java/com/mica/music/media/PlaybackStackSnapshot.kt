package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

/** In-memory intent transferred across one full Exo stack rebuild; never persisted by this seam. */
internal data class PlaybackStackSnapshot(
    val mediaItems: List<MediaItem>,
    val currentIndex: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val repeatMode: Int,
    val shuffleModeEnabled: Boolean,
    val playbackParameters: PlaybackParameters,
) {
    fun restoreInto(player: Player, resumePlayback: Boolean = playWhenReady) {
        player.playWhenReady = false
        player.repeatMode = repeatMode
        player.shuffleModeEnabled = shuffleModeEnabled
        player.playbackParameters = playbackParameters
        if (mediaItems.isEmpty()) return
        val safeIndex = currentIndex.coerceIn(0, mediaItems.lastIndex)
        player.setMediaItems(mediaItems, safeIndex, positionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = resumePlayback
    }

    companion object {
        fun capture(player: Player): PlaybackStackSnapshot = PlaybackStackSnapshot(
            mediaItems = List(player.mediaItemCount) { player.getMediaItemAt(it) },
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = player.playWhenReady,
            repeatMode = player.repeatMode,
            shuffleModeEnabled = player.shuffleModeEnabled,
            playbackParameters = player.playbackParameters,
        )
    }
}
