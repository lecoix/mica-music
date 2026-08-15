package com.mica.music.media.usb.shadow

import com.mica.music.media.usb.protocol.AdapterInstanceId
import com.mica.music.media.usb.protocol.ActiveWriteLease
import com.mica.music.media.usb.protocol.CandidateOccurrence
import com.mica.music.media.usb.protocol.CommitDisposition
import com.mica.music.media.usb.protocol.DirectStage
import com.mica.music.media.usb.protocol.DirectStagePermit
import com.mica.music.media.usb.protocol.DirectRetainedHandoffPermit
import com.mica.music.media.usb.protocol.FamilyProof
import com.mica.music.media.usb.protocol.FamilyOwnership
import com.mica.music.media.usb.protocol.MutationCausalHandle
import com.mica.music.media.usb.protocol.MutationId
import com.mica.music.media.usb.protocol.MutationKind
import com.mica.music.media.usb.protocol.MutationEpoch
import com.mica.music.media.usb.protocol.OutputTarget
import com.mica.music.media.usb.protocol.PcmConfigurePermit
import com.mica.music.media.usb.protocol.PlaybackFamily
import com.mica.music.media.usb.protocol.PlaybackIntent
import com.mica.music.media.usb.protocol.PlaybackIntentLedger
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.PlaybackStackId
import com.mica.music.media.usb.protocol.ProtocolLifecycle
import com.mica.music.media.usb.protocol.ResourceIdentity
import com.mica.music.media.usb.protocol.RetirementScope
import com.mica.music.media.usb.protocol.RetainedPcmHandoffPermit
import com.mica.music.media.usb.protocol.RuntimeIdentity
import com.mica.music.media.usb.protocol.SideEffectReceipt
import com.mica.music.media.usb.protocol.IntentSnapshot
import com.mica.music.media.usb.protocol.UsbExclusivePlaybackProtocol
import com.mica.music.media.usb.protocol.UsbExclusiveProtocolSnapshot
import com.mica.music.media.usb.protocol.UsbOutputGeneration
import com.mica.music.media.usb.protocol.WriteKind
import com.mica.music.util.DiagnosticLog

internal enum class UsbExclusiveShadowAdapterKind {
    PLATFORM_PCM,
    FFMPEG_PCM,
    FFMPEG_DSD_PCM,
    DIRECT_DOP,
}

internal enum class UsbExclusiveShadowDecision {
    RAW_OBSERVED,
    WOULD_PERMIT,
    WOULD_DEFER,
    DIVERGENCE,
    STALE_DROP,
    INSUFFICIENT_EVIDENCE,
}

internal data class UsbExclusiveShadowDiagnostic(
    val sequence: Long,
    val stackId: PlaybackStackId,
    val intentRevision: Long,
    val mutationId: MutationId?,
    val adapterInstanceId: AdapterInstanceId?,
    val occurrence: PlaybackOccurrence?,
    val outputTarget: OutputTarget,
    val rawEventKind: String,
    val decision: UsbExclusiveShadowDecision,
    val legacyCorrelationId: Long? = null,
    val detail: String = "",
)

/**
 * Service-lifetime owner for the production playback protocol.
 *
 * M3 keeps the M2 class name as a compatibility detail, but this is no longer an observation
 * projection. The coordinator owns the service ledger and each stack owns one protocol instance;
 * adapters receive exact permits, typed receipts, and the committed write lease from that same
 * instance. The observe* methods below remain raw-fact/compatibility seams for existing callers.
 */
