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
        val reservation = reserveTopologyMutation("set-media-items") ?: return
        val taggedItems = topologyProvenance.tagForProducer(mediaItems, reservation.producerToken)
        if (!prepareTopologyProvenance(reservation, taggedItems)) return
        val safeIndex = startIndex.coerceIn(0, (taggedItems.size - 1).coerceAtLeast(0))
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
            abortTopologyMutation(reservation, "exo-dispatch-error")
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

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        val reservation = reserveTopologyMutation("add-media-item") ?: return
        val tagged = topologyProvenance.tagForProducer(mediaItem, reservation.producerToken)
        val expected = currentQueueItems().toMutableList()
        try {
            expected.add(index, tagged)
        } catch (error: Throwable) {
            abortTopologyMutation(reservation, "invalid-index")
            throw error
        }
        if (!prepareTopologyProvenance(reservation, expected)) return
        try {
            super.addMediaItem(index, tagged)
        } catch (error: Throwable) {
            abortTopologyMutation(reservation, "exo-dispatch-error")
            throw error
        }
        check(commitTopologyMutation(reservation)) { "USB playback topology commit failed after addMediaItem" }
        queueRevision++
    }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        val current = currentQueueItems()
        val expected = current.toMutableList()
        try {
            val moved = expected.removeAt(currentIndex)
            expected.add(newIndex, moved)
        } catch (error: Throwable) {
            throw error
        }
        val reservation = reserveTopologyMutation("move-media-item") ?: return
        if (!prepareTopologyProvenance(reservation, expected)) return
        try {
            super.moveMediaItem(currentIndex, newIndex)
        } catch (error: Throwable) {
            abortTopologyMutation(reservation, "exo-dispatch-error")
            throw error
        }
        check(commitTopologyMutation(reservation)) { "USB playback topology commit failed after moveMediaItem" }
        queueRevision++
    }

    override fun removeMediaItem(index: Int) {
        val current = currentQueueItems()
        val expected = current.toMutableList()
        try {
            expected.removeAt(index)
        } catch (error: Throwable) {
            throw error
        }
        val reservation = reserveTopologyMutation("remove-media-item") ?: return
        if (!prepareTopologyProvenance(reservation, expected)) return
        val navigationEpoch = if (index == exoPlayer.currentMediaItemIndex) {
            val target = when {
                expected.isEmpty() -> null
                index < expected.size -> index to expected[index]
                else -> expected.lastIndex to expected.last()
            }
            prepareQueueMutation(
                targetMediaId = target?.second?.mediaId,
                targetWindowIndex = target?.first,
                requestedPlaying = exoPlayer.playWhenReady,
                seam = "remove-current-media-item",
                topologyReservation = reservation,
            )
        } else {
            null
        }
        try {
            super.removeMediaItem(index)
        } catch (error: Throwable) {
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "remove-media-item-error") }
            abortTopologyMutation(reservation, "exo-dispatch-error")
            throw error
        }
        check(commitTopologyMutation(reservation)) { "USB playback topology commit failed after removeMediaItem" }
        queueRevision++
    }

    override fun clearMediaItems() {
        val reservation = reserveTopologyMutation("clear-media-items") ?: return
        if (!prepareTopologyProvenance(reservation, emptyList())) return
        check(playbackStack.stageTopologyQueueClear(reservation)) {
            abortTopologyMutation(reservation, "queue-clear-stage-rejected")
            "USB playback protocol rejected queue clear topology stage"
        }
        try {
            super.clearMediaItems()
        } catch (error: Throwable) {
            abortTopologyMutation(reservation, "exo-dispatch-error")
            throw error
        }
        check(commitTopologyMutation(reservation)) { "USB playback topology commit failed after clearMediaItems" }
        queueRevision++
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        val previous = exoPlayer.getMediaItemAt(index)
        if (topologyProvenance.playbackSourceEquivalent(previous, mediaItem)) {
            super.replaceMediaItem(index, topologyProvenance.preserveProducerTag(previous, mediaItem))
            queueRevision++
            return
        }

        val reservation = reserveTopologyMutation("replace-media-item") ?: return
        val tagged = topologyProvenance.tagForProducer(mediaItem, reservation.producerToken)
        val expected = currentQueueItems().toMutableList().also { it[index] = tagged }
        if (!prepareTopologyProvenance(reservation, expected)) return
        val navigationEpoch = if (index == exoPlayer.currentMediaItemIndex) {
            prepareQueueMutation(
                tagged.mediaId,
                index,
                exoPlayer.playWhenReady,
                "replace-current-media-item",
                topologyReservation = reservation,
            )
        } else {
            null
        }
        try {
            super.replaceMediaItem(index, tagged)
        } catch (error: Throwable) {
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "replace-media-item-error") }
            abortTopologyMutation(reservation, "exo-dispatch-error")
            throw error
        }
        check(commitTopologyMutation(reservation)) { "USB playback topology commit failed after replaceMediaItem" }
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
        val reservation = reserveTopologyMutation("start-exo-playback") ?: return
        val taggedItems = topologyProvenance.tagForProducer(mediaItems, reservation.producerToken)
        if (!prepareTopologyProvenance(reservation, taggedItems)) return
        val navigationEpoch = prepareQueueMutation(
            targetId,
            safeIndex,
            playWhenReady,
            "start-exo-playback",
            topologyReservation = reservation,
        )
        val prevItems = mediaItemCount
        val setStartedNs = SystemClock.elapsedRealtimeNanos()
        var dispatched = false
        try {
            if (switchingItem && exoPlayer.playbackState != Player.STATE_IDLE) {
                exoPlayer.stop()
            }
            beforePlaybackStart()
            exoPlayer.setMediaItems(taggedItems, safeIndex, startPositionMs.coerceAtLeast(0L))
            dispatched = true
            check(commitTopologyMutation(reservation)) {
                "USB playback topology commit failed after startExoPlayback setMediaItems"
            }
            exoPlayer.prepare()
            exoPlayer.playWhenReady = playWhenReady
        } catch (error: Throwable) {
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "start-exo-playback-error") }
            if (!dispatched) abortTopologyMutation(reservation, "exo-dispatch-error")
            throw error
        }
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
        val targetId = mediaItems[safeIndex].mediaId
        if (!publishProtocolIntent(false)) return
        val reservation = reserveTopologyMutation("select-without-playback") ?: return
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
        var dispatched = false
        try {
            exoPlayer.pause()
            if (exoPlayer.playbackState != Player.STATE_IDLE) exoPlayer.stop()
            exoPlayer.setMediaItems(taggedItems, safeIndex, startPositionMs.coerceAtLeast(0L))
            dispatched = true
            check(commitTopologyMutation(reservation)) {
                "USB playback topology commit failed after selectWithoutPlayback setMediaItems"
            }
            exoPlayer.playWhenReady = false
        } catch (error: Throwable) {
            navigationEpoch?.let { manualNavigationTransitionBridge.cancel(it.requestId, "select-without-playback-error") }
            if (!dispatched) abortTopologyMutation(reservation, "exo-dispatch-error")
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

    private fun reserveTopologyMutation(seam: String): PlaybackTopologyMutationReservation? {
        val reservation = playbackStack.preparePlaybackTopologyMutation(seam)
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
        if (!topologyProvenance.canCommit(reservation)) {
            DiagnosticLog.event(
                "UsbExclusiveProtocol",
                "topology-producer-precommit-rejected seam=${reservation.seam}",
            )
            abortTopologyMutation(reservation, "producer-precommit-rejected")
            return false
        }
        val protocolResult = playbackStack.commitPlaybackTopologyMutation(reservation)
        if (protocolResult !is UsbExclusiveAuthorityObservation.Accepted) {
            DiagnosticLog.event(
                "UsbExclusiveProtocol",
                "topology-commit-rejected seam=${reservation.seam} result=$protocolResult",
            )
            abortTopologyMutation(reservation, "protocol-commit-rejected")
            return false
        }
        check(topologyProvenance.commit(reservation)) {
            "Playback topology producer commit changed after successful precommit: ${reservation.seam}"
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
}
