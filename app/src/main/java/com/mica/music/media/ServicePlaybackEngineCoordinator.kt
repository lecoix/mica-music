package com.mica.music.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.mica.music.data.AppPreferences
import com.mica.music.data.PlaybackQueueNavigator
import com.mica.music.data.PlaybackQueueMode
import com.mica.music.data.Song
import com.mica.music.util.DiagnosticLog
import kotlin.random.Random

internal class ServicePlaybackEngineCoordinator(
    private val context: Context,
    private val player: MicaCompositePlayer,
    private val requestState: ServicePlaybackRequestState = ServicePlaybackRequestState(),
) : AlacSessionCommandHandler, Player.Listener {

    var onPlaybackFailure: ((PlaybackFailure) -> Unit)? = null

    fun start() {
        player.playbackCoordinator = this
        player.addListener(this)
    }

    fun release() {
        player.removeListener(this)
        player.playbackCoordinator = null
        onPlaybackFailure = null
    }

    fun playCurrent() {
        val item = player.currentMediaItem ?: return
        val song = SongMediaItemCodec.decode(item) ?: run {
            player.playExoDirect()
            return
        }
        val active = requestState.activeRequest
        val failed = requestState.engineState as? PlaybackEngineState.Failed
        if (failed?.request?.songId == song.id &&
            failed.request.sourceRevision == PlaybackSourceRevision.of(song)
        ) {
            onPlaybackFailure?.invoke(failed.failure)
            return
        }
        if (active?.songId == song.id &&
            active.sourceRevision == PlaybackSourceRevision.of(song)
        ) {
            player.playExoDirect()
            return
        }
        start(song, player.currentMediaItemIndex, player.currentPosition)
    }

    override fun onPlay() {
        playCurrent()
    }

    override fun onPause() {
        player.pauseExoDirect()
    }

    override fun onSeekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun onSelectMediaItem(index: Int, positionMs: Long) {
        val override = PendingPlaybackNavigation.consumeNavigationOverride()
        if (override == null) {
            startExistingAt(index, positionMs.coerceAtLeast(0L), player.playWhenReady)
        } else {
            startAt(
                index = index,
                positionMs = positionMs.coerceAtLeast(0L),
                queue = override.queue,
                preferredMediaId = override.targetSongId,
                playWhenReady = player.playWhenReady,
            )
        }
    }

    override fun onSkipToNext() {
        startExistingAt(
            resolveNextIndex(manual = true),
            playWhenReady = player.playWhenReady,
        )
    }

    override fun onSkipToPrevious() {
        if (player.currentPosition > 3_000L) {
            onSeekTo(0L)
        } else {
            startExistingAt(resolvePreviousIndex(), playWhenReady = player.playWhenReady)
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val request = requestState.activeRequest ?: return
        val queue = player.playbackQueueSnapshot()
        val queueSong = queue.currentItem?.let(SongMediaItemCodec::decode) ?: return
        if (request.songId != queueSong.id) return
        val song = player.currentMediaItem?.let(SongMediaItemCodec::decode) ?: return
        if (song.id != queueSong.id) return
        if (!requestState.accepts(
                generation = request.generation,
                songId = song.id,
                sourceRevision = PlaybackSourceRevision.of(song),
            ) ||
            player.playerError !== error
        ) {
            return
        }
        val kind = PlaybackFailureClassifier.classify(error)
        handleFailure(
            request.id,
            PlaybackFailure(kind, error.message ?: "Media3 playback failed", error),
        )
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Manual / seek transitions are owned by [onSelectMediaItem] → [start].
        // Only auto-advance updates request state here; otherwise setMediaItems from the
        // app can begin() at position 0 and cause the next startAt to be skipped as duplicate.
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
        val song = mediaItem?.let(SongMediaItemCodec::decode) ?: return
        val active = requestState.activeRequest
        if (active?.songId == song.id &&
            active.sourceRevision == PlaybackSourceRevision.of(song)
        ) {
            return
        }
        requestState.begin(
            song,
            player.currentPosition.coerceAtLeast(0L),
            player.playWhenReady,
            qualityMode(),
        )
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val request = requestState.activeRequest ?: return
        if (isPlaying) {
            requestState.markPlaying(request.id, player.currentPosition)
        } else {
            requestState.markPaused(request.id, player.currentPosition)
        }
    }

    private fun start(
        song: Song,
        index: Int,
        positionMs: Long,
        playWhenReady: Boolean = true,
        queue: PlaybackQueueSnapshot = player.playbackQueueSnapshot(),
    ) {
        val items = queue.items
        if (items.isEmpty()) return
        when (val route = PlaybackRouter.decide(song)) {
            is PlaybackRouteDecision.Unsupported -> {
                player.selectWithoutPlayback(items, index, positionMs)
                val request = requestState.begin(
                    song,
                    positionMs,
                    playWhenReady,
                    qualityMode(),
                )
                handleFailure(
                    request.id,
                    PlaybackFailure(
                        PlaybackFailureKind.EXTRACTOR_UNSUPPORTED,
                        route.userMessage,
                    ),
                    allowAutomaticSkip = false,
                )
                return
            }
            is PlaybackRouteDecision.Supported -> {
                DiagnosticLog.event(
                    "PlaybackEngine",
                    "route=${route.reason} song=${song.id}",
                )
            }
        }
        val request = requestState.begin(
            song,
            positionMs,
            playWhenReady,
            qualityMode(),
        )
        DiagnosticLog.event(
            "PlaybackEngine",
            "start request=${request.id} source=${request.sourceRevision}",
        )
        player.startExoPlayback(items, index, positionMs, playWhenReady = playWhenReady)
    }

    private fun startExistingAt(
        index: Int,
        positionMs: Long = 0L,
        playWhenReady: Boolean,
    ) {
        if (player.mediaItemCount == 0) return
        val safe = index.coerceIn(0, player.mediaItemCount - 1)
        val song = SongMediaItemCodec.decode(player.getMediaItemAt(safe)) ?: return
        val position = positionMs.coerceAtLeast(0L)
        val active = requestState.activeRequest
        val duplicateRequest = active != null &&
            active.songId == song.id &&
            active.sourceRevision == PlaybackSourceRevision.of(song) &&
            kotlin.math.abs(active.startPositionMs - position) <=
            DUPLICATE_START_POSITION_TOLERANCE_MS
        val playbackHealthy = player.isPlaying || player.playbackState == Player.STATE_BUFFERING
        if (duplicateRequest && playbackHealthy) {
            if (player.currentMediaItemIndex != safe) player.setPlaylistIndex(safe)
            DiagnosticLog.event(
                "PlaybackEngine",
                "start-existing ignored duplicate index=$safe song=${song.id} request=${active.id}",
            )
            return
        }
        when (val route = PlaybackRouter.decide(song)) {
            is PlaybackRouteDecision.Unsupported -> {
                player.selectExistingWithoutPlayback(safe, position)
                val request = requestState.begin(
                    song,
                    position,
                    playWhenReady,
                    qualityMode(),
                )
                handleFailure(
                    request.id,
                    PlaybackFailure(
                        PlaybackFailureKind.EXTRACTOR_UNSUPPORTED,
                        route.userMessage,
                    ),
                    allowAutomaticSkip = false,
                )
                return
            }
            is PlaybackRouteDecision.Supported -> {
                DiagnosticLog.event(
                    "PlaybackEngine",
                    "route=${route.reason} song=${song.id} existing-playlist=true",
                )
            }
        }
        val request = requestState.begin(
            song,
            position,
            playWhenReady,
            qualityMode(),
        )
        DiagnosticLog.event(
            "PlaybackEngine",
            "start-existing request=${request.id} index=$safe source=${request.sourceRevision}",
        )
        player.startExistingItem(safe, position, playWhenReady)
    }

    private fun handleFailure(
        requestId: Long,
        failure: PlaybackFailure,
        allowAutomaticSkip: Boolean = true,
    ) {
        val count = requestState.markFailed(requestId, failure) ?: return
        onPlaybackFailure?.invoke(failure)
        DiagnosticLog.event(
            "PlaybackEngine",
            "failed request=$requestId kind=${failure.kind} count=$count message=${failure.message}",
            failure.cause,
        )
        if (!allowAutomaticSkip || !PlaybackFailureClassifier.allowsAutomaticSkip(failure.kind)) return
        if (count >= MAX_CONSECUTIVE_FAILURES) return
        val queueAtFailure = player.playbackQueueSnapshot()
        resolveFailureIndex(queueAtFailure)?.let { startAt(it, queue = queueAtFailure) }
    }

    private fun startAt(
        index: Int,
        positionMs: Long = 0L,
        queue: PlaybackQueueSnapshot = player.playbackQueueSnapshot(),
        preferredMediaId: String? = null,
        playWhenReady: Boolean = player.playWhenReady,
    ) {
        if (queue.items.isEmpty()) return
        val resolvedIndex = preferredMediaId
            ?.takeIf { it.isNotBlank() }
            ?.let { id -> queue.items.indexOfFirst { it.mediaId == id }.takeIf { it >= 0 } }
            ?: index
        val safe = resolvedIndex.coerceIn(0, queue.items.lastIndex)
        val song = SongMediaItemCodec.decode(queue.items[safe]) ?: return
        val active = requestState.activeRequest
        val position = positionMs.coerceAtLeast(0L)
        val isDuplicateRequest = active != null &&
            active.songId == song.id &&
            active.sourceRevision == PlaybackSourceRevision.of(song) &&
            kotlin.math.abs(active.startPositionMs - position) <=
            DUPLICATE_START_POSITION_TOLERANCE_MS
        val playbackHealthy = player.isPlaying ||
            player.playbackState == Player.STATE_BUFFERING
        if (isDuplicateRequest && playbackHealthy) {
            if (player.currentMediaItemIndex != safe) {
                player.setPlaylistIndex(safe)
            }
            DiagnosticLog.event(
                "PlaybackEngine",
                "startAt ignored duplicate requested=$index resolved=$safe " +
                    "song=${song.id} request=${active.id}",
            )
            return
        }
        if (isDuplicateRequest && player.playWhenReady && !playbackHealthy) {
            DiagnosticLog.event(
                "PlaybackEngine",
                "startAt duplicate but playback stalled; restarting requested=$index " +
                    "resolved=$safe song=${song.id} request=${active.id}",
            )
        }
        DiagnosticLog.event(
            "PlaybackEngine",
            "startAt requested=$index resolved=$safe song=${song.id} " +
                "preferred=${preferredMediaId ?: "none"} queueRevision=${queue.revision} " +
                "items=${queue.items.size}",
        )
        start(song, safe, position, playWhenReady = playWhenReady, queue = queue)
    }

    private fun resolveNextIndex(manual: Boolean): Int {
        val queueSize = player.mediaItemCount
        return PlaybackQueueNavigator.nextIndex(
            mode = queueMode(),
            currentIndex = player.currentMediaItemIndex,
            queueSize = queueSize,
            manualSkip = manual,
            randomIndex = { randomIndexExcept(queueSize, it) },
        )
    }

    private fun resolvePreviousIndex(): Int {
        val queueSize = player.mediaItemCount
        return PlaybackQueueNavigator.previousIndex(
            mode = queueMode(),
            currentIndex = player.currentMediaItemIndex,
            queueSize = queueSize,
            randomIndex = { randomIndexExcept(queueSize, it) },
        )
    }

    private fun resolveFailureIndex(
        queue: PlaybackQueueSnapshot = player.playbackQueueSnapshot(),
    ): Int? {
        if (queue.items.size <= 1) return null
        val mode = queueMode()
        val effectiveMode = if (mode == PlaybackQueueMode.REPEAT_ONE) PlaybackQueueMode.OFF else mode
        val next = PlaybackQueueNavigator.nextIndex(
            mode = effectiveMode,
            currentIndex = queue.currentIndex,
            queueSize = queue.items.size,
            manualSkip = false,
            randomIndex = { randomIndexExcept(queue.items.size, it) },
        )
        return next.takeIf { it != queue.currentIndex }
    }

    private fun randomIndexExcept(queueSize: Int, exclude: Int): Int {
        if (queueSize <= 1) return exclude
        var result = exclude
        while (result == exclude) result = Random.nextInt(queueSize)
        return result
    }

    private fun queueMode(): PlaybackQueueMode = when {
        player.shuffleModeEnabled -> PlaybackQueueMode.SHUFFLE
        player.repeatMode == Player.REPEAT_MODE_ALL -> PlaybackQueueMode.REPEAT_ALL
        player.repeatMode == Player.REPEAT_MODE_ONE -> PlaybackQueueMode.REPEAT_ONE
        else -> PlaybackQueueMode.OFF
    }

    private fun qualityMode(): AudioQualityMode =
        if (AppPreferences.equalizerEnabled(context)) AudioQualityMode.DSP else AudioQualityMode.HIFI

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val DUPLICATE_START_POSITION_TOLERANCE_MS = 250L
    }
}