internal class UsbExclusiveShadowCoordinator(
    private val diagnosticSink: (UsbExclusiveShadowDiagnostic) -> Unit = ::logDiagnostic,
) {
    internal val ledger = PlaybackIntentLedger()
    private var nextStackId = 0L
    private var nextAdapterId = 0L
    private var nextEventSequence = 0L
    private var currentOutputTarget: OutputTarget = OutputTarget.SharedPcm
    private val stacks = linkedMapOf<PlaybackStackId, UsbExclusiveShadowStack>()
    private val diagnostics = ArrayDeque<UsbExclusiveShadowDiagnostic>()

    @Synchronized
    fun createStack(initialOutputTarget: OutputTarget = currentOutputTarget): UsbExclusiveShadowStack {
        val id = PlaybackStackId(++nextStackId)
        val stack = UsbExclusiveShadowStack(
            coordinator = this,
            protocol = UsbExclusivePlaybackProtocol(ledger, id, initialOutputTarget),
        )
        stacks[id] = stack
        emit(
            stack,
            "STACK_BUILT",
            UsbExclusiveShadowDecision.RAW_OBSERVED,
            detail = "service-ledger-adopted initialOutput=$initialOutputTarget",
        )
        return stack
    }

    @Synchronized
    fun publishStack(stack: UsbExclusiveShadowStack) {
        val snapshot = stack.protocol.snapshot()
        if (stacks[snapshot.stackId] !== stack || snapshot.lifecycle !is ProtocolLifecycle.Active) return
        currentOutputTarget = snapshot.outputTarget
        emit(stack, "STACK_PUBLISHED", UsbExclusiveShadowDecision.RAW_OBSERVED, detail = "output=${snapshot.outputTarget}")
    }

    fun publishSemanticIntent(playing: Boolean) {
        synchronized(this) {
            publishSemanticIntentLocked(playing, expectedStack = null)
        }
    }

    @Synchronized
    internal fun publishSemanticIntentFromStack(
        stack: UsbExclusiveShadowStack,
        playing: Boolean,
    ): Boolean = publishSemanticIntentLocked(playing, expectedStack = stack)

    private fun publishSemanticIntentLocked(
        playing: Boolean,
        expectedStack: UsbExclusiveShadowStack?,
    ): Boolean {
        if (expectedStack != null && expectedStack.protocol.snapshot().lifecycle !is ProtocolLifecycle.Active) {
            emit(
                expectedStack,
                "SEMANTIC_${if (playing) "PLAY" else "PAUSE"}",
                UsbExclusiveShadowDecision.STALE_DROP,
                detail = "retiring-stack-cannot-publish-intent",
            )
            return false
        }
        ledger.publish(if (playing) PlaybackIntent.PLAY else PlaybackIntent.PAUSE)
        stacks.values.forEach { stack ->
            if (stack.protocol.snapshot().lifecycle is ProtocolLifecycle.Active) {
                stack.protocol.adoptLatestIntent()
            }
        }
        emit(
            expectedStack,
            "SEMANTIC_${if (playing) "PLAY" else "PAUSE"}",
            UsbExclusiveShadowDecision.RAW_OBSERVED,
        )
        return true
    }

    fun observeUsbGeneration(generation: Long) {
        observeSafely(null, "USB_GENERATION") {
            val target = OutputTarget.UsbBound(UsbOutputGeneration(generation))
            synchronized(this) {
                stacks.values.forEach { stack ->
                    val snapshot = stack.protocol.snapshot()
                    if (
                        snapshot.lifecycle is ProtocolLifecycle.Active &&
                        snapshot.outputTarget != OutputTarget.SharedPcm
                    ) {
                        stack.protocol.updateOutputTarget(target)
                        emit(
                            stack,
                            "USB_GENERATION",
                            UsbExclusiveShadowDecision.RAW_OBSERVED,
                            detail = "actualGeneration=$generation",
                        )
                    }
                }
                if (currentOutputTarget != OutputTarget.SharedPcm) currentOutputTarget = target
            }
        }
    }

    fun observeSharedPcmOutput() {
        observeSafely(null, "OUTPUT_SHARED_PCM") { updateOutputTarget(OutputTarget.SharedPcm) }
    }

    fun observeUnavailableOutput() {
        observeSafely(null, "OUTPUT_UNAVAILABLE") { updateOutputTarget(OutputTarget.Unavailable) }
    }

    @Synchronized
    fun retireStack(stack: UsbExclusiveShadowStack) {
        observeSafely(stack, "STACK_RETIRING") {
            stack.protocol.beginRetiring()
            emit(stack, "STACK_RETIRING", UsbExclusiveShadowDecision.RAW_OBSERVED)
        }
    }

    @Synchronized
    fun diagnosticsSnapshot(): List<UsbExclusiveShadowDiagnostic> = diagnostics.toList()

    @Synchronized
    internal fun newAdapter(stack: UsbExclusiveShadowStack, kind: UsbExclusiveShadowAdapterKind): UsbExclusiveShadowAdapter {
        val id = AdapterInstanceId(++nextAdapterId)
        check(stack.protocol.registerAdapter(id))
        emit(stack, "ADAPTER_REGISTERED", UsbExclusiveShadowDecision.RAW_OBSERVED, id, detail = kind.name)
        return UsbExclusiveShadowAdapter(stack, id, kind)
    }

    internal fun observeSafely(stack: UsbExclusiveShadowStack?, event: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            emit(
                stack,
                event,
                UsbExclusiveShadowDecision.DIVERGENCE,
                detail = "shadow-exception=${error.javaClass.simpleName}",
            )
        }
    }

    @Synchronized
    internal fun emit(
        stack: UsbExclusiveShadowStack?,
        event: String,
        decision: UsbExclusiveShadowDecision,
        adapter: AdapterInstanceId? = null,
        occurrence: PlaybackOccurrence? = null,
        legacyCorrelationId: Long? = null,
        detail: String = "",
    ) {
        val snapshot = stack?.protocol?.snapshot()
        val record = UsbExclusiveShadowDiagnostic(
            sequence = ++nextEventSequence,
            stackId = snapshot?.stackId ?: PlaybackStackId(0),
            intentRevision = ledger.snapshot().revision.value,
            mutationId = snapshot?.mutation?.mutationId,
            adapterInstanceId = adapter,
            occurrence = occurrence,
            outputTarget = snapshot?.outputTarget ?: currentOutputTarget,
            rawEventKind = event,
            decision = decision,
            legacyCorrelationId = legacyCorrelationId,
            detail = detail,
        )
        diagnostics.addLast(record)
        while (diagnostics.size > MAX_DIAGNOSTICS) diagnostics.removeFirst()
        runCatching { diagnosticSink(record) }
    }

    @Synchronized
    private fun updateOutputTarget(target: OutputTarget) {
        currentOutputTarget = target
        stacks.values.forEach { stack ->
            if (stack.protocol.snapshot().lifecycle is ProtocolLifecycle.Active) {
                stack.protocol.updateOutputTarget(target)
                emit(stack, "OUTPUT_TARGET", UsbExclusiveShadowDecision.RAW_OBSERVED, detail = target.toString())
            }
        }
    }

    private companion object {
        const val MAX_DIAGNOSTICS = 512

        fun logDiagnostic(record: UsbExclusiveShadowDiagnostic) {
            val occurrence = record.occurrence?.let { "${it.periodUid.hashCode()}:${it.windowSequenceNumber}" } ?: "none"
            DiagnosticLog.event(
                "UsbExclusiveShadow",
                "seq=${record.sequence} stack=${record.stackId.value} intentRev=${record.intentRevision} " +
                    "mutation=${record.mutationId?.value ?: -1L} adapter=${record.adapterInstanceId?.value ?: -1L} " +
                    "occ=$occurrence output=${record.outputTarget} event=${record.rawEventKind} " +
                    "decision=${record.decision} legacy=${record.legacyCorrelationId ?: -1L} ${record.detail}",
            )
        }
    }
}

