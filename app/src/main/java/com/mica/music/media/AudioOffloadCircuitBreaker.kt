package com.mica.music.media

import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

internal data class AudioOffloadPlaybackSnapshot(
    val mediaId: String?,
    val uriScheme: String?,
    val playbackState: Int,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val playbackSuppressionReason: Int,
    val totalBufferedDurationMs: Long,
    val currentPositionMs: Long,
)

internal interface AudioOffloadWatchdogScheduler {
    fun postDelayed(task: Runnable, delayMs: Long)
    fun remove(task: Runnable)
}

internal class HandlerAudioOffloadWatchdogScheduler(
    private val handler: Handler,
) : AudioOffloadWatchdogScheduler {
    override fun postDelayed(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }

    override fun remove(task: Runnable) {
        handler.removeCallbacks(task)
    }
}

/** Detects a real offload AudioTrack that has buffered local media but never starts playing. */
@UnstableApi
internal class AudioOffloadCircuitBreaker(
    private val snapshot: () -> AudioOffloadPlaybackSnapshot,
    private val scheduler: AudioOffloadWatchdogScheduler,
    private val onFallbackToPcm: () -> Unit,
    private val onVerifiedFailure: () -> Unit,
) : Player.Listener, ExoPlayer.AudioOffloadListener {

    var sessionDisabled: Boolean = false
        private set

    private var isOffloadedPlayback = false
    private var generation = 0L
    private var confirmationGeneration = 0L
    private var stallTask: Runnable? = null
    private var confirmationTask: Runnable? = null
    private var pendingVerificationMediaId: String? = null

    override fun onOffloadedPlayback(isOffloadedPlayback: Boolean) {
        this.isOffloadedPlayback = isOffloadedPlayback
        refresh()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refresh()

    override fun onPlaybackStateChanged(playbackState: Int) = refresh()

    override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = refresh()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        invalidatePendingWork(clearPendingVerification = true)
        refresh()
    }

    fun invalidateExternalBoundary() {
        invalidatePendingWork(clearPendingVerification = true)
        refresh()
    }

    fun resetForManualRetry() {
        invalidatePendingWork(clearPendingVerification = true)
        sessionDisabled = false
        isOffloadedPlayback = false
    }

    fun release() {
        invalidatePendingWork(clearPendingVerification = true)
        isOffloadedPlayback = false
    }

    private fun refresh() {
        val current = snapshot()
        if (sessionDisabled) {
            cancelStallTask()
            if (pendingVerificationMediaId != current.mediaId) {
                pendingVerificationMediaId = null
                cancelConfirmationTask()
            } else if (current.isPlaying) {
                schedulePcmConfirmation(current)
            } else {
                cancelConfirmationTask()
            }
            return
        }

        cancelConfirmationTask()
        cancelStallTask()
        if (!isEligiblePlaybackIntent(current)) return

        val expectedGeneration = ++generation
        val expectedMediaId = current.mediaId ?: return
        val task = Runnable {
            if (generation != expectedGeneration || sessionDisabled) return@Runnable
            val atDeadline = snapshot()
            if (atDeadline.mediaId != expectedMediaId ||
                !isEligiblePlaybackIntent(atDeadline) ||
                atDeadline.totalBufferedDurationMs < MIN_BUFFERED_DURATION_MS
            ) {
                return@Runnable
            }

            sessionDisabled = true
            pendingVerificationMediaId = expectedMediaId
            invalidatePendingWork(clearPendingVerification = false)
            onFallbackToPcm()
        }
        stallTask = task
        scheduler.postDelayed(task, STALL_TIMEOUT_MS)
    }

    private fun schedulePcmConfirmation(current: AudioOffloadPlaybackSnapshot) {
        cancelConfirmationTask()
        val expectedMediaId = pendingVerificationMediaId ?: return
        val baselinePositionMs = current.currentPositionMs
        val expectedGeneration = ++confirmationGeneration
        val task = Runnable {
            if (confirmationGeneration != expectedGeneration ||
                pendingVerificationMediaId != expectedMediaId
            ) {
                return@Runnable
            }
            val afterRecovery = snapshot()
            if (afterRecovery.mediaId != expectedMediaId ||
                !afterRecovery.isPlaying ||
                afterRecovery.currentPositionMs - baselinePositionMs < MIN_CONFIRMED_PROGRESS_MS
            ) {
                return@Runnable
            }

            pendingVerificationMediaId = null
            cancelConfirmationTask()
            onVerifiedFailure()
        }
        confirmationTask = task
        scheduler.postDelayed(task, PCM_CONFIRMATION_MS)
    }

    private fun isEligiblePlaybackIntent(current: AudioOffloadPlaybackSnapshot): Boolean {
        val local = current.uriScheme == null || current.uriScheme in LOCAL_MEDIA_SCHEMES
        return isOffloadedPlayback &&
            local &&
            current.mediaId != null &&
            current.playWhenReady &&
            !current.isPlaying &&
            current.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            current.playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_READY)
    }

    private fun invalidatePendingWork(clearPendingVerification: Boolean) {
        generation++
        confirmationGeneration++
        cancelStallTask()
        cancelConfirmationTask()
        if (clearPendingVerification) pendingVerificationMediaId = null
    }

    private fun cancelStallTask() {
        stallTask?.let(scheduler::remove)
        stallTask = null
    }

    private fun cancelConfirmationTask() {
        confirmationTask?.let(scheduler::remove)
        confirmationTask = null
    }

    private companion object {
        const val STALL_TIMEOUT_MS = 8_000L
        const val MIN_BUFFERED_DURATION_MS = 1_000L
        const val PCM_CONFIRMATION_MS = 3_000L
        const val MIN_CONFIRMED_PROGRESS_MS = 2_000L
        val LOCAL_MEDIA_SCHEMES = setOf("content", "file", "android.resource")
    }
}
