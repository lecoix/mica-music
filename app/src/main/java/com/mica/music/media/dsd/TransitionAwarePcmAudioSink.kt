package com.mica.music.media.dsd

import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.mica.music.media.usb.shadow.UsbExclusiveShadowAdapter
import com.mica.music.media.usb.shadow.UsbExclusiveShadowMedia3Facts
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
    private val shadowAdapter: UsbExclusiveShadowAdapter? = null,
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

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        val playbackIdentity = playbackPeriodProjection.snapshot()
        val shadowOccurrence = UsbExclusiveShadowMedia3Facts.occurrence(playbackIdentity)
        shadowAdapter?.observePcmConfigureAttempt(
            shadowOccurrence,
            UsbExclusiveShadowMedia3Facts.audio(inputFormat, "pcm-configure"),
        )
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
        transitionCoordinator.onPcmActivity()
        transitionCoordinator.beforePcmAccept(isPlaying = navigationEpoch?.requestedPlaying ?: true)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        shadowAdapter?.observePcmConfigureCompleted(shadowOccurrence)
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
        if (pendingConfiguration != null && !activatePendingConfiguration()) return false
        transitionCoordinator.onPcmActivity()
        transitionCoordinator.beforePcmAccept(isPlaying = true)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun play() {
        transitionCoordinator.onPcmPlayState(paused = false)
        if (pendingConfiguration != null) return
        transitionCoordinator.onPcmActivity()
        super.play()
    }

    override fun pause() {
        transitionCoordinator.onPcmPlayState(paused = true)
        super.pause()
    }

    override fun flush() {
        pendingConfiguration = null
        super.flush()
        transitionCoordinator.onPcmFlushPotentialRelease()
    }

    override fun reset() {
        pendingConfiguration = null
        super.reset()
        transitionCoordinator.onPcmReleased()
    }

    override fun release() {
        pendingConfiguration = null
        super.release()
        transitionCoordinator.onPcmReleased()
    }

    private fun activatePendingConfiguration(): Boolean {
        val pending = pendingConfiguration ?: return true
        if (transitionCoordinator.snapshot().activeFamily == DirectDsdTrackTransportFamily.DOP) return false

        val requestId = pending.navigationRequestId
        if (pending.requiresResumeAuthority) {
            if (requestId == null || !manualNavigationTransitionBridge.hasResumeGrant(requestId)) return false
        }
        if (requestId != null) {
            val bound = manualNavigationTransitionBridge.bindPcmDestination(
                pending.format,
                pending.playbackIdentity,
            ) ?: return false
            if (bound.requestId != requestId) return false
        }
        if (pending.requiresResumeAuthority) {
            checkNotNull(requestId)
            if (!manualNavigationTransitionBridge.consumeResumeGrant(requestId)) return false
        }

        transitionCoordinator.onPcmActivity()
        transitionCoordinator.beforePcmAccept(
            isPlaying = if (pending.requiresResumeAuthority) true else pending.navigationRequestedPlaying,
        )
        super.configure(
            pending.format,
            pending.specifiedBufferSize,
            pending.outputChannels,
        )
        shadowAdapter?.observePcmConfigureCompleted(
            UsbExclusiveShadowMedia3Facts.occurrence(pending.playbackIdentity),
        )
        pendingConfiguration = null
        requestId?.let {
            check(
                manualNavigationTransitionBridge.complete(
                    it,
                    DirectDsdTrackTransportFamily.PCM,
                ),
            ) { "PCM navigation acceptance for stale destination epoch" }
        }
        return true
    }
}