internal class UsbExclusiveShadowStack internal constructor(
    private val coordinator: UsbExclusiveShadowCoordinator,
    internal val protocol: UsbExclusivePlaybackProtocol,
) {
    private val periodToMediaId = linkedMapOf<Any, String>()
    private val pendingPcm = linkedMapOf<AdapterInstanceId, PcmConfigurePermit>()
    private val pendingDirect = linkedMapOf<AdapterInstanceId, DirectStagePermit>()
    private val directSeekCarrierBarriers = linkedMapOf<AdapterInstanceId, Pair<MutationId, PlaybackOccurrence>>()
    private data class RawStreamObservation(
        val occurrence: PlaybackOccurrence,
        val family: PlaybackFamily,
        val facts: String,
    )
    private val latestRawStreams = linkedMapOf<AdapterInstanceId, RawStreamObservation>()
    private var latestLegacyNavigationCorrelation: Long? = null

    fun newAdapter(kind: UsbExclusiveShadowAdapterKind): UsbExclusiveShadowAdapter =
        coordinator.newAdapter(this, kind)

    /** Publishes the application semantic intent through the service ledger before Exo dispatch. */
    fun publishSemanticIntent(playing: Boolean): Boolean {
        if (!coordinator.publishSemanticIntentFromStack(this, playing)) return false
        val snapshot = protocol.snapshot()
        return snapshot.lifecycle is ProtocolLifecycle.Active &&
            snapshot.adoptedIntent.desired == if (playing) PlaybackIntent.PLAY else PlaybackIntent.PAUSE
    }

    /**
     * Technical stack staging must re-adopt the current service intent before restoring Exo
     * execution. The caller deliberately applies the resulting execution bit to the underlying
     * Exo player, so this seam cannot publish a synthetic semantic PAUSE while staging.
     */
    fun restoreAfterTechnicalQuiesce(): IntentSnapshot {
        val fence = protocol.captureTechnicalIntentFence()
        return protocol.restoreAfterTechnicalQuiesce(fence)
    }

    /** Mints the manual epoch that gates a subsequent Exo navigation dispatch. */
    fun beginManualNavigation(targetMediaId: String, seam: String): MutationEpoch? {
        val epoch = runCatching { protocol.beginManualMutationUnbound(targetMediaId) }
            .onFailure {
                coordinator.emit(
                    this,
                    "MANUAL_NAVIGATION",
                    UsbExclusiveShadowDecision.DIVERGENCE,
                    detail = "protocol=${it.javaClass.simpleName} seam=$seam",
                )
            }
            .getOrNull()
        coordinator.emit(
            this,
            "MANUAL_NAVIGATION",
            if (epoch != null) UsbExclusiveShadowDecision.WOULD_PERMIT else UsbExclusiveShadowDecision.STALE_DROP,
            detail = "target=$targetMediaId seam=$seam destination=UNBOUND protocol-authority=true",
        )
        return epoch
    }

    fun observeManualNavigation(targetMediaId: String, seam: String) {
        coordinator.observeSafely(this, "MANUAL_NAVIGATION") {
            beginManualNavigation(targetMediaId, seam)
        }
    }

    fun observeLegacyNavigationCorrelation(requestId: Long) {
        coordinator.observeSafely(this, "LEGACY_NAV_CORRELATION") {
            latestLegacyNavigationCorrelation = requestId
            coordinator.emit(
                this,
                "LEGACY_NAV_CORRELATION",
                UsbExclusiveShadowDecision.RAW_OBSERVED,
                legacyCorrelationId = requestId,
                detail = "diagnostic-only",
            )
        }
    }

    fun beginSeek(targetSourcePositionUs: Long): MutationEpoch? {
        val snapshot = protocol.snapshot()
        val owned = snapshot.familyOwnership
        val source = owned.sourceIdentityOrNull()
        val mediaId = snapshot.applicationCurrent.mediaId
        if (source == null || mediaId == null || targetSourcePositionUs < 0L) {
            coordinator.emit(
                this,
                "SEEK_DISPATCH",
                UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
                occurrence = source?.occurrence,
                detail = "UNBOUND/NO_AUTHORITY sourcePositionUs=$targetSourcePositionUs",
            )
            return null
        }
        val epoch = protocol.beginMutation(
            kind = MutationKind.SEEK,
            targetMediaId = mediaId,
            targetFamily = source.family,
            targetFacts = source.facts,
            targetOccurrence = source.occurrence,
            causalHandleFactory = { id ->
                MutationCausalHandle(
                    snapshot.stackId,
                    id,
                    source.adapterInstanceId,
                    source.occurrence,
                    targetSourcePositionUs,
                )
            },
        )
        coordinator.emit(
            this,
            "SEEK_DISPATCH",
            if (epoch != null) UsbExclusiveShadowDecision.WOULD_PERMIT else UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
            adapter = source.adapterInstanceId,
            occurrence = source.occurrence,
            detail = "sourcePositionUs=$targetSourcePositionUs causal=${epoch?.causalHandle != null} protocol-authority=true",
        )
        return epoch
    }

    fun observeSeekDispatch(targetSourcePositionUs: Long) {
        coordinator.observeSafely(this, "SEEK_DISPATCH") {
            beginSeek(targetSourcePositionUs)
        }
    }

    fun observeApplicationMedia(mediaId: String?) {
        coordinator.observeSafely(this, "APPLICATION_MEDIA") {
            val snapshot = protocol.snapshot()
            val periodUid = mediaId?.let { id -> periodToMediaId.entries.firstOrNull { it.value == id }?.key }
            protocol.updateApplicationCurrent(mediaId, periodUid, null)
            coordinator.emit(
                this,
                "APPLICATION_MEDIA",
                UsbExclusiveShadowDecision.RAW_OBSERVED,
                detail = "mediaId=${mediaId ?: "none"}",
            )
        }
    }

    fun observeTimelinePeriod(mediaId: String, periodUid: Any?) {
        if (periodUid == null) return
        coordinator.observeSafely(this, "TIMELINE_PERIOD") {
            periodToMediaId[periodUid] = mediaId
            val current = protocol.snapshot().applicationCurrent
            if (current.mediaId == mediaId) {
                protocol.updateApplicationCurrent(mediaId, periodUid, current.occurrence?.takeIf { it.periodUid == periodUid })
            }
            coordinator.emit(
                this,
                "TIMELINE_PERIOD",
                UsbExclusiveShadowDecision.RAW_OBSERVED,
                detail = "mediaId=$mediaId period=${periodUid.hashCode()}",
            )
        }
    }

    fun observeCurrentPlayerOccurrence(mediaId: String?, occurrence: PlaybackOccurrence?) {
        coordinator.observeSafely(this, "CURRENT_PLAYER_OCCURRENCE") {
            val effectiveMediaId = mediaId ?: protocol.snapshot().applicationCurrent.mediaId
            protocol.updateApplicationCurrent(effectiveMediaId, occurrence?.periodUid, occurrence)
            var autoAdopted = false
            if (effectiveMediaId != null && occurrence != null) {
                val currentMutation = protocol.snapshot().mutation
                val manualOwnsTarget = currentMutation?.kind == MutationKind.MANUAL &&
                    currentMutation.targetMediaId == effectiveMediaId
                if (!manualOwnsTarget) autoAdopted = protocol.adoptAutoCandidate(effectiveMediaId, occurrence) != null
            }
            coordinator.emit(
                this,
                "CURRENT_PLAYER_OCCURRENCE",
                UsbExclusiveShadowDecision.RAW_OBSERVED,
                occurrence = occurrence,
                detail = "mediaId=${effectiveMediaId ?: "none"} autoAdopted=$autoAdopted",
            )
        }
    }

    fun snapshot(): UsbExclusiveProtocolSnapshot = protocol.snapshot()

    internal fun observeRawStream(
        adapter: UsbExclusiveShadowAdapter,
        occurrence: PlaybackOccurrence,
        family: PlaybackFamily,
        facts: String,
    ) {
        coordinator.observeSafely(this, "RENDERER_STREAM") {
            latestRawStreams[adapter.id] = RawStreamObservation(occurrence, family, facts)
            val before = protocol.snapshot()
            val mappedMediaId = periodToMediaId[occurrence.periodUid]
            val mutation = before.mutation
            val bound = if (
                mutation?.kind == MutationKind.MANUAL &&
                !mutation.destinationBound &&
                mappedMediaId == mutation.targetMediaId
            ) {
                protocol.bindManualDestination(mutation.mutationId, family, facts, occurrence)
            } else {
                false
            }
            if (!bound && mappedMediaId != null) {
                protocol.observeCandidate(
                    CandidateOccurrence(adapter.id, mappedMediaId, occurrence, family, facts),
                )
                val current = protocol.snapshot().applicationCurrent
                if (current.mediaId == mappedMediaId && current.occurrence == occurrence) {
                    val currentMutation = protocol.snapshot().mutation
                    val manualOwnsTarget = currentMutation?.kind == MutationKind.MANUAL &&
                        currentMutation.targetMediaId == mappedMediaId
                    if (!manualOwnsTarget) protocol.adoptAutoCandidate(mappedMediaId, occurrence)
                }
            }
            coordinator.emit(
                this,
                "RENDERER_STREAM",
                UsbExclusiveShadowDecision.RAW_OBSERVED,
                adapter.id,
                occurrence,
                latestLegacyNavigationCorrelation,
                "kind=${adapter.kind} family=$family bound=$bound facts=${facts.take(160)}",
            )
            val after = protocol.snapshot()
            if (after.familyOwnership !is FamilyOwnership.None &&
                after.familyOwnership.sourceIdentityOrNull()?.occurrence != occurrence
            ) {
                coordinator.emit(
                    this,
                    "RETAINED_HANDOFF",
                    UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
                    adapter.id,
                    occurrence,
                    detail = "PROOF_UNAVAILABLE",
                )
            }
        }
    }

    /**
     * M3 PCM acceptance seam. The caller must invoke this before touching the delegate sink; the
     * returned permit is the only authority that can be committed after that side effect.
     */
    internal fun preparePcmConfigure(
        adapter: UsbExclusiveShadowAdapter,
        occurrence: PlaybackOccurrence?,
        facts: String,
    ): PcmConfigurePermit? {
        if (occurrence == null) return null
        val mutation = protocol.snapshot().mutation ?: return null
        if (!mutation.destinationBound || mutation.targetFamily != PlaybackFamily.PCM) return null
        val permit = protocol.preparePcmConfigure(
            mutationId = mutation.mutationId,
            adapterInstanceId = adapter.id,
            occurrence = occurrence,
            // Renderer stream facts are the destination identity. Sink configure facts are a
            // later format projection and must not accidentally relabel that identity.
            facts = mutation.targetFacts,
        )
        coordinator.emit(
            this,
            "PCM_CONFIGURE_PERMIT",
            if (permit != null) UsbExclusiveShadowDecision.WOULD_PERMIT else UsbExclusiveShadowDecision.WOULD_DEFER,
            adapter.id,
            occurrence,
            detail = "production=true facts=${facts.take(160)}",
        )
        return permit
    }

    /** Commits the exact PCM side-effect receipt after the delegate configure returns. */
    internal fun commitPcmConfigure(
        adapter: UsbExclusiveShadowAdapter,
        permit: PcmConfigurePermit,
        occurrence: PlaybackOccurrence?,
        resourceIdentity: ResourceIdentity,
        facts: String,
    ): CommitDisposition {
        if (occurrence != permit.occurrence) return CommitDisposition.StaleNoEffect
        val result = protocol.commitPcmConfigure(
            permit,
            SideEffectReceipt.Completed(
                permit.activationId,
                resourceIdentity,
                facts,
            ),
        )
        coordinator.emit(
            this,
            "PCM_CONFIGURE_RECEIPT",
            if (result is CommitDisposition.CurrentPlaying || result is CommitDisposition.CurrentPaused) {
                UsbExclusiveShadowDecision.WOULD_PERMIT
            } else {
                UsbExclusiveShadowDecision.DIVERGENCE
            },
            adapter.id,
            occurrence,
            detail = "production=true disposition=${result.javaClass.simpleName}",
        )
        return result
    }

    internal fun failPcmConfigure(
        adapter: UsbExclusiveShadowAdapter,
        permit: PcmConfigurePermit,
        failure: String,
    ): CommitDisposition = protocol.commitPcmConfigure(
        permit,
        SideEffectReceipt.TerminalFailure(permit.activationId, null, failure),
    ).also { result ->
        coordinator.emit(
            this,
            "PCM_CONFIGURE_FAILURE_RECEIPT",
            UsbExclusiveShadowDecision.DIVERGENCE,
            adapter.id,
            permit.occurrence,
            detail = "production=true disposition=${result.javaClass.simpleName}",
        )
    }

    internal fun prepareRetainedPcmHandoff(
        adapter: UsbExclusiveShadowAdapter,
        occurrence: PlaybackOccurrence?,
    ): RetainedPcmHandoffPermit? {
        if (occurrence == null) return null
        val before = protocol.snapshot()
        val owned = before.familyOwnership as? FamilyOwnership.PcmOwned ?: return null
        val mutation = before.mutation ?: return null
        val observed = latestRawStreams[adapter.id] ?: return null
        if (
            observed.occurrence != occurrence ||
            observed.family != PlaybackFamily.PCM ||
            observed.facts.isBlank() ||
            !mutation.destinationBound ||
            mutation.targetFamily != PlaybackFamily.PCM ||
            mutation.targetOccurrence != occurrence ||
            mutation.targetFacts != observed.facts ||
            mutation.sourceOwnershipId != owned.ownershipId ||
            owned.adapterInstanceId != adapter.id
        ) return null

        val retirement = mutation.sourceRetirement ?: run {
            val receipt = protocol.mintRetirementReceipt(
                mutation.mutationId,
                adapter.id,
                RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED,
                FamilyProof.PcmRuntimeRetained(
                    runtimeIdentity = owned.runtimeIdentity,
                    compatibilityFacts = observed.facts,
                    tailOrderingProof =
                        "pcm-adapter-lease-drained:${owned.occurrence.windowSequenceNumber}->${occurrence.windowSequenceNumber}",
                ),
            ) ?: return null
            if (!protocol.acceptSourceRetirement(receipt)) return null
            protocol.snapshot().mutation?.sourceRetirement ?: return null
        }
        val proof = retirement.familyProof as? FamilyProof.PcmRuntimeRetained ?: return null
        if (
            retirement.scope != RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED ||
            proof.runtimeIdentity != owned.runtimeIdentity ||
            proof.compatibilityFacts != observed.facts ||
            proof.tailOrderingProof.isBlank()
        ) return null
        return protocol.prepareRetainedPcmHandoff(
            mutation.mutationId,
            adapter.id,
            occurrence,
            owned.runtimeIdentity,
        )
    }

    internal fun commitRetainedPcmHandoff(
        adapter: UsbExclusiveShadowAdapter,
        permit: RetainedPcmHandoffPermit,
    ): CommitDisposition {
        val result = protocol.commitRetainedPcmHandoff(permit)
        coordinator.emit(
            this,
            "PCM_RETAINED_HANDOFF_RECEIPT",
            if (result is CommitDisposition.CurrentPlaying || result is CommitDisposition.CurrentPaused) {
                UsbExclusiveShadowDecision.WOULD_PERMIT
            } else {
                UsbExclusiveShadowDecision.DIVERGENCE
            },
            adapter.id,
            permit.occurrence,
            detail = "production=true disposition=${result.javaClass.simpleName}",
        )
        return result
    }

    /** Exact PCM runtime teardown seam used by sink reset/release after the delegate side effect. */
    internal fun observePcmRuntimeReleased(
        adapter: UsbExclusiveShadowAdapter,
        occurrence: PlaybackOccurrence?,
        reason: String,
    ): Boolean {
        val snapshot = protocol.snapshot()
        val owned = snapshot.familyOwnership as? FamilyOwnership.PcmOwned ?: return false
        val mutation = snapshot.mutation ?: return false
        if (
            occurrence == null ||
            owned.adapterInstanceId != adapter.id ||
            owned.occurrence != occurrence ||
            mutation.sourceOwnershipId != owned.ownershipId ||
            mutation.sourceRetirement != null
        ) return false
        val receipt = protocol.mintRetirementReceipt(
            mutation.mutationId,
            adapter.id,
            com.mica.music.media.usb.protocol.RetirementScope.FAMILY_RUNTIME_RELEASED,
            com.mica.music.media.usb.protocol.FamilyProof.StackReleased(
                "observed-pcm-close:${owned.runtimeIdentity.value}:$reason",
            ),
        ) ?: return false
        return protocol.acceptSourceRetirement(receipt)
    }

    /** Enters the committed data-plane lease and returns the exact object that must be exited. */
    internal fun tryEnterWrite(
        adapter: UsbExclusiveShadowAdapter,
        occurrence: PlaybackOccurrence,
        writeKind: WriteKind,
    ): ActiveWriteLease? {
        val lease = protocol.currentWriteLease() ?: return null
        if (lease.identity.adapterInstanceId != adapter.id || lease.identity.occurrence != occurrence) return null
        return lease.takeIf {
            it.tryEnter(occurrence, lease.identity.mutationId, adapter.id, writeKind)
        }
    }

    internal fun prepareDirectStage(
        adapter: UsbExclusiveShadowAdapter,
        occurrence: PlaybackOccurrence?,
        stage: DirectStage,
        runtimeIdentity: RuntimeIdentity,
        carrierBarrierSatisfied: Boolean = false,
    ): DirectStagePermit? {
        if (occurrence == null) return null
        val mutation = protocol.snapshot().mutation ?: return null
        if (!mutation.destinationBound || mutation.targetFamily != PlaybackFamily.DOP) return null
        val exactSeekCarrierBarrier = mutation.kind == MutationKind.SEEK &&
            directSeekCarrierBarriers[adapter.id] == (mutation.mutationId to occurrence)
        val permit = protocol.prepareDirectStage(
            mutation.mutationId,
            adapter.id,
            occurrence,
            stage,
            runtimeIdentity,
            carrierBarrierSatisfied || exactSeekCarrierBarrier,
        )
        coordinator.emit(
            this,
            "DIRECT_${stage.name}_PERMIT",
            if (permit != null) UsbExclusiveShadowDecision.WOULD_PERMIT else UsbExclusiveShadowDecision.WOULD_DEFER,
            adapter.id,
            occurrence,
            detail = "production=true runtime=${runtimeIdentity.value}",
        )
        return permit
    }

    internal fun commitDirectStage(
        adapter: UsbExclusiveShadowAdapter,
        permit: DirectStagePermit,
        receipt: SideEffectReceipt,
    ): CommitDisposition? {
        val result = protocol.commitDirectStage(permit, receipt)
        coordinator.emit(
            this,
            "DIRECT_${permit.stage.name}_RECEIPT",
            if (result == null || result is CommitDisposition.CurrentPlaying || result is CommitDisposition.CurrentPaused) {
                UsbExclusiveShadowDecision.WOULD_PERMIT
            } else {
                UsbExclusiveShadowDecision.DIVERGENCE
            },
            adapter.id,
            permit.occurrence,
            detail = "production=true disposition=${result?.javaClass?.simpleName ?: "stage-progressed"}",
        )
        return result
    }

    internal fun prepareRetainedDirectHandoff(
        adapter: UsbExclusiveShadowAdapter,
        sourceOccurrence: PlaybackOccurrence?,
        targetOccurrence: PlaybackOccurrence?,
        runtimeIdentity: RuntimeIdentity,
    ): DirectRetainedHandoffPermit? {
        if (sourceOccurrence == null || targetOccurrence == null) return null
        val mutation = protocol.snapshot().mutation ?: return null
        val permit = protocol.prepareRetainedDirectHandoff(
            mutation.mutationId,
            adapter.id,
            sourceOccurrence,
            targetOccurrence,
            runtimeIdentity,
        )
        coordinator.emit(
            this,
            "DIRECT_RETAINED_HANDOFF_PERMIT",
            if (permit != null) UsbExclusiveShadowDecision.WOULD_PERMIT else UsbExclusiveShadowDecision.WOULD_DEFER,
            adapter.id,
            targetOccurrence,
            detail = "production=true runtime=${runtimeIdentity.value}",
        )
        return permit
    }

    internal fun commitRetainedDirectHandoff(
        adapter: UsbExclusiveShadowAdapter,
        permit: DirectRetainedHandoffPermit,
        receipt: SideEffectReceipt,
    ): CommitDisposition {
        val result = protocol.commitRetainedDirectHandoff(permit, receipt)
        coordinator.emit(
            this,
            "DIRECT_RETAINED_HANDOFF_RECEIPT",
            if (result is CommitDisposition.CurrentPlaying || result is CommitDisposition.CurrentPaused) {
                UsbExclusiveShadowDecision.WOULD_PERMIT
            } else {
                UsbExclusiveShadowDecision.DIVERGENCE
            },
            adapter.id,
            permit.targetOccurrence,
            detail = "production=true disposition=${result.javaClass.simpleName}",
        )
        return result
    }

    internal fun observePcmConfigureAttempt(
        adapter: UsbExclusiveShadowAdapter,
        occurrence: PlaybackOccurrence?,
        facts: String,
    ) {
        coordinator.observeSafely(this, "PCM_CONFIGURE_ATTEMPT") {
            coordinator.emit(
                this,
                "PCM_CONFIGURE_ATTEMPT",
                UsbExclusiveShadowDecision.RAW_OBSERVED,
                adapter.id,
                occurrence,
                latestLegacyNavigationCorrelation,
                "facts=${facts.take(160)}",
            )
            if (occurrence == null) {
                coordinator.emit(this, "PCM_CONFIGURE", UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE, adapter.id, detail = "occurrence=unknown")
                return@observeSafely
            }
            val mutation = protocol.snapshot().mutation
            if (mutation == null || !mutation.destinationBound || mutation.targetFamily != PlaybackFamily.PCM) {
                coordinator.emit(this, "PCM_CONFIGURE", UsbExclusiveShadowDecision.WOULD_DEFER, adapter.id, occurrence, detail = "no-exact-bound-pcm-mutation")
                return@observeSafely
            }
            val permit = protocol.preparePcmConfigure(
                mutation.mutationId,
                adapter.id,
                occurrence,
                mutation.targetFacts,
            )
            if (permit == null) {
                coordinator.emit(this, "PCM_CONFIGURE", UsbExclusiveShadowDecision.WOULD_DEFER, adapter.id, occurrence)
            } else {
                pendingPcm[adapter.id] = permit
                coordinator.emit(this, "PCM_CONFIGURE", UsbExclusiveShadowDecision.WOULD_PERMIT, adapter.id, occurrence)
            }
        }
    }

    internal fun observePcmConfigureCompleted(adapter: UsbExclusiveShadowAdapter, occurrence: PlaybackOccurrence?) {
        coordinator.observeSafely(this, "PCM_CONFIGURE_COMPLETED") {
            val permit = pendingPcm.remove(adapter.id)
            if (permit == null || occurrence == null || permit.occurrence != occurrence) {
                coordinator.emit(
                    this,
                    "PCM_CONFIGURE_COMPLETED",
                    UsbExclusiveShadowDecision.DIVERGENCE,
                    adapter.id,
                    occurrence,
                    detail = "legacy-success-without-exact-shadow-activation",
                )
                return@observeSafely
            }
            val result = protocol.commitPcmConfigure(
                permit,
                SideEffectReceipt.Completed(
                    permit.activationId,
                    ResourceIdentity("shadow-pcm-${permit.activationId.value}"),
                    "observed-legacy-configure-success",
                ),
            )
            coordinator.emit(
                this,
                "PCM_CONFIGURE_COMPLETED",
                when (result) {
                    is CommitDisposition.CurrentPlaying,
                    is CommitDisposition.CurrentPaused,
                    -> UsbExclusiveShadowDecision.WOULD_PERMIT
                    else -> UsbExclusiveShadowDecision.DIVERGENCE
                },
                adapter.id,
                occurrence,
                detail = result.javaClass.simpleName,
            )
        }
    }

    internal fun observeDirectPositionReset(
        adapter: UsbExclusiveShadowAdapter,
        occurrence: PlaybackOccurrence?,
        sourcePositionUs: Long,
    ) {
        coordinator.observeSafely(this, "DIRECT_POSITION_RESET") {
            val mutation = protocol.snapshot().mutation
            val handle = mutation?.causalHandle
            val matched = occurrence != null &&
                mutation?.kind == MutationKind.SEEK &&
                handle != null &&
                handle.adapterInstanceId == adapter.id &&
                handle.sourceOccurrence == occurrence &&
                handle.targetSourcePositionUs == sourcePositionUs
            if (matched) {
                directSeekCarrierBarriers[adapter.id] = mutation!!.mutationId to occurrence!!
            } else {
                directSeekCarrierBarriers.remove(adapter.id)
            }
            coordinator.emit(
                this,
                "DIRECT_POSITION_RESET",
                if (matched) UsbExclusiveShadowDecision.RAW_OBSERVED else UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
                adapter.id,
                occurrence,
                detail = "sourcePositionUs=$sourcePositionUs exactSeekBarrier=$matched",
            )
        }
    }

    internal fun observeDirectStage(
        adapter: UsbExclusiveShadowAdapter,
        occurrence: PlaybackOccurrence?,
        stage: DirectStage,
        runtimeIdentity: RuntimeIdentity,
        completed: Boolean,
    ) {
        coordinator.observeSafely(this, "DIRECT_${stage.name}") {
            if (occurrence == null) {
                coordinator.emit(this, "DIRECT_${stage.name}", UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE, adapter.id)
                return@observeSafely
            }
            val mutation = protocol.snapshot().mutation
            if (mutation == null || !mutation.destinationBound || mutation.targetFamily != PlaybackFamily.DOP) {
                coordinator.emit(this, "DIRECT_${stage.name}", UsbExclusiveShadowDecision.WOULD_DEFER, adapter.id, occurrence)
                return@observeSafely
            }
            val carrierBarrierSatisfied = stage == DirectStage.SOURCE_ACCEPT &&
                mutation.kind == MutationKind.SEEK &&
                directSeekCarrierBarriers[adapter.id] == (mutation.mutationId to occurrence)
            val permit = pendingDirect[adapter.id]?.takeIf { it.stage == stage }
                ?: protocol.prepareDirectStage(
                    mutation.mutationId,
                    adapter.id,
                    occurrence,
                    stage,
                    runtimeIdentity,
                    carrierBarrierSatisfied,
                )
            if (permit == null) {
                coordinator.emit(this, "DIRECT_${stage.name}", UsbExclusiveShadowDecision.WOULD_DEFER, adapter.id, occurrence)
                return@observeSafely
            }
            if (!completed) {
                pendingDirect[adapter.id] = permit
                coordinator.emit(this, "DIRECT_${stage.name}", UsbExclusiveShadowDecision.WOULD_PERMIT, adapter.id, occurrence, detail = "pending-observed-completion")
                return@observeSafely
            }
            pendingDirect.remove(adapter.id)
            val result = protocol.commitDirectStage(
                permit,
                SideEffectReceipt.Completed(
                    permit.activationId,
                    ResourceIdentity("shadow-direct-${permit.activationId.value}-${stage.name.lowercase()}"),
                    "observed-legacy-${stage.name.lowercase()}",
                    runtimeIdentity,
                ),
            )
            if (
                stage == DirectStage.SOURCE_ACCEPT &&
                (result is CommitDisposition.CurrentPlaying || result is CommitDisposition.CurrentPaused)
            ) {
                directSeekCarrierBarriers.remove(adapter.id)
            }
            coordinator.emit(
                this,
                "DIRECT_${stage.name}_COMPLETED",
                if (result == null || result is CommitDisposition.CurrentPlaying || result is CommitDisposition.CurrentPaused) {
                    UsbExclusiveShadowDecision.WOULD_PERMIT
                } else {
                    UsbExclusiveShadowDecision.DIVERGENCE
                },
                adapter.id,
                occurrence,
                detail = result?.javaClass?.simpleName ?: "stage-progressed",
            )
        }
    }

    internal fun acceptDirectStarted(adapter: UsbExclusiveShadowAdapter, occurrence: PlaybackOccurrence?): Boolean {
        if (occurrence == null) return false
        return protocol.observeAdapterStarted(adapter.id, occurrence)
    }

    internal fun observeDirectStarted(adapter: UsbExclusiveShadowAdapter, occurrence: PlaybackOccurrence?) {
        coordinator.observeSafely(this, "DIRECT_STARTED") {
            val accepted = acceptDirectStarted(adapter, occurrence)
            coordinator.emit(
                this,
                "DIRECT_STARTED",
                if (accepted) UsbExclusiveShadowDecision.RAW_OBSERVED else UsbExclusiveShadowDecision.STALE_DROP,
                adapter.id,
                occurrence,
            )
        }
    }

    internal fun observeDirectStopped(adapter: UsbExclusiveShadowAdapter, occurrence: PlaybackOccurrence?) {
        coordinator.observeSafely(this, "DIRECT_STOPPED") {
            coordinator.emit(this, "DIRECT_STOPPED", UsbExclusiveShadowDecision.RAW_OBSERVED, adapter.id, occurrence)
        }
    }

    internal fun observeDirectRuntimeReleased(
        adapter: UsbExclusiveShadowAdapter,
        occurrence: PlaybackOccurrence?,
        runtimeIdentity: RuntimeIdentity,
        reason: String,
    ) {
        coordinator.observeSafely(this, "DIRECT_RUNTIME_RELEASED") {
            val snapshot = protocol.snapshot()
            if (snapshot.lifecycle is ProtocolLifecycle.Retiring) {
                val receipt = protocol.mintRetiringDirectRuntimeReceipt(
                    sourceAdapterInstanceId = adapter.id,
                    sourceOccurrence = occurrence ?: run {
                        coordinator.emit(
                            this,
                            "DIRECT_RUNTIME_RELEASED",
                            UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
                            adapter.id,
                            occurrence,
                            detail = "reason=$reason runtime=${runtimeIdentity.value} no-exact-retiring-source",
                        )
                        return@observeSafely
                    },
                    runtimeIdentity = runtimeIdentity,
                    familyProof = com.mica.music.media.usb.protocol.FamilyProof.DirectFamilyReleased(
                        "observed-close:${runtimeIdentity.value}:$reason",
                    ),
                )
                val accepted = receipt != null && protocol.acceptRetiringDirectRuntimeReceipt(receipt)
                coordinator.emit(
                    this,
                    "DIRECT_RUNTIME_RELEASED",
                    if (accepted) UsbExclusiveShadowDecision.WOULD_PERMIT else UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
                    adapter.id,
                    occurrence,
                    detail = "reason=$reason runtime=${runtimeIdentity.value} retiringReceiptAccepted=$accepted",
                )
                return@observeSafely
            }
            val mutation = snapshot.mutation
            val owned = snapshot.familyOwnership as? FamilyOwnership.DopOwned
            if (
                occurrence == null ||
                mutation == null ||
                mutation.sourceRetirement != null ||
                mutation.sourceOwnershipId == null ||
                owned == null ||
                owned.ownershipId != mutation.sourceOwnershipId ||
                owned.adapterInstanceId != adapter.id ||
                owned.occurrence != occurrence ||
                owned.runtimeIdentity != runtimeIdentity
            ) {
                coordinator.emit(
                    this,
                    "DIRECT_RUNTIME_RELEASED",
                    UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
                    adapter.id,
                    occurrence,
                    detail = "reason=$reason runtime=${runtimeIdentity.value} no-exact-retiring-source",
                )
                return@observeSafely
            }
            val receipt = protocol.mintRetirementReceipt(
                mutation.mutationId,
                adapter.id,
                com.mica.music.media.usb.protocol.RetirementScope.FAMILY_RUNTIME_RELEASED,
                com.mica.music.media.usb.protocol.FamilyProof.DirectFamilyReleased(
                    "observed-close:${runtimeIdentity.value}:$reason",
                ),
            )
            val accepted = receipt != null && protocol.acceptSourceRetirement(receipt)
            coordinator.emit(
                this,
                "DIRECT_RUNTIME_RELEASED",
                if (accepted) UsbExclusiveShadowDecision.WOULD_PERMIT else UsbExclusiveShadowDecision.DIVERGENCE,
                adapter.id,
                occurrence,
                detail = "reason=$reason runtime=${runtimeIdentity.value} retirementAccepted=$accepted",
            )
        }
    }

    private data class OwnedSource(
        val adapterInstanceId: AdapterInstanceId,
        val occurrence: PlaybackOccurrence,
        val family: PlaybackFamily,
        val facts: String,
    )

    private fun FamilyOwnership.sourceIdentityOrNull(): OwnedSource? = when (this) {
        FamilyOwnership.None -> null
        is FamilyOwnership.PcmOwned -> OwnedSource(adapterInstanceId, occurrence, PlaybackFamily.PCM, facts)
        is FamilyOwnership.DopOwned -> OwnedSource(adapterInstanceId, occurrence, PlaybackFamily.DOP, facts)
    }
}

