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
import com.mica.music.media.usb.shadow.PlaybackTopologyMutationReservation
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackStack
import com.mica.music.media.usb.shadow.UsbExclusiveAuthorityObservation
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
    private val topologyProvenance: PlaybackTopologyMedia3Provenance =
        PlaybackTopologyMedia3Provenance(playbackStack.currentTopologyToken()),
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
        val safeIndex = startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        val targetMediaId = mediaItems.getOrNull(safeIndex)?.mediaId
        val reservation = reserveTopologyMutation(
            "set-media-items",
            targetMediaId = targetMediaId,
            queueClear = mediaItems.isEmpty(),
        ) ?: return
        val taggedItems = topologyProvenance.tagForProducer(mediaItems, reservation.producerToken)
        if (!prepareTopologyProvenance(reservation, taggedItems)) return
        val navigationEpoch = prepareQueueMutation(
            targetMediaId = taggedItems.getOrNull(safeIndex)?.mediaId,
            targetWindowIndex = safeIndex.takeIf { taggedItems.isNotEmpty() },
            requestedPlaying = exoPlayer.playWhenReady,
            seam = "set-media-items",
            topologyReservation = reservation,
        )
        try {
            super.setMediaItems(taggedItems, startIndex, startPositionMs)
        } catch (error: Throwable) {
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "set-media-items-error") }
            markTopologyDispatchUncertain(reservation, "set-media-items-exception")
            throw error
        }
        check(commitTopologyMutation(reservation)) { "USB playback topology commit failed after setMediaItems" }
        queueRevision++
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        setMediaItems(listOf(mediaItem), 0, 0L)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        setMediaItems(listOf(mediaItem), 0, startPositionMs)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        setMediaItems(mediaItems, 0, 0L)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        if (resetPosition || mediaItems.isEmpty()) {
            setMediaItems(mediaItems, 0, 0L)
        } else {
            val index = exoPlayer.currentMediaItemIndex.coerceIn(0, mediaItems.lastIndex)
            setMediaItems(mediaItems, index, exoPlayer.currentPosition.coerceAtLeast(0L))
        }
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        setMediaItems(
            listOf(mediaItem),
            0,
            if (resetPosition) 0L else exoPlayer.currentPosition.coerceAtLeast(0L),
        )
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        addMediaItem(exoPlayer.mediaItemCount, mediaItem)
    }

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        addMediaItems(exoPlayer.mediaItemCount, mediaItems)
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        require(index >= 0)
        if (mediaItems.isEmpty()) return
        val current = currentQueueItems()
        val insertionIndex = index.coerceAtMost(current.size)
        val expected = current.toMutableList().also { it.addAll(insertionIndex, mediaItems) }
        val oldCurrentIndex = exoPlayer.currentMediaItemIndex
        val replacementIndex = when {
            current.isEmpty() || oldCurrentIndex < 0 -> 0
            insertionIndex <= oldCurrentIndex -> oldCurrentIndex + mediaItems.size
            else -> oldCurrentIndex
        }.coerceIn(0, expected.lastIndex)
        dispatchCanonicalTopologyReplacement(
            seam = "add-media-items",
            expectedItems = expected,
            replacementIndex = replacementIndex,
            replacementPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
        )
        queueRevision++
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        addMediaItems(index, listOf(mediaItem))
    }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        if (currentIndex != newIndex) moveMediaItems(currentIndex, currentIndex + 1, newIndex)
    }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        require(fromIndex >= 0 && fromIndex <= toIndex && newIndex >= 0)
        val current = currentQueueItems()
        val effectiveToIndex = toIndex.coerceAtMost(current.size)
        if (fromIndex >= current.size || fromIndex == effectiveToIndex) return
        val movedCount = effectiveToIndex - fromIndex
        val effectiveNewIndex = newIndex.coerceAtMost(current.size - movedCount)
        if (fromIndex == effectiveNewIndex) return
        val indexed = current.mapIndexed { index, item -> index to item }.toMutableList()
        val moved = indexed.subList(fromIndex, effectiveToIndex).toList()
        indexed.subList(fromIndex, effectiveToIndex).clear()
        indexed.addAll(effectiveNewIndex, moved)
        val oldCurrentIndex = exoPlayer.currentMediaItemIndex
        val replacementIndex = indexed.indexOfFirst { it.first == oldCurrentIndex }
            .takeIf { it >= 0 }
            ?: 0
        dispatchCanonicalTopologyReplacement(
            seam = "move-media-items",
            expectedItems = indexed.map { it.second },
            replacementIndex = replacementIndex,
            replacementPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
        )
        queueRevision++
    }

    override fun removeMediaItem(index: Int) {
        removeMediaItems(index, index + 1)
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        require(fromIndex >= 0 && toIndex >= fromIndex)
        val current = currentQueueItems()
        val effectiveToIndex = toIndex.coerceAtMost(current.size)
        if (fromIndex >= current.size || fromIndex == effectiveToIndex) return
        val expected = current.toMutableList().also {
            it.subList(fromIndex, effectiveToIndex).clear()
        }
        val oldCurrentIndex = exoPlayer.currentMediaItemIndex
        val removedCurrent = oldCurrentIndex in fromIndex until effectiveToIndex
        val replacementIndex = Media3PlaylistIndexSemantics.currentIndexAfterRemove(
            queueSize = current.size,
            currentIndex = oldCurrentIndex,
            fromIndex = fromIndex,
            effectiveToIndex = effectiveToIndex,
        )
        val targetMediaId = if (removedCurrent && expected.isNotEmpty()) expected[replacementIndex].mediaId else null
        dispatchCanonicalTopologyReplacement(
            seam = "remove-media-items",
            expectedItems = expected,
            replacementIndex = replacementIndex,
            replacementPositionMs = if (removedCurrent) 0L else exoPlayer.currentPosition.coerceAtLeast(0L),
            targetMediaId = targetMediaId,
            targetWindowIndex = replacementIndex.takeIf { targetMediaId != null },
            queueClear = expected.isEmpty(),
        )
        queueRevision++
    }

    override fun clearMediaItems() {
        val reservation = reserveTopologyMutation("clear-media-items", queueClear = true) ?: return
        if (!prepareTopologyProvenance(reservation, emptyList())) return
        check(playbackStack.stageTopologyQueueClear(reservation)) {
            abortTopologyMutation(reservation, "queue-clear-stage-rejected")
            "USB playback protocol rejected queue clear topology stage"
        }
        try {
            super.clearMediaItems()
        } catch (error: Throwable) {
            markTopologyDispatchUncertain(reservation, "clear-media-items-exception")
            throw error
        }
        check(commitTopologyMutation(reservation)) { "USB playback topology commit failed after clearMediaItems" }
        queueRevision++
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        replaceMediaItems(index, index + 1, listOf(mediaItem))
    }

    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) {
        require(fromIndex >= 0 && toIndex >= fromIndex)
        val current = currentQueueItems()
        if (fromIndex > current.size) return
        val effectiveToIndex = toIndex.coerceAtMost(current.size)
        if (fromIndex == effectiveToIndex && mediaItems.isEmpty()) return
        val expected = current.toMutableList().also {
            it.subList(fromIndex, effectiveToIndex).clear()
            it.addAll(fromIndex, mediaItems)
        }
        if (topologyProvenance.queuePlaybackSourceEquivalent(current, expected)) {
            expected.indices.forEach { index ->
                val refreshed = topologyProvenance.preserveProducerTag(current[index], expected[index])
                if (refreshed != current[index]) super.replaceMediaItem(index, refreshed)
            }
            queueRevision++
            return
        }

        val oldCurrentIndex = exoPlayer.currentMediaItemIndex
        val replacedCurrent = oldCurrentIndex in fromIndex until effectiveToIndex
        val removedCount = effectiveToIndex - fromIndex
        val replacementIndex = when {
            expected.isEmpty() -> 0
            current.isEmpty() -> 0
            replacedCurrent && mediaItems.isNotEmpty() ->
                fromIndex + (oldCurrentIndex - fromIndex).coerceAtMost(mediaItems.lastIndex)
            replacedCurrent -> fromIndex.coerceAtMost(expected.lastIndex)
            oldCurrentIndex >= effectiveToIndex -> oldCurrentIndex - removedCount + mediaItems.size
            oldCurrentIndex >= 0 -> oldCurrentIndex
            else -> 0
        }.coerceIn(0, (expected.size - 1).coerceAtLeast(0))
        val targetMediaId = if (replacedCurrent && expected.isNotEmpty()) expected[replacementIndex].mediaId else null
        dispatchCanonicalTopologyReplacement(
            seam = "replace-media-items",
            expectedItems = expected,
            replacementIndex = replacementIndex,
            replacementPositionMs = if (replacedCurrent) 0L else exoPlayer.currentPosition.coerceAtLeast(0L),
            targetMediaId = targetMediaId,
            targetWindowIndex = replacementIndex.takeIf { targetMediaId != null },
            queueClear = expected.isEmpty(),
        )
        queueRevision++
    }

    fun startExoPlayback(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ) {
        val safeIndex = startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        val currentItems = currentQueueItems()
        if (topologyProvenance.queuePlaybackSourceEquivalent(currentItems, mediaItems)) {
            currentItems.indices.forEach { index ->
                val refreshed = topologyProvenance.preserveProducerTag(currentItems[index], mediaItems[index])
                if (refreshed != currentItems[index]) super.replaceMediaItem(index, refreshed)
            }
            startExistingItem(safeIndex, startPositionMs, playWhenReady)
            return
        }

        if (!publishProtocolIntent(playWhenReady)) return
        val targetId = mediaItems.getOrNull(safeIndex)?.mediaId
        val currentId = currentMediaItem?.mediaId
        val switchingItem = currentId != null && targetId != null && targetId != currentId
        val reservation = reserveTopologyMutation(
            "start-exo-playback",
            targetMediaId = targetId,
            queueClear = mediaItems.isEmpty(),
        ) ?: return
        val taggedItems = topologyProvenance.tagForProducer(mediaItems, reservation.producerToken)
        if (!prepareTopologyProvenance(reservation, taggedItems)) return
        val navigationEpoch = prepareQueueMutation(
            targetId,
            safeIndex.takeIf { taggedItems.isNotEmpty() },
            playWhenReady,
            "start-exo-playback",
            topologyReservation = reservation,
        )
        val prevItems = mediaItemCount
        val setStartedNs = SystemClock.elapsedRealtimeNanos()
        try {
            if (switchingItem && exoPlayer.playbackState != Player.STATE_IDLE) {
                exoPlayer.stop()
            }
            beforePlaybackStart()
        } catch (error: Throwable) {
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "start-exo-playback-pre-dispatch-error") }
            abortTopologyMutation(reservation, "pre-dispatch-error")
            throw error
        }
        try {
            exoPlayer.setMediaItems(taggedItems, safeIndex, startPositionMs.coerceAtLeast(0L))
        } catch (error: Throwable) {
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "start-exo-playback-dispatch-error") }
            markTopologyDispatchUncertain(reservation, "start-exo-playback-set-items-exception")
            throw error
        }
        check(commitTopologyMutation(reservation)) {
            "USB playback topology commit failed after startExoPlayback setMediaItems"
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playWhenReady
        val setMs = (SystemClock.elapsedRealtimeNanos() - setStartedNs) / 1_000_000.0
        DiagnosticLog.event(
            "QueueSync",
            "exo-setMediaItems durMs=${String.format(Locale.US, "%.2f", setMs)} " +
                "items=${taggedItems.size} index=$safeIndex switching=$switchingItem " +
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
        val currentItems = currentQueueItems()
        if (topologyProvenance.queuePlaybackSourceEquivalent(currentItems, mediaItems)) {
            currentItems.indices.forEach { index ->
                val refreshed = topologyProvenance.preserveProducerTag(currentItems[index], mediaItems[index])
                if (refreshed != currentItems[index]) super.replaceMediaItem(index, refreshed)
            }
            selectExistingWithoutPlayback(safeIndex, startPositionMs)
            return
        }

        val targetId = mediaItems[safeIndex].mediaId
        if (!publishProtocolIntent(false)) return
        val reservation = reserveTopologyMutation(
            "select-without-playback",
            targetMediaId = targetId,
        ) ?: return
        val taggedItems = topologyProvenance.tagForProducer(mediaItems, reservation.producerToken)
        if (!prepareTopologyProvenance(reservation, taggedItems)) return
        val navigationEpoch = prepareQueueMutation(
            targetMediaId = targetId,
            targetWindowIndex = safeIndex,
            requestedPlaying = false,
            seam = "select-without-playback",
            topologyReservation = reservation,
        )
        DirectDsdSeekDiscontinuityCoordinator.cancelForPlaybackPause()
        try {
            exoPlayer.pause()
            if (exoPlayer.playbackState != Player.STATE_IDLE) exoPlayer.stop()
        } catch (error: Throwable) {
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "select-without-playback-pre-dispatch-error") }
            abortTopologyMutation(reservation, "pre-dispatch-error")
            throw error
        }
        try {
            exoPlayer.setMediaItems(taggedItems, safeIndex, startPositionMs.coerceAtLeast(0L))
        } catch (error: Throwable) {
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "select-without-playback-dispatch-error") }
            markTopologyDispatchUncertain(reservation, "select-without-playback-set-items-exception")
            throw error
        }
        check(commitTopologyMutation(reservation)) {
            "USB playback topology commit failed after selectWithoutPlayback setMediaItems"
        }
        exoPlayer.playWhenReady = false
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
        topologyReservation: PlaybackTopologyMutationReservation? = null,
    ): ManualNavigationTransitionEpoch {
        val accepted = if (topologyReservation == null) {
            playbackStack.beginManualNavigation(
                targetMediaId,
                seam,
                targetWindowIndex,
                expectedTargetPeriodUid,
            ) != null
        } else {
            playbackStack.stageTopologyManualNavigation(
                topologyReservation,
                targetMediaId,
                targetWindowIndex,
                expectedTargetPeriodUid,
            )
        }
        check(accepted) {
            "USB playback protocol rejected manual navigation before Exo dispatch: $seam/$targetMediaId"
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
                "targetPeriodKnown=${epoch.expectedTargetPeriodUid != null} " +
                "topologyReserved=${topologyReservation != null}",
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
        topologyReservation: PlaybackTopologyMutationReservation? = null,
    ): ManualNavigationTransitionEpoch? = try {
        if (targetMediaId == null) {
            val accepted = topologyReservation?.let(playbackStack::stageTopologyQueueClear)
                ?: playbackStack.beginQueueClear()
            check(accepted) {
                "USB playback protocol rejected empty queue mutation before Exo dispatch: $seam"
            }
            null
        } else {
            check(targetMediaId.isNotBlank()) {
                "USB playback protocol rejected unbound queue destination before Exo dispatch: $seam"
            }
            publishManualNavigation(
                targetMediaId,
                requestedPlaying,
                seam,
                targetWindowIndex = targetWindowIndex,
                topologyReservation = topologyReservation,
            )
        }
    } catch (error: Throwable) {
        topologyReservation?.let { abortTopologyMutation(it, "queue-mutation-prepare-error") }
        throw error
    }

    private fun currentQueueItems(): List<MediaItem> =
        List(exoPlayer.mediaItemCount, exoPlayer::getMediaItemAt)

    private fun dispatchCanonicalTopologyReplacement(
        seam: String,
        expectedItems: List<MediaItem>,
        replacementIndex: Int,
        replacementPositionMs: Long,
        targetMediaId: String? = null,
        targetWindowIndex: Int? = null,
        queueClear: Boolean = expectedItems.isEmpty(),
    ) {
        val reservation = reserveTopologyMutation(seam, targetMediaId, queueClear) ?: return
        val taggedItems = topologyProvenance.tagForProducer(expectedItems, reservation.producerToken)
        if (!prepareTopologyProvenance(reservation, taggedItems)) return
        val navigationEpoch = when {
            queueClear -> prepareQueueMutation(
                targetMediaId = null,
                requestedPlaying = exoPlayer.playWhenReady,
                seam = seam,
                topologyReservation = reservation,
            )
            targetMediaId != null -> prepareQueueMutation(
                targetMediaId = targetMediaId,
                targetWindowIndex = targetWindowIndex,
                requestedPlaying = exoPlayer.playWhenReady,
                seam = seam,
                topologyReservation = reservation,
            )
            else -> null
        }
        try {
            if (queueClear) {
                super.clearMediaItems()
            } else {
                super.setMediaItems(
                    taggedItems,
                    replacementIndex.coerceIn(0, taggedItems.lastIndex),
                    replacementPositionMs.coerceAtLeast(0L),
                )
            }
        } catch (error: Throwable) {
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "$seam-error") }
            markTopologyDispatchUncertain(reservation, "$seam-exception")
            throw error
        }
        check(commitTopologyMutation(reservation)) {
            "USB playback topology commit failed after $seam"
        }
    }

    private fun reserveTopologyMutation(
        seam: String,
        targetMediaId: String? = null,
        queueClear: Boolean = false,
    ): PlaybackTopologyMutationReservation? {
        val reservation = playbackStack.preparePlaybackTopologyMutation(seam, targetMediaId, queueClear)
        if (reservation == null) {
            DiagnosticLog.event("UsbExclusiveProtocol", "topology-prepare-rejected seam=$seam")
        }
        return reservation
    }

    private fun prepareTopologyProvenance(
        reservation: PlaybackTopologyMutationReservation,
        expectedItems: List<MediaItem>,
    ): Boolean {
        if (topologyProvenance.prepare(reservation, expectedItems)) return true
        abortTopologyMutation(reservation, "producer-provenance-prepare-rejected")
        return false
    }

    private fun commitTopologyMutation(reservation: PlaybackTopologyMutationReservation): Boolean {
        check(topologyProvenance.canCommit(reservation)) {
            "Playback topology producer reservation changed after Media3 dispatch: ${reservation.seam}"
        }
        val dispatched = playbackStack.markPlaybackTopologyDispatchSucceeded(reservation)
        check(dispatched is UsbExclusiveAuthorityObservation.Accepted) {
            "USB playback topology dispatch marker rejected after successful Media3 call: ${reservation.seam}/$dispatched"
        }
        val protocolResult = playbackStack.commitPlaybackTopologyMutation(reservation)
        check(protocolResult is UsbExclusiveAuthorityObservation.Accepted) {
            "USB playback topology monotonic commit rejected after successful Media3 call: ${reservation.seam}/$protocolResult"
        }
        check(topologyProvenance.commit(reservation)) {
            "Playback topology producer commit changed after protocol commit: ${reservation.seam}"
        }
        return true
    }

    private fun abortTopologyMutation(
        reservation: PlaybackTopologyMutationReservation,
        reason: String,
    ) {
        topologyProvenance.abort(reservation)
        playbackStack.abortPlaybackTopologyMutation(reservation, reason)
    }

    private fun markTopologyDispatchUncertain(
        reservation: PlaybackTopologyMutationReservation,
        reason: String,
    ) {
        playbackStack.markPlaybackTopologyDispatchUncertain(reservation, reason)
        DiagnosticLog.event(
            "UsbExclusiveProtocol",
            "topology-dispatch-uncertain seam=${reservation.seam} reason=$reason",
        )
    }
}
