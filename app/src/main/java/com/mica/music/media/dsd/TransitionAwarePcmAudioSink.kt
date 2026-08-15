package com.mica.music.media.dsd

import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * PCM-family acceptance/release seam shared by platform and FFmpeg audio renderers.
 *
 * The delegate remains authoritative for PCM buffering. This wrapper only publishes family state
 * before bytes are accepted and after the sink has been flushed/reset/released.
 */
internal class TransitionAwarePcmAudioSink(
    delegate: AudioSink,
    private val transitionCoordinator: DirectDsdTrackTransitionCoordinator,
    private val manualNavigationTransitionBridge: ManualNavigationTransitionBridge = ManualNavigationTransitionBridge(),
    private val playbackPeriodProjection: ManualNavigationPlaybackPeriodProjection =
        ManualNavigationPlaybackPeriodProjection(manualNavigationTransitionBridge),
) : ForwardingAudioSink(delegate) {
    private data class PendingConfiguration(
        val format: Format,
        val specifiedBufferSize: Int,
        val outputChannels: IntArray?,
        val requiresResumeAuthority: Boolean,
        val navigationRequestId: Long?,
        val navigationRequestedPlaying: Boolean,
        val playbackIdentity: ManualNavigationPlaybackIdentity?,
    )

    private var pendingConfiguration: PendingConfiguration? = null
    private var playRequestedWhilePending = false

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        val playbackIdentity = playbackPeriodProjection.snapshot()
        val navigationEpoch = manualNavigationTransitionBridge.bindPcmDestination(inputFormat, playbackIdentity)
        val navigationSnapshot = manualNavigationTransitionBridge.snapshot()
        val requiresResumeAuthority = transitionCoordinator.shouldDeferPcmUntilResume()
        if (
            transitionCoordinator.snapshot().activeFamily == DirectDsdTrackTransportFamily.DOP ||
            requiresResumeAuthority ||
            (navigationEpoch == null && navigationSnapshot != null)
        ) {
            val effectiveNavigationEpoch = navigationEpoch ?: navigationSnapshot
            pendingConfiguration = PendingConfiguration(
                format = inputFormat,
                specifiedBufferSize = specifiedBufferSize,
                outputChannels = outputChannels?.copyOf(),
                requiresResumeAuthority = requiresResumeAuthority,
                navigationRequestId = effectiveNavigationEpoch?.requestId,
                navigationRequestedPlaying = effectiveNavigationEpoch?.requestedPlaying ?: true,
                playbackIdentity = playbackIdentity,
            )
            return
        }
        pendingConfiguration = null
        playRequestedWhilePending = false
        transitionCoordinator.onPcmActivity()
        transitionCoordinator.beforePcmAccept(isPlaying = navigationEpoch?.requestedPlaying ?: true)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        navigationEpoch?.let {
            check(
                manualNavigationTransitionBridge.complete(
                    it.requestId,
                    DirectDsdTrackTransportFamily.PCM,
                ),
            ) { "PCM navigation acceptance for stale destination epoch" }
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (pendingConfiguration != null && !activatePendingConfiguration(resumeAuthority = false)) return false
        transitionCoordinator.onPcmActivity()
        transitionCoordinator.beforePcmAccept(isPlaying = true)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun play() {
        transitionCoordinator.onPcmPlayState(paused = false)
        if (pendingConfiguration != null) {
            playRequestedWhilePending = true
            if (!activatePendingConfiguration(resumeAuthority = true)) return
            return
        }
        transitionCoordinator.onPcmActivity()
        super.play()
    }

    override fun pause() {
        transitionCoordinator.onPcmPlayState(paused = true)
        super.pause()
    }

    override fun flush() {
        pendingConfiguration = null
        playRequestedWhilePending = false
        super.flush()
        transitionCoordinator.onPcmFlushPotentialRelease()
    }

    override fun reset() {
        pendingConfiguration = null
        playRequestedWhilePending = false
        super.reset()
        transitionCoordinator.onPcmReleased()
    }

    override fun release() {
        pendingConfiguration = null
        playRequestedWhilePending = false
        super.release()
        transitionCoordinator.onPcmReleased()
    }

    private fun activatePendingConfiguration(resumeAuthority: Boolean): Boolean {
        val pending = pendingConfiguration ?: return true
        if (pending.requiresResumeAuthority && !resumeAuthority) return false
        if (transitionCoordinator.snapshot().activeFamily == DirectDsdTrackTransportFamily.DOP) return false
        pending.navigationRequestId?.let { requestId ->
            val bound = manualNavigationTransitionBridge.bindPcmDestination(
                pending.format,
                pending.playbackIdentity,
            ) ?: return false
            if (bound.requestId != requestId) return false
        }
        transitionCoordinator.onPcmActivity()
        transitionCoordinator.beforePcmAccept(isPlaying = pending.navigationRequestedPlaying)
        super.configure(
            pending.format,
            pending.specifiedBufferSize,
            pending.outputChannels,
        )
        pendingConfiguration = null
        pending.navigationRequestId?.let { requestId ->
            check(
                manualNavigationTransitionBridge.complete(
                    requestId,
                    DirectDsdTrackTransportFamily.PCM,
                ),
            ) { "PCM navigation acceptance for stale destination epoch" }
        }
        if (playRequestedWhilePending) {
            playRequestedWhilePending = false
            super.play()
        }
        return true
    }
}
