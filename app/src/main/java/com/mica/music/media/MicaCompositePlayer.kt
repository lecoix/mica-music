package com.mica.music.media

import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mica.music.media.dsd.DirectDsdSeekDiscontinuityCoordinator
import com.mica.music.media.dsd.DirectDsdTrackTransitionCoordinator
import com.mica.music.media.dsd.ManualNavigationTransitionBridge
import com.mica.music.media.dsd.ManualNavigationTransitionEpoch
import com.mica.music.media.dsd.ManualNavigationTimelinePeriodResolver
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackStack
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
class MicaCompositePlayer internal constructor(
    private val exoPlayer: ExoPlayer,
    private val playbackStack: UsbExclusivePlaybackStack,
    private val trackTransitionCoordinator: DirectDsdTrackTransitionCoordinator = DirectDsdTrackTransitionCoordinator(),
    private val manualNavigationTransitionBridge: ManualNavigationTransitionBridge = ManualNavigationTransitionBridge(),
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
        if (!advancePlaybackTopology("set-media-items")) return
        val safeIndex = startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        prepareQueueMutation(
            targetMediaId = mediaItems.getOrNull(safeIndex)?.mediaId,
            targetWindowIndex = safeIndex.takeIf { mediaItems.isNotEmpty() },
            requestedPlaying = exoPlayer.playWhenReady,
            seam = "set-media-items",
        )
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
        queueRevision++
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        setMediaItems(listOf(mediaItem), 0, 0L)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        setMediaItems(listOf(mediaItem), 0, startPositionMs)
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        if (!advancePlaybackTopology("add-media-item")) return
        super.addMediaItem(index, mediaItem)
        queueRevision++
    }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        if (!advancePlaybackTopology("move-media-item")) return
        super.moveMediaItem(currentIndex, newIndex)
        queueRevision++
    }

    override fun removeMediaItem(index: Int) {
        if (!advancePlaybackTopology("remove-media-item")) return
        if (index == exoPlayer.currentMediaItemIndex) {
            val target = runCatching {
                when {
                    index + 1 < exoPlayer.mediaItemCount -> index to exoPlayer.getMediaItemAt(index + 1)
                    index > 0 -> (index - 1) to exoPlayer.getMediaItemAt(index - 1)
                    else -> null
                }
            }.getOrNull()
            prepareQueueMutation(
                targetMediaId = target?.second?.mediaId,
                targetWindowIndex = target?.first,
                requestedPlaying = exoPlayer.playWhenReady,
                seam = "remove-current-media-item",
            )
        }
        super.removeMediaItem(index)
        queueRevision++
    }

    override fun clearMediaItems() {
        if (!advancePlaybackTopology("clear-media-items")) return
        check(playbackStack.beginQueueClear()) {
            "USB playback protocol rejected queue clear before Exo dispatch"
        }
        super.clearMediaItems()
        queueRevision++
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        if (!advancePlaybackTopology("replace-media-item")) return
        if (index == exoPlayer.currentMediaItemIndex) {
            prepareQueueMutation(
                mediaItem.mediaId,
                index,
                exoPlayer.playWhenReady,
                "replace-current-media-item",
            )
        }
        super.replaceMediaItem(index, mediaItem)
        queueRevision++
    }

    fun startExoPlayback(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ) {
        val safeIndex = startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        if (!publishProtocolIntent(playWhenReady)) return
        val targetId = mediaItems.getOrNull(safeIndex)?.mediaId
        val currentId = currentMediaItem?.mediaId
        val switchingItem = currentId != null && targetId != null && targetId != currentId
        if (!advancePlaybackTopology("start-exo-playback")) return
        val navigationEpoch = prepareQueueMutation(targetId, safeIndex, playWhenReady, "start-exo-playback")
        val prevItems = mediaItemCount
        val setStartedNs = SystemClock.elapsedRealtimeNanos()
        try {
            if (switchingItem && exoPlayer.playbackState != Player.STATE_IDLE) {
                exoPlayer.stop()
            }
            beforePlaybackStart()
            exoPlayer.setMediaItems(mediaItems, safeIndex, startPositionMs.coerceAtLeast(0L))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = playWhenReady
        } catch (error: Throwable) {
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "start-exo-playback-error") }
            throw error
        }
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
        val safePositionMs = positionMs.coerceAtLeast(0L)
        if (!publishProtocolIntent(playWhenReady)) return
        val targetId = runCatching { exoPlayer.getMediaItemAt(safeIndex) }.getOrNull()?.mediaId
        val switchingItem = targetId != null &&
            (safeIndex != exoPlayer.currentMediaItemIndex || targetId != exoPlayer.currentMediaItem?.mediaId)
        val navigationEpoch = if (switchingItem) {
            publishManualNavigation(
                targetId,
                playWhenReady,
                "start-existing",
                targetWindowIndex = safeIndex,
                expectedTargetPeriodUid = ManualNavigationTimelinePeriodResolver.resolveSinglePeriodUid(
                    exoPlayer.currentTimeline,
                    safeIndex,
                    targetId,
                ),
            )
        } else {
            null
        }
        if (safeIndex == exoPlayer.currentMediaItemIndex &&
            playbackStack.beginSeek(safePositionMs * 1_000L) == null
        ) {
            DiagnosticLog.event("UsbExclusiveProtocol", "seek-rejected-before-exo targetMs=$safePositionMs")
            return
        }
        if (!playWhenReady) {
            DirectDsdSeekDiscontinuityCoordinator.cancelForPlaybackPause()
            exoPlayer.playWhenReady = false
        }
        beforePlaybackStart()
        val seekIntent = if (
            playWhenReady && exoPlayer.isPlaying && safeIndex == exoPlayer.currentMediaItemIndex
        ) {
            DirectDsdSeekDiscontinuityCoordinator.publishPlayingSeek(safePositionMs)
        } else {
            null
        }
        seekIntent?.let { intent ->
            DiagnosticLog.event(
                "DirectDsdSeek",
                "dispatch seam=start-existing request=${intent.requestId} " +
                    "rendererGeneration=${intent.session.rendererGeneration} " +
                    "sessionGeneration=${intent.session.sessionGeneration} " +
                    "targetSourceUs=${intent.targetSourcePositionUs}",
            )
        }
        try {
            exoPlayer.seekTo(safeIndex, safePositionMs)
        } catch (error: Throwable) {
            seekIntent?.let { DirectDsdSeekDiscontinuityCoordinator.cancelRequest(it.requestId) }
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "start-existing-error") }
            throw error
        }
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
        if (!publishProtocolIntent(false)) return
        val targetId = runCatching { exoPlayer.getMediaItemAt(safeIndex) }.getOrNull()?.mediaId
        val switchingItem = targetId != null &&
            (safeIndex != exoPlayer.currentMediaItemIndex || targetId != exoPlayer.currentMediaItem?.mediaId)
        val navigationEpoch = if (switchingItem) {
            publishManualNavigation(
                targetId,
                requestedPlaying = false,
                seam = "select-existing-without-playback",
                targetWindowIndex = safeIndex,
                expectedTargetPeriodUid = ManualNavigationTimelinePeriodResolver.resolveSinglePeriodUid(
                    exoPlayer.currentTimeline,
                    safeIndex,
                    targetId,
                ),
            )
        } else {
            null
        }
        DirectDsdSeekDiscontinuityCoordinator.cancelForPlaybackPause()
        try {
            exoPlayer.pause()
            if (exoPlayer.playbackState != Player.STATE_IDLE) exoPlayer.stop()
            exoPlayer.seekTo(safeIndex, positionMs.coerceAtLeast(0L))
            exoPlayer.playWhenReady = false
        } catch (error: Throwable) {
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "select-existing-error") }
            throw error
        }
    }

    /** Selects an unsupported item as the current item without preparing or playing it. */
    fun selectWithoutPlayback(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long = 0L,
    ) {
        if (mediaItems.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, mediaItems.lastIndex)
        val targetId = mediaItems[safeIndex].mediaId
        if (!publishProtocolIntent(false)) return
        if (!advancePlaybackTopology("select-without-playback")) return
        val navigationEpoch = prepareQueueMutation(
            targetMediaId = targetId,
            targetWindowIndex = safeIndex,
            requestedPlaying = false,
            seam = "select-without-playback",
        )
        DirectDsdSeekDiscontinuityCoordinator.cancelForPlaybackPause()
        try {
            exoPlayer.pause()
            if (exoPlayer.playbackState != Player.STATE_IDLE) exoPlayer.stop()
            exoPlayer.setMediaItems(mediaItems, safeIndex, startPositionMs.coerceAtLeast(0L))
            exoPlayer.playWhenReady = false
        } catch (error: Throwable) {
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "select-without-playback-error") }
            throw error
        }
        queueRevision++
    }

    /** Stops, seeks, and re-prepares playback to flush processor state (does not rebuild the sink). */
    fun flushPlaybackPipeline(positionMs: Long, resumePlayback: Boolean) {
        DirectDsdSeekDiscontinuityCoordinator.cancelForPlaybackPause()
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
        startExistingItem(safe, currentPosition, playWhenReady)
    }

    fun playExoDirect() {
        if (!publishProtocolIntent(true)) return
        if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
        exoPlayer.play()
    }

    fun pauseExoDirect() {
        if (!publishProtocolIntent(false)) return
        DirectDsdSeekDiscontinuityCoordinator.cancelForPlaybackPause()
        exoPlayer.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!publishProtocolIntent(playWhenReady)) return
        if (!playWhenReady) {
            DirectDsdSeekDiscontinuityCoordinator.cancelForPlaybackPause()
        }
        onPlaybackIntentChanged?.invoke(playWhenReady)
        if (playWhenReady) {
            playbackCoordinator?.playCurrent() ?: super.setPlayWhenReady(true)
        } else {
            super.setPlayWhenReady(false)
        }
    }

    override fun play() {
        if (!publishProtocolIntent(true)) return
        onPlaybackIntentChanged?.invoke(true)
        playbackCoordinator?.playCurrent() ?: super.play()
    }

    override fun pause() {
        if (!publishProtocolIntent(false)) return
        DirectDsdSeekDiscontinuityCoordinator.cancelForPlaybackPause()
        onPlaybackIntentChanged?.invoke(false)
        super.pause()
    }

    override fun seekTo(positionMs: Long) {
        val safePositionMs = positionMs.coerceAtLeast(0L)
        if (playbackStack.beginSeek(safePositionMs * 1_000L) == null) {
            DiagnosticLog.event("UsbExclusiveProtocol", "seek-rejected-before-exo targetMs=$safePositionMs")
            return
        }
        val seekIntent = if (exoPlayer.isPlaying) {
            DirectDsdSeekDiscontinuityCoordinator.publishPlayingSeek(safePositionMs)
        } else {
            null
        }
        seekIntent?.let { intent ->
            DiagnosticLog.event(
                "DirectDsdSeek",
                "dispatch seam=seek-position request=${intent.requestId} " +
                    "rendererGeneration=${intent.session.rendererGeneration} " +
                    "sessionGeneration=${intent.session.sessionGeneration} " +
                    "targetSourceUs=${intent.targetSourcePositionUs}",
            )
        }
        try {
            super.seekTo(safePositionMs)
        } catch (error: Throwable) {
            seekIntent?.let { DirectDsdSeekDiscontinuityCoordinator.cancelRequest(it.requestId) }
            throw error
        }
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        playbackCoordinator?.let {
            it.onSelectMediaItem(mediaItemIndex, positionMs)
            return
        }
        val targetId = runCatching { exoPlayer.getMediaItemAt(mediaItemIndex).mediaId }.getOrNull()
        if (targetId == null) {
            DiagnosticLog.event("UsbExclusiveProtocol", "indexed-seek-rejected-before-exo target=$mediaItemIndex")
            return
        }
        if (targetId != exoPlayer.currentMediaItem?.mediaId) {
            publishManualNavigation(
                targetId,
                playWhenReady,
                "seek-index",
                targetWindowIndex = mediaItemIndex,
                expectedTargetPeriodUid = ManualNavigationTimelinePeriodResolver.resolveSinglePeriodUid(
                    exoPlayer.currentTimeline,
                    mediaItemIndex,
                    targetId,
                ),
            )
        } else if (playbackStack.beginSeek(positionMs.coerceAtLeast(0L) * 1_000L) == null) {
            DiagnosticLog.event("UsbExclusiveProtocol", "indexed-seek-rejected-before-exo target=$mediaItemIndex")
            return
        }
        super.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekToNextMediaItem() {
        playbackCoordinator?.let {
            it.onSkipToNext()
            return
        }
        val targetIndex = exoPlayer.nextMediaItemIndex
        val targetId = runCatching { exoPlayer.getMediaItemAt(targetIndex).mediaId }.getOrNull()
        if (targetId == null) {
            DiagnosticLog.event("UsbExclusiveProtocol", "next-seek-rejected-before-exo")
            return
        }
        publishManualNavigation(targetId, playWhenReady, "next-media-item", targetWindowIndex = targetIndex)
        super.seekToNextMediaItem()
    }

    override fun seekToPreviousMediaItem() {
        playbackCoordinator?.let {
            it.onSkipToPrevious()
            return
        }
        val targetIndex = exoPlayer.previousMediaItemIndex
        val targetId = runCatching { exoPlayer.getMediaItemAt(targetIndex).mediaId }.getOrNull()
        if (targetId == null) {
            DiagnosticLog.event("UsbExclusiveProtocol", "previous-seek-rejected-before-exo")
            return
        }
        publishManualNavigation(targetId, playWhenReady, "previous-media-item", targetWindowIndex = targetIndex)
        super.seekToPreviousMediaItem()
    }

    override fun seekToPrevious() {
        playbackCoordinator?.let {
            it.onSkipToPrevious()
            return
        }
        val targetIndex = exoPlayer.previousMediaItemIndex
        val targetId = runCatching { exoPlayer.getMediaItemAt(targetIndex).mediaId }.getOrNull()
        if (targetId == null) {
            DiagnosticLog.event("UsbExclusiveProtocol", "previous-seek-rejected-before-exo")
            return
        }
        publishManualNavigation(targetId, playWhenReady, "previous", targetWindowIndex = targetIndex)
        super.seekToPrevious()
    }

    override fun seekToNext() {
        playbackCoordinator?.let {
            it.onSkipToNext()
            return
        }
        val targetIndex = exoPlayer.nextMediaItemIndex
        val targetId = runCatching { exoPlayer.getMediaItemAt(targetIndex).mediaId }.getOrNull()
        if (targetId == null) {
            DiagnosticLog.event("UsbExclusiveProtocol", "next-seek-rejected-before-exo")
            return
        }
        publishManualNavigation(targetId, playWhenReady, "next", targetWindowIndex = targetIndex)
        super.seekToNext()
    }

    internal fun abortManualNavigation(reason: String) {
        manualNavigationTransitionBridge.abort(reason)
    }

    private fun publishManualNavigation(
        targetMediaId: String,
        requestedPlaying: Boolean,
        seam: String,
        targetWindowIndex: Int? = null,
        expectedTargetPeriodUid: Any? = null,
    ): ManualNavigationTransitionEpoch {
        if (
            playbackStack.beginManualNavigation(
                targetMediaId,
                seam,
                targetWindowIndex,
                expectedTargetPeriodUid,
            ) == null
        ) {
            error("USB playback protocol rejected manual navigation before Exo dispatch: $seam/$targetMediaId")
        }
        val epoch = manualNavigationTransitionBridge.publish(
            targetMediaId = targetMediaId,
            requestedPlaying = requestedPlaying,
            sourceFamily = trackTransitionCoordinator.snapshot().activeFamily,
            expectedTargetPeriodUid = expectedTargetPeriodUid,
        )
        playbackStack.observeLegacyNavigationCorrelation(epoch.requestId)
        DiagnosticLog.event(
            "TrackNavigation",
            "dispatch seam=$seam request=${epoch.requestId} target=$targetMediaId " +
                "playing=$requestedPlaying source=${epoch.sourceFamily} " +
                "targetPeriodKnown=${epoch.expectedTargetPeriodUid != null}",
        )
        return epoch
    }

    private fun publishProtocolIntent(playing: Boolean): Boolean =
        playbackStack.publishSemanticIntent(playing)

    private fun prepareQueueMutation(
        targetMediaId: String?,
        targetWindowIndex: Int? = null,
        requestedPlaying: Boolean,
        seam: String,
    ): ManualNavigationTransitionEpoch? {
        if (targetMediaId == null) {
            check(playbackStack.beginQueueClear()) {
                "USB playback protocol rejected empty queue mutation before Exo dispatch: $seam"
            }
            return null
        }
        check(targetMediaId.isNotBlank()) {
            "USB playback protocol rejected unbound queue destination before Exo dispatch: $seam"
        }
        return publishManualNavigation(
            targetMediaId,
            requestedPlaying,
            seam,
            targetWindowIndex = targetWindowIndex,
        )
    }

    private fun advancePlaybackTopology(seam: String): Boolean {
        val result = playbackStack.advancePlaybackTopology(seam)
        val accepted = result is com.mica.music.media.usb.shadow.UsbExclusiveAuthorityObservation.Accepted
        if (!accepted) {
            DiagnosticLog.event(
                "UsbExclusiveProtocol",
                "topology-advance-rejected-before-exo seam=$seam result=$result",
            )
        }
        return accepted
    }
}
