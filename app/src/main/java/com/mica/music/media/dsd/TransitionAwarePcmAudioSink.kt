package com.mica.music.media.dsd

import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.mica.music.media.usb.protocol.CommitDisposition
import com.mica.music.media.usb.protocol.FamilyOwnership
import com.mica.music.media.usb.protocol.FamilyProof
import com.mica.music.media.usb.protocol.PcmAudioGeometry
import com.mica.music.media.usb.protocol.PcmConfigurePermit
import com.mica.music.media.usb.protocol.PcmDelegateTerminal
import com.mica.music.media.usb.protocol.PcmRetirementPermit
import com.mica.music.media.usb.protocol.PcmTailOrderingProof
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.ProtocolLifecycle
import com.mica.music.media.usb.protocol.ResourceIdentity
import com.mica.music.media.usb.protocol.WriteKind
import com.mica.music.media.usb.UsbOutputSessionOwner
import com.mica.music.media.usb.UsbP2RedemptionContext
import com.mica.music.media.usb.sharedPcmUsbP2RedemptionContext
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
    private val playbackAdapter: UsbExclusivePlaybackAdapter,
    private val usbP2RedemptionContext: UsbP2RedemptionContext =
        sharedPcmUsbP2RedemptionContext(UsbOutputSessionOwner()),
) : ForwardingAudioSink(delegate) {
    private data class PendingConfiguration(
        val format: Format,
        val specifiedBufferSize: Int,
        val outputChannels: IntArray?,
        val navigationRequestId: Long?,
        val playbackIdentity: ManualNavigationPlaybackIdentity?,
        val geometry: PcmAudioGeometry,
    )

    private var pendingConfiguration: PendingConfiguration? = null
    private var configuredGeometry: PcmAudioGeometry? = null
    private var sinkBoundarySequence = 0L

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        val playbackIdentity = playbackPeriodProjection.snapshot()
        val playbackOccurrence = UsbExclusiveShadowMedia3Facts.occurrence(playbackIdentity)
        val configureFacts = UsbExclusiveShadowMedia3Facts.audio(inputFormat, "pcm-configure")
        val geometry = pcmGeometry(inputFormat, outputChannels)
        val navigationEpoch = manualNavigationTransitionBridge.bindPcmDestination(inputFormat, playbackIdentity)
        val navigationSnapshot = manualNavigationTransitionBridge.snapshot()
        usbP2RedemptionContext.prepareProtocolBinding()

        // M3 production path: a protocol permit must exist before the delegate is touched. A
        // denied permit is retained as a deferred configuration and never falls through to the
        // legacy coordinator as an authority.
        var permit = playbackAdapter.preparePcmConfigure(playbackOccurrence, configureFacts)
        if (permit == null) {
            val retirement = playbackAdapter.preparePcmFullRelease(playbackOccurrence, geometry)
            if (retirement != null) {
                performPcmFullRelease(retirement, PcmDelegateTerminal.RESET_COMPLETED) { super.reset() }
                configuredGeometry = null
                permit = playbackAdapter.preparePcmConfigure(playbackOccurrence, configureFacts)
            }
        }
        if (permit == null) {
            pendingConfiguration = PendingConfiguration(
                format = inputFormat,
                specifiedBufferSize = specifiedBufferSize,
                outputChannels = outputChannels?.copyOf(),
                navigationRequestId = navigationEpoch?.requestId ?: navigationSnapshot?.requestId,
                playbackIdentity = playbackIdentity,
                geometry = geometry,
            )
            return
        }
        usbP2RedemptionContext.ensurePermitTarget(permit.outputTarget)
        pendingConfiguration = null
        val accepted = configureWithProtocol(
            adapter = playbackAdapter,
            permit = permit,
            occurrence = playbackOccurrence,
            inputFormat = inputFormat,
            specifiedBufferSize = specifiedBufferSize,
            outputChannels = outputChannels,
            facts = configureFacts,
            geometry = geometry,
            retryConfiguration = PendingConfiguration(
                format = inputFormat,
                specifiedBufferSize = specifiedBufferSize,
                outputChannels = outputChannels?.copyOf(),
                navigationRequestId = navigationEpoch?.requestId ?: navigationSnapshot?.requestId,
                playbackIdentity = playbackIdentity,
                geometry = geometry,
            ),
        )
        if (accepted) navigationEpoch?.let { completeNavigationProjection(it.requestId) }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (pendingConfiguration != null && !activatePendingConfiguration()) return false
        val identity = playbackPeriodProjection.snapshot()
        val occurrence = UsbExclusiveShadowMedia3Facts.occurrence(identity) ?: return false
        var lease = playbackAdapter.tryEnterWrite(occurrence, WriteKind.PCM_DATA)
        if (lease == null) {
            // Reused PCM delegate: the sink itself observes the first B write boundary. Only
            // after A intake is revoked/drained does it issue typed compatibility/tail evidence.
            val geometry = configuredGeometry
            val retirement = geometry?.let {
                playbackAdapter.preparePcmRetainedRetirement(occurrence, it)
            }
            if (retirement != null) {
                val proof = FamilyProof.PcmRuntimeRetained(
                    runtimeIdentity = retirement.source.runtimeIdentity,
                    sourceGeometry = retirement.source.geometry,
                    targetGeometry = geometry,
                    tailOrdering = PcmTailOrderingProof(
                        sourceOccurrence = retirement.source.occurrence,
                        targetOccurrence = occurrence,
                        sinkBoundarySequence = nextSinkBoundarySequence(),
                    ),
                )
                playbackAdapter.completePcmRetainedRetirement(retirement, proof)?.let { permit ->
                    usbP2RedemptionContext.ensurePermitTarget(permit.outputTarget)
                    val disposition = playbackAdapter.commitRetainedPcmHandoff(permit)
                    if (disposition is CommitDisposition.CurrentPlaying || disposition is CommitDisposition.CurrentPaused) {
                        lease = playbackAdapter.tryEnterWrite(occurrence, WriteKind.PCM_DATA)
                    }
                }
            }
        }
        lease ?: return false
        val target = usbP2RedemptionContext.prepareProtocolBinding()
        if (target != null && lease.identity.outputTarget != target) {
            lease.exit()
            return false
        }
        return try {
            if (target == null) {
                super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            } else {
                usbP2RedemptionContext.withProtocolWrite(
                    target,
                    lease,
                    WriteKind.PCM_DATA,
                ) {
                    super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                }
            }
        } finally {
            lease.exit()
        }
    }

    override fun play() {
        if (pendingConfiguration != null) return
        super.play()
    }

    override fun pause() {
        super.pause()
    }

    override fun flush() {
        pendingConfiguration = null
        super.flush()
    }

    override fun reset() {
        pendingConfiguration = null
        val retirement = requiredRetiringPcmReleasePermit()
        if (retirement == null) {
            super.reset()
        } else {
            performPcmFullRelease(retirement, PcmDelegateTerminal.RESET_COMPLETED) { super.reset() }
        }
        configuredGeometry = null
    }

    override fun release() {
        pendingConfiguration = null
        val retirement = requiredRetiringPcmReleasePermit()
        if (retirement == null) {
            super.release()
        } else {
            performPcmFullRelease(retirement, PcmDelegateTerminal.RELEASE_COMPLETED) { super.release() }
        }
        configuredGeometry = null
    }

    private fun activatePendingConfiguration(): Boolean {
        val pending = pendingConfiguration ?: return true
        val occurrence = UsbExclusiveShadowMedia3Facts.occurrence(pending.playbackIdentity) ?: return false
        usbP2RedemptionContext.prepareProtocolBinding()
        var permit = playbackAdapter.preparePcmConfigure(occurrence, "pcm-pending-configure")
        if (permit == null) {
            val retirement = playbackAdapter.preparePcmFullRelease(occurrence, pending.geometry)
            if (retirement != null) {
                performPcmFullRelease(retirement, PcmDelegateTerminal.RESET_COMPLETED) { super.reset() }
                configuredGeometry = null
                permit = playbackAdapter.preparePcmConfigure(occurrence, "pcm-pending-configure")
            }
        }
        permit ?: return false
        usbP2RedemptionContext.ensurePermitTarget(permit.outputTarget)
        val resourceIdentity = ResourceIdentity("pcm-configure-${permit.activationId.value}")
        try {
            super.configure(pending.format, pending.specifiedBufferSize, pending.outputChannels)
        } catch (error: Throwable) {
            val disposition = playbackAdapter.failPcmConfigure(
                permit,
                resourceIdentity,
                "configure:${error.javaClass.simpleName}",
            )
            resolvePcmCommit(
                playbackAdapter,
                permit,
                resourceIdentity,
                disposition,
                retryConfiguration = pending,
            )
            throw error
        }
        val disposition = playbackAdapter.commitPcmConfigure(
            permit,
            occurrence,
            resourceIdentity,
            "protocol-configure-complete",
            pending.geometry,
        )
        val accepted = resolvePcmCommit(
            playbackAdapter,
            permit,
            resourceIdentity,
            disposition,
            retryConfiguration = pending,
        )
        if (accepted) {
            configuredGeometry = pending.geometry
            pendingConfiguration = null
            pending.navigationRequestId?.let(::completeNavigationProjection)
        }
        return accepted
    }

    private fun configureWithProtocol(
        adapter: UsbExclusivePlaybackAdapter,
        permit: com.mica.music.media.usb.protocol.PcmConfigurePermit,
        occurrence: PlaybackOccurrence?,
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
        facts: String,
        geometry: PcmAudioGeometry,
        retryConfiguration: PendingConfiguration,
    ): Boolean {
        val resourceIdentity = ResourceIdentity("pcm-configure-${permit.activationId.value}")
        try {
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
        } catch (error: Throwable) {
            val disposition = adapter.failPcmConfigure(
                permit,
                resourceIdentity,
                "configure:${error.javaClass.simpleName}",
            )
            resolvePcmCommit(adapter, permit, resourceIdentity, disposition, retryConfiguration)
            throw error
        }
        val disposition = adapter.commitPcmConfigure(
            permit,
            occurrence,
            resourceIdentity,
            facts,
            geometry,
        )
        val accepted = resolvePcmCommit(adapter, permit, resourceIdentity, disposition, retryConfiguration)
        if (accepted) configuredGeometry = geometry
        return accepted
    }

    /** Performs delegate cleanup before releasing the exact protocol requirement. */
    private fun resolvePcmCommit(
        adapter: UsbExclusivePlaybackAdapter,
        permit: PcmConfigurePermit,
        resourceIdentity: ResourceIdentity,
        disposition: CommitDisposition,
        retryConfiguration: PendingConfiguration,
    ): Boolean = when (disposition) {
        is CommitDisposition.CurrentPlaying,
        is CommitDisposition.CurrentPaused,
        -> true
        is CommitDisposition.CurrentCleanupRequired -> {
            super.reset()
            when (adapter.completeCleanup(permit.activationId, resourceIdentity)) {
                CommitDisposition.RetryPendingSameMutation -> {
                    pendingConfiguration = retryConfiguration
                    false
                }
                CommitDisposition.TerminalFailure -> {
                    pendingConfiguration = null
                    error("PCM configure cleanup completed with terminal continuation")
                }
                CommitDisposition.StaleNoEffect -> {
                    pendingConfiguration = null
                    false
                }
                null -> error("PCM configure cleanup requirement was not owned by its adapter")
                else -> error("Unexpected PCM cleanup continuation after current receipt")
            }
        }
        is CommitDisposition.StaleCleanupRequired,
        is CommitDisposition.RetiringCleanupRequired,
        -> {
            super.reset()
            checkNotNull(adapter.completeCleanup(permit.activationId, resourceIdentity)) {
                "PCM stale/retiring cleanup requirement was not owned by its adapter"
            }
            pendingConfiguration = null
            false
        }
        CommitDisposition.RetryPendingSameMutation -> {
            pendingConfiguration = retryConfiguration
            false
        }
        CommitDisposition.StaleNoEffect -> {
            // The exact receipt was not accepted; reset the delegate before failing closed so a
            // stale sink cannot keep a writable configured resource alive.
            super.reset()
            pendingConfiguration = null
            error("PCM configure receipt became stale without a cleanup requirement")
        }
        CommitDisposition.TerminalFailure -> {
            pendingConfiguration = null
            error("PCM configure receipt reached terminal failure")
        }
    }

    private fun pcmGeometry(inputFormat: Format, outputChannels: IntArray?): PcmAudioGeometry =
        PcmAudioGeometry(
            sampleRate = inputFormat.sampleRate,
            channelCount = inputFormat.channelCount,
            pcmEncoding = inputFormat.pcmEncoding,
            outputChannels = outputChannels?.toList(),
        )

    private fun nextSinkBoundarySequence(): Long {
        sinkBoundarySequence += 1L
        return sinkBoundarySequence
    }

    private inline fun performPcmFullRelease(
        retirement: PcmRetirementPermit,
        terminal: PcmDelegateTerminal,
        delegateTeardown: () -> Unit,
    ) {
        // The permit is frozen before the physical call. Never consult the mutable projection
        // after teardown to decide which source/runtime was destroyed.
        delegateTeardown()
        val proof = FamilyProof.PcmFamilyReleased(
            runtimeIdentity = retirement.source.runtimeIdentity,
            sourceGeometry = retirement.source.geometry,
            terminal = terminal,
            sinkBoundarySequence = nextSinkBoundarySequence(),
        )
        check(playbackAdapter.completePcmFullRelease(retirement, proof)) {
            "PCM delegate teardown completed without exact typed retirement acceptance"
        }
    }

    private fun requiredRetiringPcmReleasePermit(): PcmRetirementPermit? {
        val snapshot = playbackAdapter.snapshot()
        val owned = snapshot.familyOwnership as? FamilyOwnership.PcmOwned ?: return null
        if (snapshot.lifecycle !is ProtocolLifecycle.Retiring || owned.adapterInstanceId != playbackAdapter.id) return null
        return checkNotNull(playbackAdapter.prepareRetiringPcmFullRelease()) {
            "PCM stack teardown attempted before exact source writer drain"
        }
    }

    private fun completeNavigationProjection(requestId: Long) {
        manualNavigationTransitionBridge.complete(
            requestId,
            DirectDsdTrackTransportFamily.PCM,
        )
    }
}
