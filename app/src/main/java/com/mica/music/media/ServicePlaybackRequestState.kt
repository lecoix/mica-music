package com.mica.music.media

internal class ServicePlaybackRequestState(
    private val sequencer: PlaybackRequestSequencer = PlaybackRequestSequencer(),
    private val fallbackLedger: PlaybackFallbackLedger = PlaybackFallbackLedger(),
) {
    var activeRequest: PlaybackRequest? = null
        private set

    var engineState: PlaybackEngineState = PlaybackEngineState.Idle
        private set

    var consecutiveFailures: Int = 0
        private set

    private var terminalFailureRequestId: Long? = null
    private val alacDecoderFailedSources = mutableMapOf<String, MutableSet<String>>()
    private val disabledAlacDecoders = mutableSetOf<String>()

    fun begin(
        song: com.mica.music.data.Song,
        backend: PlaybackBackendKind,
        positionMs: Long,
        playWhenReady: Boolean,
        qualityMode: AudioQualityMode,
    ): PlaybackRequest {
        val request = sequencer.next(song, backend, positionMs, playWhenReady, qualityMode)
        val previous = activeRequest
        engineState = if (previous == null) {
            PlaybackEngineState.Preparing(request)
        } else {
            PlaybackEngineState.Switching(previous.id, request)
        }
        activeRequest = request
        terminalFailureRequestId = null
        engineState = PlaybackEngineState.Preparing(request)
        return request
    }

    fun accepts(requestId: Long): Boolean = activeRequest?.id == requestId

    fun accepts(
        generation: Long,
        songId: String,
        sourceRevision: String,
        backend: PlaybackBackendKind,
    ): Boolean {
        val request = activeRequest ?: return false
        return request.generation == generation &&
            request.songId == songId &&
            request.sourceRevision == sourceRevision &&
            request.backend == backend
    }

    fun setUserPlayIntent(requestId: Long, play: Boolean): PlaybackRequest? {
        val request = activeRequest?.takeIf { it.id == requestId } ?: return null
        val updated = request.copy(userPlayIntent = play)
        activeRequest = updated
        engineState = when (val state = engineState) {
            is PlaybackEngineState.Preparing -> PlaybackEngineState.Preparing(updated)
            is PlaybackEngineState.Playing ->
                if (play) PlaybackEngineState.Playing(updated, state.positionMs)
                else PlaybackEngineState.Paused(updated, state.positionMs)
            is PlaybackEngineState.Paused ->
                if (play) PlaybackEngineState.Paused(updated, state.positionMs)
                else PlaybackEngineState.Paused(updated, state.positionMs)
            is PlaybackEngineState.Switching -> PlaybackEngineState.Switching(
                state.fromRequestId,
                updated,
            )
            is PlaybackEngineState.Failed -> PlaybackEngineState.Failed(updated, state.failure)
            PlaybackEngineState.Idle -> PlaybackEngineState.Idle
        }
        return updated
    }

    fun markPlaying(requestId: Long, positionMs: Long) {
        val request = activeRequest?.takeIf { it.id == requestId } ?: return
        engineState = PlaybackEngineState.Playing(request, positionMs.coerceAtLeast(0L))
        if (positionMs >= STABLE_PLAYBACK_RESET_MS) consecutiveFailures = 0
    }

    fun markPaused(requestId: Long, positionMs: Long) {
        val request = activeRequest?.takeIf { it.id == requestId } ?: return
        engineState = PlaybackEngineState.Paused(request, positionMs.coerceAtLeast(0L))
    }

    fun markFailed(requestId: Long, failure: PlaybackFailure): Int? {
        val request = activeRequest?.takeIf { it.id == requestId } ?: return null
        if (terminalFailureRequestId == requestId) return null
        terminalFailureRequestId = requestId
        engineState = PlaybackEngineState.Failed(request, failure)
        consecutiveFailures++
        return consecutiveFailures
    }

    fun shouldUseSoftwareFallback(song: com.mica.music.data.Song): Boolean =
        fallbackLedger.hasAttemptedSoftware(PlaybackSourceRevision.of(song))

    fun backendFor(song: com.mica.music.data.Song): PlaybackBackendKind {
        val route = PlaybackRouter.decide(song)
        return if (route.fallback == PlaybackBackendKind.SOFTWARE && (
                shouldUseSoftwareFallback(song) ||
                    (AlacPlayback.isAlac(song) && disabledAlacDecoders.isNotEmpty())
                )
        ) {
            PlaybackBackendKind.SOFTWARE
        } else {
            route.primary
        }
    }

    fun claimSoftwareFallback(requestId: Long, kind: PlaybackFailureKind): Boolean {
        val request = activeRequest?.takeIf {
            it.id == requestId && it.backend == PlaybackBackendKind.MEDIA3
        } ?: return false
        return PlaybackFailureClassifier.allowsSoftwareFallback(kind) &&
            fallbackLedger.claimSoftwareFallback(request.sourceRevision)
    }

    /**
     * Opens a process-local circuit after the same named ALAC decoder fails on
     * two distinct sources. Per-source fallback remains the first line of defense.
     */
    fun recordAlacDecoderFailure(requestId: Long, decoderIdentity: String): Boolean {
        val request = activeRequest?.takeIf {
            it.id == requestId && it.backend == PlaybackBackendKind.MEDIA3
        } ?: return false
        if (!decoderIdentity.contains("alac", ignoreCase = true)) return false

        val failedSources = alacDecoderFailedSources.getOrPut(decoderIdentity) {
            mutableSetOf()
        }
        failedSources += request.sourceRevision
        if (failedSources.size < ALAC_DECODER_CIRCUIT_THRESHOLD) return false
        return disabledAlacDecoders.add(decoderIdentity)
    }

    private companion object {
        const val STABLE_PLAYBACK_RESET_MS = 1_000L
        const val ALAC_DECODER_CIRCUIT_THRESHOLD = 2
    }
}