internal class UsbExclusiveShadowAdapter internal constructor(
    private val stack: UsbExclusiveShadowStack,
    internal val id: AdapterInstanceId,
    internal val kind: UsbExclusiveShadowAdapterKind,
) {
    fun snapshot(): UsbExclusiveProtocolSnapshot = stack.snapshot()

    /** Production permit before the wrapped PCM delegate is configured. */
    fun preparePcmConfigure(
        occurrence: PlaybackOccurrence?,
        facts: String,
    ): PcmConfigurePermit? = stack.preparePcmConfigure(this, occurrence, facts)

    /** Production receipt after the wrapped PCM delegate has been configured. */
    fun commitPcmConfigure(
        permit: PcmConfigurePermit,
        occurrence: PlaybackOccurrence?,
        resourceIdentity: ResourceIdentity,
        facts: String,
    ): CommitDisposition = stack.commitPcmConfigure(this, permit, occurrence, resourceIdentity, facts)

    fun failPcmConfigure(permit: PcmConfigurePermit, failure: String): CommitDisposition =
        stack.failPcmConfigure(this, permit, failure)

    fun prepareRetainedPcmHandoff(occurrence: PlaybackOccurrence?): RetainedPcmHandoffPermit? =
        stack.prepareRetainedPcmHandoff(this, occurrence)

    fun commitRetainedPcmHandoff(permit: RetainedPcmHandoffPermit): CommitDisposition =
        stack.commitRetainedPcmHandoff(this, permit)

    fun observePcmRuntimeReleased(occurrence: PlaybackOccurrence?, reason: String): Boolean =
        stack.observePcmRuntimeReleased(this, occurrence, reason)

    /** Enters the one committed lease; callers must exit the returned lease in a finally block. */
    fun tryEnterWrite(occurrence: PlaybackOccurrence, writeKind: WriteKind): ActiveWriteLease? =
        stack.tryEnterWrite(this, occurrence, writeKind)

    fun prepareDirectStage(
        occurrence: PlaybackOccurrence?,
        stage: DirectStage,
        runtimeIdentity: RuntimeIdentity,
        carrierBarrierSatisfied: Boolean = false,
    ): DirectStagePermit? = stack.prepareDirectStage(
        this,
        occurrence,
        stage,
        runtimeIdentity,
        carrierBarrierSatisfied,
    )

    fun commitDirectStage(
        permit: DirectStagePermit,
        receipt: SideEffectReceipt,
    ): CommitDisposition? = stack.commitDirectStage(this, permit, receipt)

    fun prepareRetainedDirectHandoff(
        sourceOccurrence: PlaybackOccurrence?,
        targetOccurrence: PlaybackOccurrence?,
        runtimeIdentity: RuntimeIdentity,
    ): DirectRetainedHandoffPermit? = stack.prepareRetainedDirectHandoff(
        this,
        sourceOccurrence,
        targetOccurrence,
        runtimeIdentity,
    )

    fun commitRetainedDirectHandoff(
        permit: DirectRetainedHandoffPermit,
        receipt: SideEffectReceipt,
    ): CommitDisposition = stack.commitRetainedDirectHandoff(this, permit, receipt)

    fun observeStream(occurrence: PlaybackOccurrence, family: PlaybackFamily, facts: String) {
        stack.observeRawStream(this, occurrence, family, facts)
    }

    fun observePcmConfigureAttempt(occurrence: PlaybackOccurrence?, facts: String) {
        stack.observePcmConfigureAttempt(this, occurrence, facts)
    }

    fun observePcmConfigureCompleted(occurrence: PlaybackOccurrence?) {
        stack.observePcmConfigureCompleted(this, occurrence)
    }

    fun observeDirectPositionReset(occurrence: PlaybackOccurrence?, sourcePositionUs: Long) {
        stack.observeDirectPositionReset(this, occurrence, sourcePositionUs)
    }

    fun observeDirectStage(
        occurrence: PlaybackOccurrence?,
        stage: DirectStage,
        runtimeIdentity: RuntimeIdentity,
        completed: Boolean,
    ) {
        stack.observeDirectStage(this, occurrence, stage, runtimeIdentity, completed)
    }

    fun observeDirectStarted(occurrence: PlaybackOccurrence?) {
        stack.observeDirectStarted(this, occurrence)
    }

    fun acceptDirectStarted(occurrence: PlaybackOccurrence?): Boolean =
        stack.acceptDirectStarted(this, occurrence)

    fun observeDirectStopped(occurrence: PlaybackOccurrence?) {
        stack.observeDirectStopped(this, occurrence)
    }

    fun observeDirectRuntimeReleased(
        occurrence: PlaybackOccurrence?,
        runtimeIdentity: RuntimeIdentity,
        reason: String,
    ) {
        stack.observeDirectRuntimeReleased(this, occurrence, runtimeIdentity, reason)
    }
}
