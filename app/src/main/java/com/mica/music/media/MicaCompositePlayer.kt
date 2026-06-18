package com.mica.music.media

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

data class PlaybackQueueSnapshot(
    val items: List<MediaItem>,
    val currentIndex: Int,
    val revision: Long,
) {
    val currentItem: MediaItem?
        get() = items.getOrNull(currentIndex)
}

/**
 * Thin [ForwardingPlayer] over Exo: queue snapshot for the service coordinator and
 * command routing for play/seek/navigation.
 */
@UnstableApi
class MicaCompositePlayer(
    private val exoPlayer: ExoPlayer,
) : ForwardingPlayer(exoPlayer) {

    internal var playbackCoordinator: ServicePlaybackEngineCoordinator? = null

    private var queueRevision: Long = 0L

    override fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
        queueRevision++
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        super.addMediaItem(index, mediaItem)
        queueRevision++
    }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        super.moveMediaItem(currentIndex, newIndex)
        queueRevision++
    }

    override fun removeMediaItem(index: Int) {
        super.removeMediaItem(index)
        queueRevision++
    }

    override fun clearMediaItems() {
        super.clearMediaItems()
        queueRevision++
    }

    fun startExoPlayback(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ) {
        val safeIndex = startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        val targetId = mediaItems.getOrNull(safeIndex)?.mediaId
        val switchingItem = targetId != null && targetId != currentMediaItem?.mediaId
        if (switchingItem && exoPlayer.playbackState != Player.STATE_IDLE) {
            exoPlayer.stop()
        }
        exoPlayer.setMediaItems(mediaItems, safeIndex, startPositionMs.coerceAtLeast(0L))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playWhenReady
    }

    fun playbackQueueSnapshot(): PlaybackQueueSnapshot {
        val items = List(exoPlayer.mediaItemCount, exoPlayer::getMediaItemAt)
        val index = if (items.isEmpty()) {
            0
        } else {
            currentMediaItemIndex.coerceIn(0, items.lastIndex)
        }
        return PlaybackQueueSnapshot(items, index, queueRevision)
    }

    fun setPlaylistIndex(index: Int) {
        if (mediaItemCount == 0) return
        val safe = index.coerceIn(0, mediaItemCount - 1)
        if (safe == currentMediaItemIndex) return
        seekTo(safe, currentPosition)
    }

    fun playExoDirect() {
        if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
        exoPlayer.play()
    }

    fun pauseExoDirect() {
        exoPlayer.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) {
            playbackCoordinator?.playCurrent() ?: super.setPlayWhenReady(true)
        } else {
            super.setPlayWhenReady(false)
        }
    }

    override fun play() {
        playbackCoordinator?.playCurrent() ?: super.play()
    }

    override fun pause() {
        super.pause()
    }

    override fun seekTo(positionMs: Long) {
        super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        playbackCoordinator?.onSelectMediaItem(mediaItemIndex, positionMs)
            ?: super.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekToNextMediaItem() {
        playbackCoordinator?.onSkipToNext() ?: super.seekToNextMediaItem()
    }

    override fun seekToPreviousMediaItem() {
        playbackCoordinator?.onSkipToPrevious() ?: super.seekToPreviousMediaItem()
    }

    override fun seekToPrevious() {
        playbackCoordinator?.onSkipToPrevious() ?: super.seekToPrevious()
    }

    override fun seekToNext() {
        playbackCoordinator?.onSkipToNext() ?: super.seekToNext()
    }
}
