package com.mica.music.media.dsd

import android.util.Log

enum class DirectDsdTrackTransportFamily {
    NONE,
    PCM,
    DOP,
}

/**
 * Per-Exo-stack fail-closed family handoff gate.
 *
 * It does not own transport state. Renderers/sinks publish release only after their own state is
 * clean, and must ask this coordinator before accepting bytes for the destination family.
 */
class DirectDsdTrackTransitionCoordinator(
    private val milestone: (String) -> Unit = { Log.i(TAG, it) },
) {
    private var activeFamily = DirectDsdTrackTransportFamily.NONE
    private var pcmPaused = false
    private var directPaused = false
    private var pcmReleasePending = false
    private var lastReleasedFamily = DirectDsdTrackTransportFamily.NONE
    private var lastReleasedWasPaused = false

    @Synchronized
    fun shouldDeferPcmUntilResume(): Boolean =
        (activeFamily == DirectDsdTrackTransportFamily.DOP && directPaused) ||
            (lastReleasedFamily == DirectDsdTrackTransportFamily.DOP && lastReleasedWasPaused)

    @Synchronized
    fun shouldDeferDirectUntilResume(): Boolean =
        (activeFamily == DirectDsdTrackTransportFamily.PCM && pcmPaused) ||
            (lastReleasedFamily == DirectDsdTrackTransportFamily.PCM && lastReleasedWasPaused)

    @Synchronized
    fun beforePcmAccept(isPlaying: Boolean = true) {
        check(activeFamily != DirectDsdTrackTransportFamily.DOP) {
            "PCM acceptance before Direct DSD release"
        }
        check(
            !(!isPlaying && shouldDeferPcmUntilResume()),
        ) { "paused Direct DSD -> PCM transition is deferred" }
        activeFamily = DirectDsdTrackTransportFamily.PCM
        pcmPaused = !isPlaying
        pcmReleasePending = false
        consumeReleasedHistoryLocked(DirectDsdTrackTransportFamily.DOP)
        milestone("trackTransition=pcm-accept-allowed playing=$isPlaying")
    }

    @Synchronized
    fun onPcmActivity() {
        if (activeFamily == DirectDsdTrackTransportFamily.PCM) {
            pcmReleasePending = false
        }
    }

    @Synchronized
    fun onPcmFlushPotentialRelease() {
        if (activeFamily == DirectDsdTrackTransportFamily.PCM) {
            pcmReleasePending = true
        }
    }

    @Synchronized
    fun onPcmPlayState(paused: Boolean) {
        pcmPaused = paused
    }

    @Synchronized
    fun onPcmReleased() {
        if (activeFamily == DirectDsdTrackTransportFamily.PCM) {
            completePcmReleaseLocked()
        }
    }

    @Synchronized
    fun completePcmReleaseForDirectHandoff() {
        if (activeFamily == DirectDsdTrackTransportFamily.PCM && pcmReleasePending) {
            completePcmReleaseLocked()
        }
    }

    @Synchronized
    fun beforeDirectAccept(isPlaying: Boolean) {
        check(activeFamily != DirectDsdTrackTransportFamily.PCM) {
            "Direct DSD acceptance before PCM release"
        }
        check(
            !(!isPlaying && shouldDeferDirectUntilResume()),
        ) { "paused PCM -> Direct DSD transition is deferred" }
        activeFamily = DirectDsdTrackTransportFamily.DOP
        directPaused = !isPlaying
        consumeReleasedHistoryLocked(DirectDsdTrackTransportFamily.PCM)
        milestone("trackTransition=dop-accept-allowed playing=$isPlaying")
    }

    @Synchronized
    fun onDirectPlayState(paused: Boolean) {
        directPaused = paused
    }

    @Synchronized
    fun onDirectReleased(wasPaused: Boolean) {
        if (activeFamily == DirectDsdTrackTransportFamily.DOP) {
            activeFamily = DirectDsdTrackTransportFamily.NONE
            lastReleasedFamily = DirectDsdTrackTransportFamily.DOP
            lastReleasedWasPaused = wasPaused
            directPaused = wasPaused
            milestone("trackTransition=dop-released paused=$wasPaused")
        }
    }

    @Synchronized
    fun snapshot(): DirectDsdTrackTransitionSnapshot = DirectDsdTrackTransitionSnapshot(
        activeFamily = activeFamily,
        pcmPaused = pcmPaused,
        directPaused = directPaused,
        lastReleasedFamily = lastReleasedFamily,
        lastReleasedWasPaused = lastReleasedWasPaused,
    )

    private fun completePcmReleaseLocked() {
        activeFamily = DirectDsdTrackTransportFamily.NONE
        lastReleasedFamily = DirectDsdTrackTransportFamily.PCM
        lastReleasedWasPaused = pcmPaused
        pcmReleasePending = false
        milestone("trackTransition=PCM_SOURCE_INTAKE_CLOSED")
        milestone("trackTransition=PCM_SINK_DECODER_STATE_RELEASED paused=$pcmPaused")
    }

    private fun consumeReleasedHistoryLocked(expectedSourceFamily: DirectDsdTrackTransportFamily) {
        if (lastReleasedFamily != expectedSourceFamily) return
        lastReleasedFamily = DirectDsdTrackTransportFamily.NONE
        lastReleasedWasPaused = false
        milestone("trackTransition=release-history-consumed source=$expectedSourceFamily")
    }

    private companion object {
        const val TAG = "MicaDsdTransition"
    }
}

data class DirectDsdTrackTransitionSnapshot(
    val activeFamily: DirectDsdTrackTransportFamily,
    val pcmPaused: Boolean,
    val directPaused: Boolean,
    val lastReleasedFamily: DirectDsdTrackTransportFamily,
    val lastReleasedWasPaused: Boolean,
)
