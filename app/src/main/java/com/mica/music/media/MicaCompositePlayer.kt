package com.mica.music.media

import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mica.music.util.DiagnosticLog
import java.util.Locale

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
    private val beforePlaybackStart: () -> Unit = {},
) : ForwardingPlayer(exoPlayer) {

    private var requestedVolume = 1f
    private var replayGainVolume = 1f

    override fun setVolume(volume: Float) {
        requestedVolume = volume.coerceIn(0f, 1f)
        exoPlayer.volume = requestedVolume * replayGainVolume
    }

    override fun getVolume(): Float = requestedVolume

    fun setReplayGainVolume(volume: Float) {
        replayGainVolume = volume.coerceIn(0f, 1f)
        exoPlayer.volume = requestedVolume * replayGainVolume
    }

    internal var playbackCoordinator: ServicePlaybackEngineCoordinator? = null
    internal var onPlaybackIntentChanged: ((Boolean) -> Unit)? = null

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
        val prevItems = mediaItemCount
        val setStartedNs = SystemClock.elapsedRealtimeNanos()
        beforePlaybackStart()
        exoPlayer.setMediaItems(mediaItems, safeIndex, startPositionMs.coerceAtLeast(0L))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playWhenReady
        val setMs = (SystemClock.elapsedRealtimeNanos() - setStartedNs) / 1_000_000.0
        DiagnosticLog.event(
            "QueueSync",
            "exo-setMediaItems durMs=${String.format(Locale.US, "%.2f", setMs)} " +
                "items=${mediaItems.size} index=$safeIndex switching=$switchingItem " +
                "prevItems=$prevItems",
        )
    }

    /** Switches within the existing Exo playlist without rebuilding its timeline. */
    fun startExistingItem(
        index: Int,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ) {
        if (exoPlayer.mediaItemCount == 0) return
        val safeIndex = index.coerceIn(0, exoPlayer.mediaItemCount - 1)
        if (!playWhenReady) exoPlayer.playWhenReady = false
        beforePlaybackStart()
        exoPlayer.seekTo(safeIndex, positionMs.coerceAtLeast(0L))
        if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
        exoPlayer.playWhenReady = playWhenReady
        DiagnosticLog.event(
            "QueueSync",
            "exo-seek-existing index=$safeIndex items=${exoPlayer.mediaItemCount}",
        )
    }

    /** Selects an unsupported item already present in Exo without rebuilding the playlist. */
    fun selectExistingWithoutPlayback(index: Int, positionMs: Long = 0L) {
        if (exoPlayer.mediaItemCount == 0) return
        val safeIndex = index.coerceIn(0, exoPlayer.mediaItemCount - 1)
        exoPlayer.pause()
        if (exoPlayer.playbackState != Player.STATE_IDLE) exoPlayer.stop()
        exoPlayer.seekTo(safeIndex, positionMs.coerceAtLeast(0L))
        exoPlayer.playWhenReady = false
    }

    /** Selects an unsupported item as the current item without preparing or playing it. */
    fun selectWithoutPlayback(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long = 0L,
    ) {
        if (mediaItems.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, mediaItems.lastIndex)
        exoPlayer.pause()
        if (exoPlayer.playbackState != Player.STATE_IDLE) exoPlayer.stop()
        exoPlayer.setMediaItems(mediaItems, safeIndex, startPositionMs.coerceAtLeast(0L))
        exoPlayer.playWhenReady = false
        queueRevision++
    }

    /** Stops, seeks, and re-prepares playback to flush processor state (does not rebuild the sink). */
    fun flushPlaybackPipeline(positionMs: Long, resumePlayback: Boolean) {
        exoPlayer.playWhenReady = false
        exoPlayer.stop()
        exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = resumePlayback
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
        exoPlayer.seekTo(safe, currentPosition)
    }

    fun playExoDirect() {
        if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
        exoPlayer.play()
    }

    fun pauseExoDirect() {
        exoPlayer.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        onPlaybackIntentChanged?.invoke(playWhenReady)
        if (playWhenReady) {
            playbackCoordinator?.playCurrent() ?: super.setPlayWhenReady(true)
        } else {
            super.setPlayWhenReady(false)
        }
    }

    override fun play() {
        onPlaybackIntentChanged?.invoke(true)
        playbackCoordinator?.playCurrent() ?: super.play()
    }

    override fun pause() {
        onPlaybackIntentChanged?.invoke(false)
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
