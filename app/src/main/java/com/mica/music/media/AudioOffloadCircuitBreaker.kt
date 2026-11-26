package com.mica.music.media

import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink

internal enum class AudioOffloadFallbackReason {
    BUFFERING_STALL,
    READY_POSITION_STALL,
    OFFLOAD_AUDIO_TRACK_ERROR,
}

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

/**
 * Detects a real offload AudioTrack that stalls in buffering, enters STATE_READY with a frozen
 * hardware clock, or throws an offload AudioTrack error, then falls back to PCM and verifies
 * playback progress before persisting a device/build failure record.
 */
@UnstableApi
internal class AudioOffloadCircuitBreaker(
    private val snapshot: () -> AudioOffloadPlaybackSnapshot,
    private val scheduler: AudioOffloadWatchdogScheduler,
    private val onFallbackToPcm: () -> Unit,
    private val onVerifiedFailure: () -> Unit,
    private val onAudioTrackOffloadChanged: (Boolean) -> Unit = {},
) : Player.Listener, AnalyticsListener, ExoPlayer.AudioOffloadListener {

    private enum class WatchMode {
        BUFFERING_STALL,
        READY_POSITION_STALL,
    }

    private data class ActiveWatch(
        val mediaId: String,
        val mode: WatchMode,
        val baselinePositionMs: Long,
    )

    var sessionDisabled: Boolean = false
        private set

    var lastFallbackReason: AudioOffloadFallbackReason? = null
        private set

    private var explicitOffloadedPlayback = false
    private var lastInitializedTrackOffloaded = false
    private var activeOffloadedTrackCount = 0

    val currentlyOffloaded: Boolean
        get() = explicitOffloadedPlayback ||
            (lastInitializedTrackOffloaded && activeOffloadedTrackCount > 0)

    private var generation = 0L
    private var confirmationGeneration = 0L
    private var stallTask: Runnable? = null
    private var activeWatch: ActiveWatch? = null
    private var confirmationTask: Runnable? = null
    private var pendingVerificationMediaId: String? = null

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        val wasOffloaded = currentlyOffloaded
        lastInitializedTrackOffloaded = audioTrackConfig.offload
        if (audioTrackConfig.offload) {
            activeOffloadedTrackCount++
        } else {
            explicitOffloadedPlayback = false
        }
        val nowOffloaded = currentlyOffloaded
        if (wasOffloaded != nowOffloaded) {
            onAudioTrackOffloadChanged(nowOffloaded)
        }
        refresh()
    }

    override fun onAudioTrackReleased(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        val wasOffloaded = currentlyOffloaded
        if (audioTrackConfig.offload) {
            activeOffloadedTrackCount = (activeOffloadedTrackCount - 1).coerceAtLeast(0)
            if (activeOffloadedTrackCount == 0) {
                lastInitializedTrackOffloaded = false
                explicitOffloadedPlayback = false
            }
        }
        val nowOffloaded = currentlyOffloaded
        if (wasOffloaded != nowOffloaded) {
            onAudioTrackOffloadChanged(nowOffloaded)
        }
        refresh()
    }

    override fun onOffloadedPlayback(isOffloadedPlayback: Boolean) {
        val wasOffloaded = currentlyOffloaded
        explicitOffloadedPlayback = isOffloadedPlayback
        if (!isOffloadedPlayback) {
            lastInitializedTrackOffloaded = false
        }
        val nowOffloaded = currentlyOffloaded
        if (wasOffloaded != nowOffloaded) {
            onAudioTrackOffloadChanged(nowOffloaded)
        }
        refresh()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refresh()

    override fun onPlaybackStateChanged(playbackState: Int) = refresh()

    override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = refresh()

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK ||
            reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
            reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
        ) {
            invalidateStallWatch()
            if (confirmationTask != null) {
                confirmationGeneration++
                cancelConfirmationTask()
            }
            refresh()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        invalidatePendingWork(clearPendingVerification = true)
        refresh()
    }

    fun tryRecoverFromPlayerError(error: PlaybackException): Boolean {
        if (sessionDisabled) return false
        if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED &&
            error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED
        ) {
            return false
        }
        val current = snapshot()
        val mediaId = current.mediaId ?: return false
        tripToPcm(
            mediaId = mediaId,
            reason = AudioOffloadFallbackReason.OFFLOAD_AUDIO_TRACK_ERROR,
        )
        return true
    }

    fun invalidateExternalBoundary() {
        invalidatePendingWork(clearPendingVerification = true)
        refresh()
    }

    fun resetForManualRetry() {
        invalidatePendingWork(clearPendingVerification = true)
        sessionDisabled = false
        lastFallbackReason = null
        explicitOffloadedPlayback = false
        lastInitializedTrackOffloaded = false
        activeOffloadedTrackCount = 0
    }

    fun release() {
        invalidatePendingWork(clearPendingVerification = true)
        explicitOffloadedPlayback = false
        lastInitializedTrackOffloaded = false
        activeOffloadedTrackCount = 0
    }

    private fun refresh() {
        val current = snapshot()
        if (sessionDisabled) {
            invalidateStallWatch()
            if (pendingVerificationMediaId != current.mediaId) {
                pendingVerificationMediaId = null
                cancelConfirmationTask()
            } else if (current.isPlaying && !currentlyOffloaded) {
                if (confirmationTask == null) {
                    schedulePcmConfirmation(current)
                }
            } else {
                cancelConfirmationTask()
            }
            return
        }

        cancelConfirmationTask()
        val desiredMode = desiredWatchMode(current)
        val expectedMediaId = current.mediaId
        if (desiredMode == null || expectedMediaId == null) {
            invalidateStallWatch()
            return
        }

        val existing = activeWatch
        if (existing != null &&
            existing.mediaId == expectedMediaId &&
            existing.mode == desiredMode
        ) {
            if (desiredMode == WatchMode.READY_POSITION_STALL &&
                current.currentPositionMs - existing.baselinePositionMs >= MIN_READY_PROGRESS_MS
            ) {
                invalidateStallWatch()
            }
            return
        }

        invalidateStallWatch()
        val expectedGeneration = ++generation
        val baselinePositionMs = current.currentPositionMs
        val timeoutMs = when (desiredMode) {
            WatchMode.BUFFERING_STALL -> BUFFERING_STALL_TIMEOUT_MS
            WatchMode.READY_POSITION_STALL -> READY_STALL_TIMEOUT_MS
        }
        val task = Runnable {
            if (generation != expectedGeneration || sessionDisabled) return@Runnable
            activeWatch = null
            stallTask = null
            val atDeadline = snapshot()
            if (atDeadline.mediaId != expectedMediaId) return@Runnable
            if (desiredWatchMode(atDeadline) != desiredMode) return@Runnable

            val stalled = when (desiredMode) {
                WatchMode.BUFFERING_STALL ->
                    atDeadline.totalBufferedDurationMs >= MIN_BUFFERED_DURATION_MS
                WatchMode.READY_POSITION_STALL ->
                    atDeadline.currentPositionMs - baselinePositionMs < MIN_READY_PROGRESS_MS
            }
            if (!stalled) return@Runnable

            val reason = when (desiredMode) {
                WatchMode.BUFFERING_STALL -> AudioOffloadFallbackReason.BUFFERING_STALL
                WatchMode.READY_POSITION_STALL -> AudioOffloadFallbackReason.READY_POSITION_STALL
            }
            tripToPcm(mediaId = expectedMediaId, reason = reason)
        }
        activeWatch = ActiveWatch(
            mediaId = expectedMediaId,
            mode = desiredMode,
            baselinePositionMs = baselinePositionMs,
        )
        stallTask = task
        scheduler.postDelayed(task, timeoutMs)
    }

    private fun tripToPcm(mediaId: String, reason: AudioOffloadFallbackReason) {
        sessionDisabled = true
        lastFallbackReason = reason
        pendingVerificationMediaId = mediaId
        explicitOffloadedPlayback = false
        lastInitializedTrackOffloaded = false
        invalidatePendingWork(clearPendingVerification = false)
        onFallbackToPcm()
    }

    private fun schedulePcmConfirmation(current: AudioOffloadPlaybackSnapshot) {
        cancelConfirmationTask()
        val expectedMediaId = pendingVerificationMediaId ?: return
        val baselinePositionMs = current.currentPositionMs
        val expectedGeneration = ++confirmationGeneration
        val task = Runnable {
            if (confirmationGeneration != expectedGeneration ||
                !sessionDisabled ||
                pendingVerificationMediaId != expectedMediaId
            ) {
                return@Runnable
            }
            confirmationTask = null
            val afterRecovery = snapshot()
            if (afterRecovery.mediaId != expectedMediaId ||
                !afterRecovery.isPlaying ||
                currentlyOffloaded ||
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

    private fun desiredWatchMode(current: AudioOffloadPlaybackSnapshot): WatchMode? {
        val local = current.uriScheme == null || current.uriScheme in LOCAL_MEDIA_SCHEMES
        if (!currentlyOffloaded ||
            !local ||
            current.mediaId == null ||
            !current.playWhenReady ||
            current.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
        ) {
            return null
        }
        return when {
            current.playbackState == Player.STATE_BUFFERING && !current.isPlaying ->
                WatchMode.BUFFERING_STALL
            current.playbackState == Player.STATE_READY && current.isPlaying ->
                WatchMode.READY_POSITION_STALL
            else -> null
        }
    }

    private fun invalidatePendingWork(clearPendingVerification: Boolean) {
        generation++
        confirmationGeneration++
        cancelStallTask()
        cancelConfirmationTask()
        if (clearPendingVerification) pendingVerificationMediaId = null
    }

    private fun invalidateStallWatch() {
        generation++
        cancelStallTask()
    }

    private fun cancelStallTask() {
        stallTask?.let(scheduler::remove)
        stallTask = null
        activeWatch = null
    }

    private fun cancelConfirmationTask() {
        confirmationTask?.let(scheduler::remove)
        confirmationTask = null
    }

    private companion object {
        const val BUFFERING_STALL_TIMEOUT_MS = 4_000L
        const val READY_STALL_TIMEOUT_MS = 4_000L
        const val MIN_BUFFERED_DURATION_MS = 1_000L
        const val MIN_READY_PROGRESS_MS = 250L
        const val PCM_CONFIRMATION_MS = 3_000L
        const val MIN_CONFIRMED_PROGRESS_MS = 2_000L
        val LOCAL_MEDIA_SCHEMES = setOf("content", "file", "android.resource")
    }
}
