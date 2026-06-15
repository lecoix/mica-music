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
    private val engine: AlacAudioTrackEngine,
    private val audioFocusGate: SoftwareAudioFocusGate = AlwaysGrantedSoftwareAudioFocusGate,
    private val requestState: ServicePlaybackRequestState = ServicePlaybackRequestState(),
) : AlacSessionCommandHandler, Player.Listener {
    private val clock = AlacPlaybackClock()
    private var focusedGeneration: Long? = null
    private var softwareNeedsStart = false

    fun start() {
        player.playbackCoordinator = this
        player.addListener(this)
    }

    fun release() {
        player.removeListener(this)
        player.playbackCoordinator = null
        focusedGeneration?.let(audioFocusGate::abandon)
        focusedGeneration = null
        engine.release()
    }

    fun stopSoftwareSession() {
        focusedGeneration?.let(audioFocusGate::abandon)
        focusedGeneration = null
        engine.stop()
        softwareNeedsStart = false
        player.endAlacSession()
    }

    fun onCurrentQueueItemRemoved(continuePlaying: Boolean) {
        val queue = player.playbackQueueSnapshot()
        if (queue.items.isEmpty()) {
            stopSoftwareSession()
            return
        }
        engine.stop()
        focusedGeneration?.let(audioFocusGate::abandon)
        focusedGeneration = null
        softwareNeedsStart = true
        val song = queue.currentItem?.let(SongMediaItemCodec::decode) ?: return
        val request = requestState.begin(
            song = song,
            backend = requestState.backendFor(song),
            positionMs = 0L,
            playWhenReady = continuePlaying,
            qualityMode = qualityMode(),
        )
        clock.resetForNewTrack(song.durationSec.coerceAtLeast(0) * 1000L)
        clock.applyPlayWhenReady(continuePlaying)
        clock.applyBuffering(clock.generation, false)
        requestState.markPaused(request.id, 0L)
        publishSoftwareState()
        if (continuePlaying) {
            start(song, queue.currentIndex, 0L, request.backend, playWhenReady = true)
        }
    }

    fun playCurrent() {
        val item = player.currentMediaItem ?: return
        val song = SongMediaItemCodec.decode(item)
        if (song == null) {
            player.playExoDirect()
            return
        }
        val active = requestState.activeRequest
        val backend = requestState.backendFor(song)
        if (active?.songId == song.id &&
            active.sourceRevision == PlaybackSourceRevision.of(song) &&
            active.backend == PlaybackBackendKind.MEDIA3 &&
            backend == PlaybackBackendKind.MEDIA3
        ) {
            player.playExoDirect()
            return
        }
        start(song, player.currentMediaItemIndex, player.currentPosition, backend)
    }

    override fun onPlay() {
        if (!player.isAlacActive) {
            playCurrent()
            return
        }
        if (softwareNeedsStart) {
            val queue = player.playbackQueueSnapshot()
            val song = queue.currentItem?.let(SongMediaItemCodec::decode) ?: return
            start(song, queue.currentIndex, clock.positionMs, requestState.backendFor(song))
            return
        }
        clock.applyPlayWhenReady(true)
        requestState.setUserPlayIntent(
            requestState.activeRequest?.id ?: return,
            true,
        )
        publishSoftwareState()
        val request = requestState.activeRequest ?: return
        if (focusedGeneration == request.generation ||
            audioFocusGate.request(request.generation)
        ) {
            focusedGeneration = request.generation
            engine.resumeOrRestart()
        } else {
            clock.applyPlayWhenReady(false)
            requestState.setUserPlayIntent(request.id, false)
            requestState.markPaused(request.id, clock.positionMs)
            publishSoftwareState()
        }
    }

    override fun onPause() {
        if (!player.isAlacActive) {
            player.pauseExoDirect()
            return
        }
        clock.applyPlayWhenReady(false)
        requestState.activeRequest?.let { request ->
            requestState.setUserPlayIntent(request.id, false)
        }
        clock.applyPlaying(clock.generation, false)
        engine.pause()
        requestState.activeRequest?.let {
            requestState.markPaused(it.id, clock.positionMs)
        }
        publishSoftwareState()
    }

    override fun onSeekTo(positionMs: Long) {
        if (!player.isAlacActive) return
        val target = positionMs.coerceAtLeast(0L)
        DiagnosticLog.event(
            "PlaybackEngine",
            "seek backend=SOFTWARE request=${requestState.activeRequest?.id} " +
                "generation=${requestState.activeRequest?.generation} targetMs=$target",
        )
        clock.beginSeek(target, clock.playWhenReady)
        publishSoftwareState()
        engine.seekToMs(target.toInt(), startPlayback = clock.playWhenReady)
    }

    override fun onSelectMediaItem(index: Int, positionMs: Long) {
        startAt(index, positionMs.coerceAtLeast(0L))
    }

    override fun onSkipToNext() {
        startAt(resolveNextIndex(manual = true))
    }

    override fun onSkipToPrevious() {
        if (clock.positionMs > 3_000L) {
            onSeekTo(0L)
        } else {
            startAt(resolvePreviousIndex())
        }
    }

    fun onSoftwareAudioFocusGain(generation: Long) {
        val request = requestState.activeRequest ?: return
        if (request.generation != generation || !request.userPlayIntent || !player.isAlacActive) {
            return
        }
        focusedGeneration = generation
        clock.applyPlayWhenReady(true)
        publishSoftwareState()
        engine.resumeOrRestart()
    }

    fun onSoftwareAudioFocusLoss(generation: Long, transient: Boolean) {
        val request = requestState.activeRequest ?: return
        if (request.generation != generation || !player.isAlacActive) return
        clock.applyPlaying(clock.generation, false)
        if (!transient) {
            clock.applyPlayWhenReady(false)
            requestState.setUserPlayIntent(request.id, false)
        }
        engine.pause()
        requestState.markPaused(request.id, clock.positionMs)
        publishSoftwareState()
    }

    override fun onPlayerError(error: PlaybackException) {
        val request = requestState.activeRequest ?: return
        if (request.backend != PlaybackBackendKind.MEDIA3 ||
            player.currentMediaItem?.mediaId != request.songId
        ) {
            return
        }
        val kind = PlaybackFailureClassifier.classify(error)
        val song = player.currentMediaItem?.let(SongMediaItemCodec::decode) ?: return
        if (!requestState.accepts(
                generation = request.generation,
                songId = song.id,
                sourceRevision = PlaybackSourceRevision.of(song),
                backend = PlaybackBackendKind.MEDIA3,
            ) ||
            player.playerError !== error
        ) {
            return
        }
        val route = PlaybackRouter.decide(song)
        val decoderIdentity = PlaybackFailureClassifier.stableAlacDecoderIdentity(error)
        val circuitOpened = decoderIdentity?.let {
            requestState.recordAlacDecoderFailure(request.id, it)
        } == true
        val shouldFallback = route.fallback == PlaybackBackendKind.SOFTWARE &&
            (requestState.shouldUseSoftwareFallback(song) ||
                requestState.claimSoftwareFallback(request.id, kind))
        if (shouldFallback) {
            val position = (player.currentPosition - FALLBACK_REWIND_MS).coerceAtLeast(0L)
            if (circuitOpened) {
                DiagnosticLog.event(
                    "PlaybackEngine",
                    "ALAC Media3 disabled for process decoder=$decoderIdentity " +
                        "after distinct-source failures",
                )
            }
            DiagnosticLog.event(
                "PlaybackEngine",
                "fallback request=${request.id} kind=$kind positionMs=$position",
                error,
            )
            start(song, player.currentMediaItemIndex, position, PlaybackBackendKind.SOFTWARE)
            return
        }
        handleFailure(
            request.id,
            PlaybackFailure(kind, error.message ?: "Media3 playback failed", error),
        )
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (player.isAlacActive) return
        val song = mediaItem?.let(SongMediaItemCodec::decode) ?: return
        val active = requestState.activeRequest
        if (active?.songId == song.id &&
            active.sourceRevision == PlaybackSourceRevision.of(song)
        ) {
            return
        }
        val backend = requestState.backendFor(song)
        if (backend == PlaybackBackendKind.SOFTWARE && player.playWhenReady) {
            start(song, player.currentMediaItemIndex, 0L, PlaybackBackendKind.SOFTWARE)
        } else {
            requestState.begin(
                song,
                PlaybackBackendKind.MEDIA3,
                0L,
                player.playWhenReady,
                qualityMode(),
            )
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (player.isAlacActive) return
        val request = requestState.activeRequest
            ?.takeIf { it.backend == PlaybackBackendKind.MEDIA3 }
            ?: return
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
        backend: PlaybackBackendKind,
        playWhenReady: Boolean = true,
    ) {
        when (backend) {
            PlaybackBackendKind.MEDIA3 -> startMedia3(song, index, positionMs, playWhenReady)
            PlaybackBackendKind.SOFTWARE -> startSoftware(song, index, positionMs, playWhenReady)
        }
    }

    private fun startMedia3(
        song: Song,
        index: Int,
        positionMs: Long,
        playWhenReady: Boolean,
    ) {
        val items = player.playbackQueueSnapshot().items
        if (items.isEmpty()) return
        val request = requestState.begin(
            song,
            PlaybackBackendKind.MEDIA3,
            positionMs,
            playWhenReady,
            qualityMode(),
        )
        // Invalidate the software request before closing its stdout. The close is cancellation,
        // not a decode failure, and the software timeline remains visible until the atomic Exo swap.
        focusedGeneration?.let(audioFocusGate::abandon)
        focusedGeneration = null
        engine.stopForBackendSwitch()
        DiagnosticLog.event(
            "PlaybackEngine",
            "start backend=MEDIA3 request=${request.id} source=${request.sourceRevision}",
        )
        softwareNeedsStart = false
        player.startExoPlayback(items, index, positionMs, playWhenReady = playWhenReady)
    }

    private fun startSoftware(
        song: Song,
        index: Int,
        positionMs: Long,
        playWhenReady: Boolean,
    ) {
        if (!FfmpegRunner.hasEmbeddedBinary(context)) {
            val request = requestState.begin(
                song,
                PlaybackBackendKind.SOFTWARE,
                positionMs,
                playWhenReady,
                qualityMode(),
            )
            handleFailure(
                request.id,
                PlaybackFailure(PlaybackFailureKind.DECODE_FAILED, "FFmpeg unavailable"),
            )
            return
        }
        val queue = player.playbackQueueSnapshot()
        if (queue.items.isEmpty()) return
        val safeIndex = index.coerceIn(0, queue.items.lastIndex)
        val request = requestState.begin(
            song,
            PlaybackBackendKind.SOFTWARE,
            positionMs,
            playWhenReady,
            qualityMode(),
        )
        clock.resetForNewTrack(song.durationSec.coerceAtLeast(0) * 1000L)
        clock.applyPlayWhenReady(playWhenReady)
        if (positionMs > 0) clock.pinInitialPosition(positionMs)
        player.startSoftwarePlaybackSession(
            mediaItems = queue.items,
            startIndex = safeIndex,
            state = clock.toSessionState(),
            snapshotRevision = queue.revision,
        )
        DiagnosticLog.event(
            "PlaybackEngine",
            "start backend=SOFTWARE request=${request.id} source=${request.sourceRevision}",
        )
        if (!playWhenReady) {
            softwareNeedsStart = true
            clock.applyBuffering(clock.generation, false)
            requestState.markPaused(request.id, clock.positionMs)
            publishSoftwareState()
        } else if (audioFocusGate.request(request.generation)) {
            focusedGeneration = request.generation
            softwareNeedsStart = false
            engine.play(
                song,
                softwareCallback(request.id),
                startOffsetMs = positionMs.coerceAtLeast(0L).toInt(),
            )
        } else {
            softwareNeedsStart = true
            clock.applyPlayWhenReady(false)
            clock.applyBuffering(clock.generation, false)
            requestState.setUserPlayIntent(request.id, false)
            requestState.markPaused(request.id, clock.positionMs)
            publishSoftwareState()
            DiagnosticLog.event(
                "AudioFocus",
                "software focus denied request=${request.id}; playback paused",
            )
        }
    }

    private fun softwareCallback(requestId: Long) = object : AlacAudioTrackEngine.Callback {
        private fun stale(): Boolean = !requestState.accepts(requestId) || !player.isAlacActive

        override fun onPrepared(durationSec: Int) {
            if (stale()) return
            clock.applyPrepared(clock.generation, durationSec)
            clock.releaseSeekAnchor()
            publishSoftwareState()
        }

        override fun onPositionMs(positionMs: Int) {
            if (stale()) return
            clock.applyPosition(
                clock.generation,
                positionMs.toLong(),
                clock.durationMs,
            ) ?: return
            requestState.markPlaying(requestId, clock.positionMs)
            player.publishAlacPosition(clock.positionMs, clock.durationMs)
        }

        override fun onPlayingChanged(playing: Boolean) {
            if (stale()) return
            clock.applyPlaying(clock.generation, playing)
            if (playing) {
                requestState.markPlaying(requestId, clock.positionMs)
            } else {
                requestState.markPaused(requestId, clock.positionMs)
            }
            publishSoftwareState()
        }

        override fun onBuffering(buffering: Boolean) {
            if (stale()) return
            clock.applyBuffering(clock.generation, buffering)
            if (!buffering) clock.releaseSeekAnchor()
            publishSoftwareState()
        }

        override fun onEnded() {
            if (stale()) return
            clock.applyPlaying(clock.generation, false)
            val next = resolveNextIndex(manual = false)
            val currentIndex = player.playbackQueueSnapshot().currentIndex
            if (next == currentIndex && queueMode() == PlaybackQueueMode.OFF) {
                focusedGeneration?.let(audioFocusGate::abandon)
                focusedGeneration = null
                engine.stop()
                player.endAlacSession()
                return
            }
            startAt(next)
        }

        override fun onError(message: String) {
            if (stale()) return
            handleFailure(
                requestId,
                PlaybackFailure(PlaybackFailureKind.DECODE_FAILED, message),
            )
        }
    }

    private fun handleFailure(requestId: Long, failure: PlaybackFailure) {
        val count = requestState.markFailed(requestId, failure) ?: return
        DiagnosticLog.event(
            "PlaybackEngine",
            "failed request=$requestId kind=${failure.kind} count=$count message=${failure.message}",
            failure.cause,
        )
        focusedGeneration?.let(audioFocusGate::abandon)
        focusedGeneration = null
        engine.stop()
        player.endAlacSession()
        if (!PlaybackFailureClassifier.allowsAutomaticSkip(failure.kind)) return
        if (count >= MAX_CONSECUTIVE_FAILURES) return
        resolveFailureIndex()?.let(::startAt)
    }

    private fun startAt(index: Int, positionMs: Long = 0L) {
        val queue = player.playbackQueueSnapshot()
        if (queue.items.isEmpty()) return
        val safe = index.coerceIn(0, queue.items.lastIndex)
        val song = SongMediaItemCodec.decode(queue.items[safe]) ?: return
        start(song, safe, positionMs, requestState.backendFor(song))
    }

    private fun publishSoftwareState() {
        player.publishAlacState(clock.toSessionState())
    }

    private fun resolveNextIndex(manual: Boolean): Int {
        val queue = player.playbackQueueSnapshot()
        return PlaybackQueueNavigator.nextIndex(
            mode = queueMode(),
            currentIndex = queue.currentIndex,
            queueSize = queue.items.size,
            manualSkip = manual,
            randomIndex = { randomIndexExcept(queue.items.size, it) },
        )
    }

    private fun resolvePreviousIndex(): Int {
        val queue = player.playbackQueueSnapshot()
        return PlaybackQueueNavigator.previousIndex(
            mode = queueMode(),
            currentIndex = queue.currentIndex,
            queueSize = queue.items.size,
            randomIndex = { randomIndexExcept(queue.items.size, it) },
        )
    }

    private fun resolveFailureIndex(): Int? {
        val queue = player.playbackQueueSnapshot()
        if (queue.items.size <= 1) return null
        return if (queueMode() == PlaybackQueueMode.SHUFFLE) {
            randomIndexExcept(queue.items.size, queue.currentIndex)
        } else if (queue.currentIndex < queue.items.lastIndex) {
            queue.currentIndex + 1
        } else if (queueMode() == PlaybackQueueMode.REPEAT_ALL) {
            0
        } else {
            null
        }
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
        const val FALLBACK_REWIND_MS = 300L
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
