package com.mica.music.media.dsd

import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.mica.music.media.usb.protocol.CommitDisposition
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.WriteKind
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackAdapter
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
    private val playbackAdapter: UsbExclusivePlaybackAdapter? = null,
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
        val playbackOccurrence = UsbExclusiveShadowMedia3Facts.occurrence(playbackIdentity)
        val configureFacts = UsbExclusiveShadowMedia3Facts.audio(inputFormat, "pcm-configure")
        val navigationEpoch = manualNavigationTransitionBridge.bindPcmDestination(inputFormat, playbackIdentity)
        val navigationSnapshot = manualNavigationTransitionBridge.snapshot()

        // M3 production path: a protocol permit must exist before the delegate is touched. A
        // denied permit is retained as a deferred configuration and never falls through to the
        // legacy coordinator as an authority.
        playbackAdapter?.let { adapter ->
            val permit = adapter.preparePcmConfigure(playbackOccurrence, configureFacts)
            if (permit == null) {
                pendingConfiguration = PendingConfiguration(
                    format = inputFormat,
                    specifiedBufferSize = specifiedBufferSize,
                    outputChannels = outputChannels?.copyOf(),
                    requiresResumeAuthority = false,
                    navigationRequestId = navigationEpoch?.requestId ?: navigationSnapshot?.requestId,
                    navigationRequestedPlaying = navigationEpoch?.requestedPlaying
                        ?: navigationSnapshot?.requestedPlaying
                        ?: true,
                    playbackIdentity = playbackIdentity,
                )
                return
            }
            pendingConfiguration = null
            configureWithProtocol(
                adapter = adapter,
                permit = permit,
                occurrence = playbackOccurrence,
                inputFormat = inputFormat,
                specifiedBufferSize = specifiedBufferSize,
                outputChannels = outputChannels,
                facts = configureFacts,
            )
            navigationEpoch?.let { completeNavigationProjection(it.requestId) }
            return
        }

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
        playbackAdapter?.let { adapter ->
            val identity = playbackPeriodProjection.snapshot()
            val occurrence = UsbExclusiveShadowMedia3Facts.occurrence(identity) ?: return false
            var lease = adapter.tryEnterWrite(occurrence, WriteKind.PCM_DATA)
            if (lease == null) {
                // A reusable decoder/sink may advance the exact occurrence without another
                // configure callback. The adapter may mint a retained handoff only after the
                // raw target stream proof and old lease drain are both exact; otherwise this
                // remains fail-closed and no B bytes reach the delegate.
                adapter.prepareRetainedPcmHandoff(occurrence)?.let { permit ->
                    val disposition = adapter.commitRetainedPcmHandoff(permit)
                    if (disposition is CommitDisposition.CurrentPlaying || disposition is CommitDisposition.CurrentPaused) {
                        lease = adapter.tryEnterWrite(occurrence, WriteKind.PCM_DATA)
                    }
                }
            }
            lease ?: return false
            return try {
                super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            } finally {
                lease.exit()
            }
        }
        transitionCoordinator.onPcmActivity()
        transitionCoordinator.beforePcmAccept(isPlaying = true)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun play() {
        if (playbackAdapter == null) transitionCoordinator.onPcmPlayState(paused = false)
        if (pendingConfiguration != null) return
        if (playbackAdapter != null) {
            super.play()
            return
        }
        transitionCoordinator.onPcmActivity()
        super.play()
    }

    override fun pause() {
        if (playbackAdapter == null) transitionCoordinator.onPcmPlayState(paused = true)
        super.pause()
    }

    override fun flush() {
        pendingConfiguration = null
        super.flush()
        if (playbackAdapter == null) transitionCoordinator.onPcmFlushPotentialRelease()
    }

    override fun reset() {
        pendingConfiguration = null
        super.reset()
        playbackAdapter?.observePcmRuntimeReleased(
            UsbExclusiveShadowMedia3Facts.occurrence(playbackPeriodProjection.snapshot()),
            "reset",
        )
        if (playbackAdapter == null) transitionCoordinator.onPcmReleased()
    }

    override fun release() {
        pendingConfiguration = null
        super.release()
        playbackAdapter?.observePcmRuntimeReleased(
            UsbExclusiveShadowMedia3Facts.occurrence(playbackPeriodProjection.snapshot()),
            "release",
        )
        if (playbackAdapter == null) transitionCoordinator.onPcmReleased()
    }

    private fun activatePendingConfiguration(): Boolean {
        val pending = pendingConfiguration ?: return true
        playbackAdapter?.let { adapter ->
            val occurrence = UsbExclusiveShadowMedia3Facts.occurrence(pending.playbackIdentity) ?: return false
            val permit = adapter.preparePcmConfigure(occurrence, "pcm-pending-configure") ?: return false
            try {
                super.configure(pending.format, pending.specifiedBufferSize, pending.outputChannels)
            } catch (error: Throwable) {
                adapter.failPcmConfigure(permit, "configure:${error.javaClass.simpleName}")
                throw error
            }
            val disposition = adapter.commitPcmConfigure(
                permit,
                occurrence,
                com.mica.music.media.usb.protocol.ResourceIdentity("pcm-configure-${permit.activationId.value}"),
                "protocol-configure-complete",
            )
            check(disposition is CommitDisposition.CurrentPlaying || disposition is CommitDisposition.CurrentPaused) {
                "PCM protocol rejected pending configure receipt: $disposition"
            }
            pendingConfiguration = null
            pending.navigationRequestId?.let(::completeNavigationProjection)
            return true
        }
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

    private fun configureWithProtocol(
        adapter: UsbExclusivePlaybackAdapter,
        permit: com.mica.music.media.usb.protocol.PcmConfigurePermit,
        occurrence: PlaybackOccurrence?,
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
        facts: String,
    ) {
        try {
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
        } catch (error: Throwable) {
            adapter.failPcmConfigure(permit, "configure:${error.javaClass.simpleName}")
            throw error
        }
        val disposition = adapter.commitPcmConfigure(
            permit,
            occurrence,
            com.mica.music.media.usb.protocol.ResourceIdentity("pcm-configure-${permit.activationId.value}"),
            facts,
        )
        check(disposition is CommitDisposition.CurrentPlaying || disposition is CommitDisposition.CurrentPaused) {
            "PCM protocol rejected configure receipt: $disposition"
        }
    }

    private fun completeNavigationProjection(requestId: Long) {
        manualNavigationTransitionBridge.complete(
            requestId,
            DirectDsdTrackTransportFamily.PCM,
        )
    }
}
