package com.mica.music.media.usb.shadow

import com.mica.music.media.usb.protocol.AdapterInstanceId
import com.mica.music.media.usb.protocol.ActiveWriteLease
import com.mica.music.media.usb.protocol.CandidateOccurrence
import com.mica.music.media.usb.protocol.CommitDisposition
import com.mica.music.media.usb.protocol.CleanupRequirement
import com.mica.music.media.usb.protocol.DirectStage
import com.mica.music.media.usb.protocol.DirectStagePermit
import com.mica.music.media.usb.protocol.DirectRetainedHandoffPermit
import com.mica.music.media.usb.protocol.DirectPhysicalRuntimeEndpoint
import com.mica.music.media.usb.protocol.FamilyProof
import com.mica.music.media.usb.protocol.FamilyOwnership
import com.mica.music.media.usb.protocol.MutationCausalHandle
import com.mica.music.media.usb.protocol.MutationId
import com.mica.music.media.usb.protocol.MutationKind
import com.mica.music.media.usb.protocol.MutationEpoch
import com.mica.music.media.usb.protocol.OutputTarget
import com.mica.music.media.usb.protocol.PcmAudioGeometry
import com.mica.music.media.usb.protocol.PcmConfigurePermit
import com.mica.music.media.usb.protocol.PcmRetirementPermit
import com.mica.music.media.usb.protocol.PlaybackFamily
import com.mica.music.media.usb.protocol.PlaybackIntent
import com.mica.music.media.usb.protocol.PlaybackIntentLedger
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.PlaybackStackId
import com.mica.music.media.usb.protocol.ProtocolLifecycle
import com.mica.music.media.usb.protocol.ProtocolTopologyReservation
import com.mica.music.media.usb.protocol.ResourceIdentity
import com.mica.music.media.usb.protocol.RetirementScope
import com.mica.music.media.usb.protocol.RetainedPcmHandoffPermit
import com.mica.music.media.usb.protocol.RuntimeIdentity
import com.mica.music.media.usb.protocol.SideEffectReceipt
import com.mica.music.media.usb.protocol.IntentSnapshot
import com.mica.music.media.usb.protocol.IntentRevision
import com.mica.music.media.usb.protocol.UsbExclusivePlaybackProtocol
import com.mica.music.media.usb.protocol.UsbExclusiveProtocolSnapshot
import com.mica.music.media.usb.protocol.UsbOutputGeneration
import com.mica.music.media.usb.protocol.TopologyCommitKind
import com.mica.music.media.usb.PlaybackOutputFacts
import com.mica.music.media.usb.UsbOutputPhase
import com.mica.music.media.usb.UsbPermissionState
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

@JvmInline
internal value class PlaybackTopologyEpoch(val value: Long)

internal data class PlaybackTopologyProducerToken(
    val stackId: PlaybackStackId,
    val epoch: PlaybackTopologyEpoch,
)

internal data class PlaybackTopologyMutationReservation(
    val producerToken: PlaybackTopologyProducerToken,
    val baseToken: PlaybackTopologyProducerToken,
    val seam: String,
    val protocolReservation: ProtocolTopologyReservation,
)

internal data class PlaybackTopologyPeriodFact(
    val windowIndex: Int,
    val mediaId: String,
    val periodUid: Any,
)

private const val MAX_QUARANTINED_RAW_STREAMS = 128
private val UNKNOWN_PCM_GEOMETRY = PcmAudioGeometry(-1, -1, -1, null)

internal sealed interface UsbExclusiveAuthorityObservation {
    data object Accepted : UsbExclusiveAuthorityObservation
    data object InsufficientEvidence : UsbExclusiveAuthorityObservation
    data object Rejected : UsbExclusiveAuthorityObservation
    data class Failed(val cause: Throwable) : UsbExclusiveAuthorityObservation
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
    private val authorityFaultInjector: ((String) -> Unit)? = null,
    private val diagnosticSink: (UsbExclusiveShadowDiagnostic) -> Unit = ::logDiagnostic,
) {
    internal val ledger = PlaybackIntentLedger()
    private var nextStackId = 0L
    private var nextAdapterId = 0L
    private var nextEventSequence = 0L
    private var currentOutputTarget: OutputTarget = OutputTarget.SharedPcm
    private var currentObservedP2Generation: Long? = null
    private var latestUsbFacts: PlaybackOutputFacts? = null
    private val stacks = linkedMapOf<PlaybackStackId, UsbExclusiveShadowStack>()
    private val diagnostics = ArrayDeque<UsbExclusiveShadowDiagnostic>()

    @Synchronized
    fun createStack(initialOutputTarget: OutputTarget = currentOutputTarget): UsbExclusiveShadowStack {
        val id = PlaybackStackId(++nextStackId)
        val usableLatestFacts = latestUsbFacts?.takeIf { facts ->
            currentObservedP2Generation != null && facts.generation == currentObservedP2Generation
        }
        val effectiveInitialOutput = when (initialOutputTarget) {
            OutputTarget.SharedPcm -> OutputTarget.SharedPcm
            OutputTarget.Unavailable,
            is OutputTarget.UsbBound,
            -> usableLatestFacts?.usableUsbTarget() ?: OutputTarget.Unavailable
        }
        val stack = UsbExclusiveShadowStack(
            coordinator = this,
            protocol = UsbExclusivePlaybackProtocol(ledger, id, effectiveInitialOutput),
        )
        stacks[id] = stack
        emit(
            stack,
            "STACK_BUILT",
            UsbExclusiveShadowDecision.RAW_OBSERVED,
            detail = "service-ledger-adopted initialOutput=$effectiveInitialOutput",
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

    @Synchronized
    fun observeUsbGeneration(generation: Long) {
        val previous = currentObservedP2Generation
        if (previous != null && generation < previous) {
            emit(
                null,
                "USB_GENERATION_STALE",
                UsbExclusiveShadowDecision.STALE_DROP,
                detail = "observed=$generation current=$previous",
            )
            return
        }
        currentObservedP2Generation = generation
        if (latestUsbFacts?.generation != generation) latestUsbFacts = null
        stacks.values.forEach { stack ->
            val snapshot = stack.protocol.snapshot()
            if (snapshot.lifecycle is ProtocolLifecycle.Active && snapshot.outputTarget != OutputTarget.SharedPcm) {
                stack.protocol.updateOutputTarget(OutputTarget.Unavailable)
                emit(
                    stack,
                    "USB_GENERATION_INVALIDATED",
                    UsbExclusiveShadowDecision.RAW_OBSERVED,
                    detail = "actualGeneration=$generation usable=false",
                )
            }
        }
        if (currentOutputTarget != OutputTarget.SharedPcm) currentOutputTarget = OutputTarget.Unavailable
    }

    @Synchronized
    fun observeUsbFacts(facts: PlaybackOutputFacts) {
        val currentGeneration = currentObservedP2Generation
        if (currentGeneration == null || facts.generation != currentGeneration) {
            stacks.values.forEach { stack ->
                if (stack.protocol.snapshot().lifecycle is ProtocolLifecycle.Active) {
                    emit(
                        stack,
                        "USB_OUTPUT_FACTS_STALE",
                        UsbExclusiveShadowDecision.STALE_DROP,
                        detail = "factsGeneration=${facts.generation} currentGeneration=${currentGeneration ?: -1L}",
                    )
                }
            }
            return
        }
        latestUsbFacts = facts
        val usable = facts.usableUsbTarget()
        val target: OutputTarget = usable ?: OutputTarget.Unavailable
        stacks.values.forEach { stack ->
            val snapshot = stack.protocol.snapshot()
            if (snapshot.lifecycle is ProtocolLifecycle.Active && snapshot.outputTarget != OutputTarget.SharedPcm) {
                stack.protocol.updateOutputTarget(target)
                emit(
                    stack,
                    "USB_OUTPUT_FACTS",
                    if (usable != null) UsbExclusiveShadowDecision.RAW_OBSERVED else UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
                    detail = "generation=${facts.generation} phase=${facts.phase} usable=${usable != null}",
                )
            }
        }
        if (currentOutputTarget != OutputTarget.SharedPcm) currentOutputTarget = target
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
                detail = "diagnostic-exception=${error.javaClass.simpleName}",
            )
        }
    }

    internal fun observeAuthority(
        stack: UsbExclusiveShadowStack,
        event: String,
        block: () -> UsbExclusiveAuthorityObservation,
    ): UsbExclusiveAuthorityObservation = try {
        authorityFaultInjector?.invoke(event)
        block()
    } catch (error: Throwable) {
        emit(
            stack,
            event,
            UsbExclusiveShadowDecision.DIVERGENCE,
            detail = "authority-exception=${error.javaClass.simpleName}",
        )
        UsbExclusiveAuthorityObservation.Failed(error)
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

    private fun PlaybackOutputFacts.usableUsbTarget(): OutputTarget.UsbBound? {
        if (
            phase != UsbOutputPhase.ACTIVE ||
            permission != UsbPermissionState.GRANTED ||
            !attached ||
            !claimed ||
            !exclusive ||
            !signalExact ||
            runtimeHandle == null ||
            request == null
        ) return null
        return OutputTarget.UsbBound(UsbOutputGeneration(generation))
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
    internal val streamProducerHandles = StreamProducerHandleRegistry(protocol.stackId)
    private var topologyEpoch = PlaybackTopologyEpoch(1)
    private var nextTopologyEpochValue = topologyEpoch.value
    private val periodFactsByEpoch = linkedMapOf<PlaybackTopologyEpoch, LinkedHashMap<Int, PlaybackTopologyPeriodFact>>()
    private val pendingPcm = linkedMapOf<AdapterInstanceId, PcmConfigurePermit>()
    private val pendingDirect = linkedMapOf<AdapterInstanceId, DirectStagePermit>()

    private data class RawStreamKey(
        val adapterInstanceId: AdapterInstanceId,
        val occurrence: PlaybackOccurrence,
        val topologyEpoch: PlaybackTopologyEpoch,
    )

    private data class RawStreamObservation(
        val key: RawStreamKey,
        val family: PlaybackFamily,
        val facts: String,
    )

    private data class UnscopedRawStreamObservation(
        val adapterInstanceId: AdapterInstanceId,
        val occurrence: PlaybackOccurrence,
        val family: PlaybackFamily,
        val facts: String,
        val reservationEpochAtDelivery: PlaybackTopologyEpoch?,
    )

    private data class ApplicationMediaFact(
        val topologyEpoch: PlaybackTopologyEpoch,
        val mediaId: String?,
        val windowIndex: Int?,
    )

    private data class EventTimeCurrentFact(
        val topologyEpoch: PlaybackTopologyEpoch,
        val mediaId: String?,
        val windowIndex: Int?,
        val occurrence: PlaybackOccurrence?,
    )

    private data class ManualDestinationExpectation(
        val mutationId: MutationId,
        val topologyEpoch: PlaybackTopologyEpoch,
        val targetMediaId: String,
        val targetWindowIndex: Int?,
        val expectedPeriodUid: Any?,
    )

    private data class StagedTopologyManualDestination(
        val targetMediaId: String,
        val targetWindowIndex: Int?,
        val expectedPeriodUid: Any?,
    )

    private data class PendingTopologyMutation(
        val reservation: PlaybackTopologyMutationReservation,
        var stagedManualDestination: StagedTopologyManualDestination? = null,
        var queueClear: Boolean = false,
    )

    private val rawStreams = linkedMapOf<RawStreamKey, RawStreamObservation>()
    private val unscopedRawStreams = linkedMapOf<Pair<AdapterInstanceId, PlaybackOccurrence>, UnscopedRawStreamObservation>()
    private val applicationMediaFacts = linkedMapOf<PlaybackTopologyEpoch, ApplicationMediaFact>()
    private val eventTimeCurrentFacts = linkedMapOf<PlaybackTopologyEpoch, EventTimeCurrentFact>()
    private var manualDestinationExpectation: ManualDestinationExpectation? = null
    private var pendingTopologyMutation: PendingTopologyMutation? = null
    private var latestLegacyNavigationCorrelation: Long? = null

    init {
        periodFactsByEpoch[topologyEpoch] = linkedMapOf()
        applicationMediaFacts[topologyEpoch] = ApplicationMediaFact(topologyEpoch, null, null)
        eventTimeCurrentFacts[topologyEpoch] = EventTimeCurrentFact(topologyEpoch, null, null, null)
    }

    fun newAdapter(kind: UsbExclusiveShadowAdapterKind): UsbExclusiveShadowAdapter =
        coordinator.newAdapter(this, kind)

    /** Publishes the application semantic intent through the service ledger before Exo dispatch. */
    fun publishSemanticIntent(playing: Boolean): Boolean {
        if (!coordinator.publishSemanticIntentFromStack(this, playing)) return false
        val snapshot = protocol.snapshot()
        return snapshot.lifecycle is ProtocolLifecycle.Active &&
            snapshot.adoptedIntent.desired == if (playing) PlaybackIntent.PLAY else PlaybackIntent.PAUSE
    }

    /** Captures semantic-intent revision before a publication-free technical execution window. */
    fun captureTechnicalIntentFence(): IntentRevision = protocol.captureTechnicalIntentFence()

    /**
     * Re-adopts the latest service intent at the end of a technical execution window. The captured
     * revision is only a fence/provenance value; it is never a resume grant.
     */
    fun restoreAfterTechnicalQuiesce(fence: IntentRevision): IntentSnapshot? {
        val latest = protocol.restoreAfterTechnicalQuiesce(fence)
        return latest.takeIf { protocol.snapshot().lifecycle is ProtocolLifecycle.Active }
    }

    /** Existing rebuild seam: no technical window is open here, so capture immediately before adopt. */
    fun restoreAfterTechnicalQuiesce(): IntentSnapshot =
        protocol.restoreAfterTechnicalQuiesce(captureTechnicalIntentFence())

    fun currentTopologyEpoch(): PlaybackTopologyEpoch = topologyEpoch

    fun currentTopologyToken(): PlaybackTopologyProducerToken =
        PlaybackTopologyProducerToken(protocol.snapshot().stackId, topologyEpoch)

    /**
     * Reserves producer provenance before the real application mutation without changing current
     * authority. Callers must commit only after the canonical Exo topology dispatch succeeds, or
     * abort on any pre-dispatch/dispatch failure. No framework/native side effect is held under a
     * coordinator lock by this transaction.
     */
    fun preparePlaybackTopologyMutation(
        seam: String,
        targetMediaId: String? = null,
        queueClear: Boolean = false,
    ): PlaybackTopologyMutationReservation? {
        if (pendingTopologyMutation != null) return null
        val base = currentTopologyToken()
        val kind = when {
            queueClear -> TopologyCommitKind.QUEUE_CLEAR
            targetMediaId != null -> TopologyCommitKind.MANUAL_TARGET
            else -> TopologyCommitKind.TOPOLOGY_ONLY
        }
        val protocolReservation = protocol.reserveTopologyMutation(seam, kind, targetMediaId) ?: return null
        val reservedEpoch = PlaybackTopologyEpoch(++nextTopologyEpochValue)
        val reservation = PlaybackTopologyMutationReservation(
            producerToken = PlaybackTopologyProducerToken(base.stackId, reservedEpoch),
            baseToken = base,
            seam = seam,
            protocolReservation = protocolReservation,
        )
        pendingTopologyMutation = PendingTopologyMutation(
            reservation = reservation,
            stagedManualDestination = targetMediaId?.let {
                StagedTopologyManualDestination(it, null, null)
            },
            queueClear = queueClear,
        )
        periodFactsByEpoch.putIfAbsent(reservedEpoch, linkedMapOf())
        applicationMediaFacts.putIfAbsent(reservedEpoch, ApplicationMediaFact(reservedEpoch, null, null))
        eventTimeCurrentFacts.putIfAbsent(reservedEpoch, EventTimeCurrentFact(reservedEpoch, null, null, null))
        coordinator.emit(
            this,
            "TOPOLOGY_PREPARE",
            UsbExclusiveShadowDecision.RAW_OBSERVED,
            detail = "base=${base.epoch.value} reserved=${reservedEpoch.value} seam=$seam kind=$kind",
        )
        return reservation
    }

    fun stageTopologyManualNavigation(
        reservation: PlaybackTopologyMutationReservation,
        targetMediaId: String,
        targetWindowIndex: Int? = null,
        expectedPeriodUid: Any? = null,
    ): Boolean {
        val pending = pendingTopologyMutation ?: return false
        val reserved = pending.stagedManualDestination ?: return false
        if (
            pending.reservation != reservation ||
            pending.queueClear ||
            reserved.targetMediaId != targetMediaId
        ) return false
        pending.stagedManualDestination = reserved.copy(
            targetWindowIndex = targetWindowIndex,
            expectedPeriodUid = expectedPeriodUid,
        )
        return true
    }

    fun stageTopologyQueueClear(reservation: PlaybackTopologyMutationReservation): Boolean {
        val pending = pendingTopologyMutation ?: return false
        return pending.reservation == reservation && pending.queueClear && pending.stagedManualDestination == null
    }

    fun markPlaybackTopologyDispatchSucceeded(
        reservation: PlaybackTopologyMutationReservation,
    ): UsbExclusiveAuthorityObservation = coordinator.observeAuthority(this, "TOPOLOGY_DISPATCHED") {
        val pending = pendingTopologyMutation
            ?: return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        if (pending.reservation != reservation) return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        if (!protocol.markTopologyDispatchSucceeded(reservation.protocolReservation)) {
            return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        }
        UsbExclusiveAuthorityObservation.Accepted
    }

    fun markPlaybackTopologyDispatchUncertain(
        reservation: PlaybackTopologyMutationReservation,
        reason: String,
    ): UsbExclusiveAuthorityObservation = coordinator.observeAuthority(this, "TOPOLOGY_DISPATCH_UNCERTAIN") {
        val pending = pendingTopologyMutation
            ?: return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        if (pending.reservation != reservation) return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        if (!protocol.markTopologyDispatchUncertain(reservation.protocolReservation)) {
            return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        }
        coordinator.emit(
            this,
            "TOPOLOGY_DISPATCH_UNCERTAIN",
            UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
            detail = "reserved=${reservation.producerToken.epoch.value} seam=${reservation.seam} reason=$reason",
        )
        UsbExclusiveAuthorityObservation.Accepted
    }

    fun commitPlaybackTopologyMutation(
        reservation: PlaybackTopologyMutationReservation,
    ): UsbExclusiveAuthorityObservation = coordinator.observeAuthority(this, "TOPOLOGY_COMMIT") {
        val pending = pendingTopologyMutation
            ?: return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        if (
            pending.reservation != reservation ||
            reservation.baseToken != currentTopologyToken() ||
            reservation.producerToken.stackId != protocol.snapshot().stackId
        ) return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        if (!protocol.commitTopologyMutation(reservation.protocolReservation)) {
            return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        }

        topologyEpoch = reservation.producerToken.epoch
        pendingTopologyMutation = null
        manualDestinationExpectation = pending.stagedManualDestination?.let { target ->
            val mutationId = reservation.protocolReservation.reservedMutationId
                ?: return@let null
            ManualDestinationExpectation(
                mutationId = mutationId,
                topologyEpoch = topologyEpoch,
                targetMediaId = target.targetMediaId,
                targetWindowIndex = target.targetWindowIndex,
                expectedPeriodUid = target.expectedPeriodUid,
            )
        }
        if (!pending.queueClear) protocol.updateApplicationCurrent(null, null, null)
        reconcileRawObservations()
        pruneHeavyTopologyFacts()
        coordinator.emit(
            this,
            "TOPOLOGY_COMMIT",
            UsbExclusiveShadowDecision.RAW_OBSERVED,
            detail = "epoch=${topologyEpoch.value} seam=${reservation.seam}",
        )
        UsbExclusiveAuthorityObservation.Accepted
    }

    fun abortPlaybackTopologyMutation(
        reservation: PlaybackTopologyMutationReservation,
        reason: String,
    ): UsbExclusiveAuthorityObservation = coordinator.observeAuthority(this, "TOPOLOGY_ABORT") {
        val pending = pendingTopologyMutation
            ?: return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        if (pending.reservation != reservation) return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        if (!protocol.abortTopologyMutation(reservation.protocolReservation)) {
            return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        }
        val abortedEpoch = reservation.producerToken.epoch
        pendingTopologyMutation = null
        periodFactsByEpoch.remove(abortedEpoch)
        applicationMediaFacts.remove(abortedEpoch)
        eventTimeCurrentFacts.remove(abortedEpoch)
        rawStreams.entries.removeAll { it.key.topologyEpoch == abortedEpoch }
        unscopedRawStreams.entries.removeAll { it.value.reservationEpochAtDelivery == abortedEpoch }
        coordinator.emit(
            this,
            "TOPOLOGY_ABORT",
            UsbExclusiveShadowDecision.STALE_DROP,
            detail = "reserved=${abortedEpoch.value} current=${topologyEpoch.value} seam=${reservation.seam} reason=$reason",
        )
        UsbExclusiveAuthorityObservation.Accepted
    }

    /** Compatibility/test seam. Production topology mutations use prepare/dispatch/commit. */
    fun advancePlaybackTopology(seam: String): UsbExclusiveAuthorityObservation {
        val reservation = preparePlaybackTopologyMutation(seam)
            ?: return UsbExclusiveAuthorityObservation.Rejected
        if (markPlaybackTopologyDispatchSucceeded(reservation) !is UsbExclusiveAuthorityObservation.Accepted) {
            return UsbExclusiveAuthorityObservation.Rejected
        }
        return commitPlaybackTopologyMutation(reservation)
    }

    /** Mints the manual mutation before Exo dispatch and retains exact target queue provenance. */
    fun beginManualNavigation(
        targetMediaId: String,
        seam: String,
        targetWindowIndex: Int? = null,
        expectedPeriodUid: Any? = null,
    ): MutationEpoch? {
        val epoch = try {
            protocol.beginManualMutationUnbound(targetMediaId)
        } catch (error: Throwable) {
            coordinator.emit(
                this,
                "MANUAL_NAVIGATION",
                UsbExclusiveShadowDecision.DIVERGENCE,
                detail = "authority-exception=${error.javaClass.simpleName} seam=$seam",
            )
            null
        }
        if (epoch != null) {
            manualDestinationExpectation = ManualDestinationExpectation(
                mutationId = epoch.mutationId,
                topologyEpoch = topologyEpoch,
                targetMediaId = targetMediaId,
                targetWindowIndex = targetWindowIndex,
                expectedPeriodUid = expectedPeriodUid,
            )
            reconcileRawObservations()
        }
        coordinator.emit(
            this,
            "MANUAL_NAVIGATION",
            if (epoch != null) UsbExclusiveShadowDecision.WOULD_PERMIT else UsbExclusiveShadowDecision.STALE_DROP,
            detail = "target=$targetMediaId window=${targetWindowIndex ?: -1} seam=$seam destination=UNBOUND topology=${topologyEpoch.value}",
        )
        return epoch
    }

    /** Fences an empty-queue dispatch while retaining exact source teardown provenance. */
    fun beginQueueClear(): Boolean = try {
        protocol.beginQueueClear().also { accepted ->
            coordinator.emit(
                this,
                "QUEUE_CLEAR",
                if (accepted) UsbExclusiveShadowDecision.WOULD_PERMIT else UsbExclusiveShadowDecision.STALE_DROP,
                detail = "protocol-authority=true source-teardown=${if (accepted) "fenced" else "blocked"}",
            )
        }
    } catch (error: Throwable) {
        coordinator.emit(this, "QUEUE_CLEAR", UsbExclusiveShadowDecision.DIVERGENCE, detail = "authority-exception=${error.javaClass.simpleName}")
        false
    }

    fun observeManualNavigation(targetMediaId: String, seam: String) {
        beginManualNavigation(targetMediaId, seam)
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
        val source = snapshot.familyOwnership.sourceIdentityOrNull()
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
        val epoch = try {
            protocol.beginMutation(
                kind = MutationKind.SEEK,
                targetMediaId = mediaId,
                targetFamily = source.family,
                targetFacts = source.facts,
                targetOccurrence = source.occurrence,
                destinationAdapterInstanceId = source.adapterInstanceId,
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
        } catch (error: Throwable) {
            coordinator.emit(this, "SEEK_DISPATCH", UsbExclusiveShadowDecision.DIVERGENCE, detail = "authority-exception=${error.javaClass.simpleName}")
            null
        }
        coordinator.emit(
            this,
            "SEEK_DISPATCH",
            if (epoch != null) UsbExclusiveShadowDecision.WOULD_PERMIT else UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
            adapter = source.adapterInstanceId,
            occurrence = source.occurrence,
            detail = "sourcePositionUs=$targetSourcePositionUs causal=${epoch?.causalHandle != null}",
        )
        return epoch
    }

    fun observeSeekDispatch(targetSourcePositionUs: Long) {
        beginSeek(targetSourcePositionUs)
    }

    fun observeApplicationMedia(
        mediaId: String?,
        windowIndex: Int? = null,
        producerToken: PlaybackTopologyProducerToken = currentTopologyToken(),
    ): UsbExclusiveAuthorityObservation = coordinator.observeAuthority(this, "APPLICATION_MEDIA") {
        val epoch = acceptedObservationEpoch(producerToken)
        if (epoch == null) {
            coordinator.emit(
                this,
                "APPLICATION_MEDIA",
                UsbExclusiveShadowDecision.STALE_DROP,
                detail = "mediaId=${mediaId ?: "none"} window=${windowIndex ?: -1} producer=${producerToken.epoch.value} current=${topologyEpoch.value}",
            )
            return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        }
        applicationMediaFacts[epoch] = ApplicationMediaFact(epoch, mediaId, windowIndex)
        if (epoch == topologyEpoch) {
            protocol.updateApplicationCurrent(mediaId, null, null)
            reconcileRawObservations()
        }
        coordinator.emit(
            this,
            "APPLICATION_MEDIA",
            UsbExclusiveShadowDecision.RAW_OBSERVED,
            detail = "mediaId=${mediaId ?: "none"} window=${windowIndex ?: -1} producer=${epoch.value} current=${topologyEpoch.value}",
        )
        UsbExclusiveAuthorityObservation.Accepted
    }

    /** onTimelineChanged publishes mapping facts only; it never creates a topology epoch. */
    fun observeTimelineSnapshot(
        facts: List<PlaybackTopologyPeriodFact>,
        reason: Int,
        producerToken: PlaybackTopologyProducerToken = currentTopologyToken(),
    ): UsbExclusiveAuthorityObservation = coordinator.observeAuthority(this, "TIMELINE_SNAPSHOT") {
        val normalized = facts.distinctBy { it.windowIndex }.sortedBy { it.windowIndex }
        val incoming = normalized.associateBy { it.windowIndex }
        val epoch = acceptedObservationEpoch(producerToken)
        if (epoch == null) {
            coordinator.emit(
                this,
                "TIMELINE_SNAPSHOT",
                UsbExclusiveShadowDecision.STALE_DROP,
                detail = "producer=${producerToken.epoch.value} current=${topologyEpoch.value} reason=$reason windows=${incoming.size}",
            )
            return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        }
        val existing = periodFactsByEpoch.getOrPut(epoch) { linkedMapOf() }
        val conflicts = existing.any { (index, fact) -> incoming[index]?.let { it != fact } == true } ||
            incoming.any { (index, fact) -> existing[index]?.let { it != fact } == true }
        if (conflicts) {
            coordinator.emit(
                this,
                "TIMELINE_SNAPSHOT",
                UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
                detail = "unexpected-structural-change producer=${epoch.value} current=${topologyEpoch.value} reason=$reason",
            )
            return@observeAuthority UsbExclusiveAuthorityObservation.InsufficientEvidence
        }
        // Placeholder/partial realization is monotonic inside one producer topology: later exact
        // mappings may fill previously unresolved windows but may not rewrite an existing fact.
        incoming.forEach { (index, fact) -> existing.putIfAbsent(index, fact) }
        if (epoch == topologyEpoch) reconcileRawObservations()
        coordinator.emit(
            this,
            "TIMELINE_SNAPSHOT",
            UsbExclusiveShadowDecision.RAW_OBSERVED,
            detail = "producer=${epoch.value} current=${topologyEpoch.value} reason=$reason windows=${incoming.size}",
        )
        UsbExclusiveAuthorityObservation.Accepted
    }

    /** Compatibility/test seam; production publishes an exact full timeline snapshot. */
    fun observeTimelinePeriod(
        mediaId: String,
        periodUid: Any?,
        producerToken: PlaybackTopologyProducerToken = currentTopologyToken(),
    ) {
        if (periodUid == null) return
        val epoch = acceptedObservationEpoch(producerToken) ?: return
        val periodFacts = periodFactsByEpoch.getOrPut(epoch) { linkedMapOf() }
        val existing = periodFacts.values.firstOrNull { it.mediaId == mediaId && it.periodUid == periodUid }
        if (existing == null) {
            val index = (periodFacts.keys.maxOrNull() ?: -1) + 1
            periodFacts[index] = PlaybackTopologyPeriodFact(index, mediaId, periodUid)
        }
        if (epoch == topologyEpoch) reconcileRawObservations()
    }

    fun observeEventTimeCurrent(
        windowIndex: Int?,
        mediaId: String?,
        occurrence: PlaybackOccurrence?,
        producerToken: PlaybackTopologyProducerToken = currentTopologyToken(),
    ): UsbExclusiveAuthorityObservation = coordinator.observeAuthority(this, "CURRENT_PLAYER_EVENT_TIME") {
        val epoch = acceptedObservationEpoch(producerToken)
        if (epoch == null) {
            occurrence?.let { staleOccurrence ->
                unscopedRawStreams.entries.removeAll { it.key.second == staleOccurrence }
            }
            coordinator.emit(
                this,
                "CURRENT_PLAYER_EVENT_TIME",
                UsbExclusiveShadowDecision.STALE_DROP,
                occurrence = occurrence,
                detail = "producer=${producerToken.epoch.value} current=${topologyEpoch.value} mediaId=${mediaId ?: "none"} window=${windowIndex ?: -1}",
            )
            return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
        }
        eventTimeCurrentFacts[epoch] = EventTimeCurrentFact(epoch, mediaId, windowIndex, occurrence)
        if (epoch != topologyEpoch) {
            return@observeAuthority UsbExclusiveAuthorityObservation.Accepted
        }
        if (occurrence == null) {
            protocol.updateApplicationCurrent(applicationMediaFacts[topologyEpoch]?.mediaId, null, null)
        }
        val result = reconcileRawObservations()
        coordinator.emit(
            this,
            "CURRENT_PLAYER_EVENT_TIME",
            if (occurrence != null && result) UsbExclusiveShadowDecision.RAW_OBSERVED else UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
            occurrence = occurrence,
            detail = "mediaId=${mediaId ?: "none"} window=${windowIndex ?: -1} producer=${epoch.value} current=${topologyEpoch.value}",
        )
        if (occurrence != null && result) UsbExclusiveAuthorityObservation.Accepted else UsbExclusiveAuthorityObservation.InsufficientEvidence
    }

    /** Compatibility seam for deterministic tests; no global Player getter is consulted. */
    fun observeCurrentPlayerOccurrence(mediaId: String?, occurrence: PlaybackOccurrence?) {
        val periodFacts = periodFactsByEpoch[topologyEpoch].orEmpty()
        val mapping = occurrence?.let { occ ->
            periodFacts.values.singleOrNull { it.periodUid == occ.periodUid && (mediaId == null || it.mediaId == mediaId) }
        }
        observeEventTimeCurrent(
            mapping?.windowIndex,
            mediaId ?: mapping?.mediaId,
            occurrence,
            currentTopologyToken(),
        )
    }

    fun snapshot(): UsbExclusiveProtocolSnapshot = protocol.snapshot()

    internal fun observeRawStream(
        adapter: UsbExclusiveShadowAdapter,
        occurrence: PlaybackOccurrence,
        family: PlaybackFamily,
        facts: String,
        producerToken: PlaybackTopologyProducerToken? = null,
    ): UsbExclusiveAuthorityObservation = coordinator.observeAuthority(this, "RENDERER_STREAM") {
        if (producerToken != null) {
            val epoch = acceptedObservationEpoch(producerToken)
            if (epoch == null) {
                coordinator.emit(
                    this,
                    "RENDERER_STREAM",
                    UsbExclusiveShadowDecision.STALE_DROP,
                    adapter.id,
                    occurrence,
                    detail = "producer=${producerToken.epoch.value} current=${topologyEpoch.value}",
                )
                return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
            }
            if (!storeScopedRawStream(adapter.id, occurrence, family, facts, epoch)) {
                return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
            }
        } else {
            val unscopedKey = adapter.id to occurrence
            val observation = UnscopedRawStreamObservation(
                adapter.id,
                occurrence,
                family,
                facts,
                pendingTopologyMutation?.reservation?.producerToken?.epoch,
            )
            val previous = unscopedRawStreams[unscopedKey]
            if (previous != null && (previous.family != family || previous.facts != facts)) {
                coordinator.emit(
                    this,
                    "RENDERER_STREAM",
                    UsbExclusiveShadowDecision.DIVERGENCE,
                    adapter.id,
                    occurrence,
                    detail = "same-unscoped-key-conflicting-stream-facts",
                )
                return@observeAuthority UsbExclusiveAuthorityObservation.Rejected
            }
            if (previous == null) {
                unscopedRawStreams[unscopedKey] = observation
                while (unscopedRawStreams.size > MAX_QUARANTINED_RAW_STREAMS) {
                    unscopedRawStreams.remove(unscopedRawStreams.keys.first())
                }
            }
        }
        val joined = reconcileRawObservations()
        val scopedEpochs = rawStreams.keys
            .filter { it.adapterInstanceId == adapter.id && it.occurrence == occurrence }
            .map { it.topologyEpoch.value }
        coordinator.emit(
            this,
            "RENDERER_STREAM",
            if (scopedEpochs.isEmpty()) UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE else UsbExclusiveShadowDecision.RAW_OBSERVED,
            adapter.id,
            occurrence,
            latestLegacyNavigationCorrelation,
            "kind=${adapter.kind} family=$family joined=$joined producer=${producerToken?.epoch?.value ?: -1L} scoped=$scopedEpochs current=${topologyEpoch.value} facts=${facts.take(160)}",
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
        UsbExclusiveAuthorityObservation.Accepted
    }

    private fun acceptedObservationEpoch(token: PlaybackTopologyProducerToken): PlaybackTopologyEpoch? {
        if (token.stackId != protocol.snapshot().stackId) return null
        if (token.epoch == topologyEpoch) return topologyEpoch
        val pendingEpoch = pendingTopologyMutation?.reservation?.producerToken?.epoch
        return token.epoch.takeIf { it == pendingEpoch }
    }

    private fun pruneHeavyTopologyFacts() {
        val keep = buildSet {
            add(topologyEpoch)
            pendingTopologyMutation?.reservation?.producerToken?.epoch?.let(::add)
        }
        periodFactsByEpoch.keys.retainAll(keep)
        applicationMediaFacts.keys.retainAll(keep)
        eventTimeCurrentFacts.keys.retainAll(keep)
        rawStreams.entries.removeAll { it.key.topologyEpoch !in keep }
    }

    private fun storeScopedRawStream(
        adapterInstanceId: AdapterInstanceId,
        occurrence: PlaybackOccurrence,
        family: PlaybackFamily,
        facts: String,
        epoch: PlaybackTopologyEpoch,
    ): Boolean {
        val key = RawStreamKey(adapterInstanceId, occurrence, epoch)
        val observation = RawStreamObservation(key, family, facts)
        val previous = rawStreams[key]
        if (previous != null && previous != observation) {
            coordinator.emit(
                this,
                "RENDERER_STREAM",
                UsbExclusiveShadowDecision.DIVERGENCE,
                adapterInstanceId,
                occurrence,
                detail = "same-key-conflicting-stream-facts producer=${epoch.value}",
            )
            return false
        }
        rawStreams[key] = observation
        return true
    }

    private fun reconcileRawObservations(): Boolean {
        if (protocol.snapshot().lifecycle !is ProtocolLifecycle.Active) return false
        var changed = false
        val periodFacts = periodFactsByEpoch[topologyEpoch].orEmpty()
        val currentEpochStreams = rawStreams.values.filter { it.key.topologyEpoch == topologyEpoch }
        val expectation = manualDestinationExpectation?.takeIf { it.topologyEpoch == topologyEpoch }
        val mutation = protocol.snapshot().mutation
        if (
            expectation != null &&
            mutation?.mutationId == expectation.mutationId &&
            mutation.kind == MutationKind.MANUAL &&
            !mutation.destinationBound
        ) {
            val eligible = currentEpochStreams.filter { stream ->
                val mappings = periodFacts.values.filter { mapping ->
                    mapping.periodUid == stream.key.occurrence.periodUid &&
                        mapping.mediaId == expectation.targetMediaId &&
                        (expectation.targetWindowIndex == null || mapping.windowIndex == expectation.targetWindowIndex) &&
                        (expectation.expectedPeriodUid == null || mapping.periodUid == expectation.expectedPeriodUid)
                }
                mappings.size == 1
            }
            val exactEvent = eventTimeCurrentFacts[topologyEpoch]?.takeIf { event ->
                event.occurrence != null &&
                    (event.mediaId == null || event.mediaId == expectation.targetMediaId) &&
                    (expectation.targetWindowIndex == null || event.windowIndex == expectation.targetWindowIndex)
            }
            val selected = when {
                eligible.size == 1 -> eligible.single()
                exactEvent != null -> eligible.singleOrNull { it.key.occurrence == exactEvent.occurrence }
                else -> null
            }
            if (selected != null) {
                changed = protocol.bindManualDestination(
                    expectation.mutationId,
                    selected.key.adapterInstanceId,
                    selected.family,
                    selected.facts,
                    selected.key.occurrence,
                ) || changed
            }
        }

        currentEpochStreams.forEach { stream ->
            val mappings = periodFacts.values.filter { it.periodUid == stream.key.occurrence.periodUid }
            if (mappings.size == 1) {
                val mapping = mappings.single()
                protocol.observeCandidate(
                    CandidateOccurrence(
                        stream.key.adapterInstanceId,
                        mapping.mediaId,
                        stream.key.occurrence,
                        stream.family,
                        stream.facts,
                    ),
                )
            }
        }

        val app = applicationMediaFacts[topologyEpoch]
        val event = eventTimeCurrentFacts[topologyEpoch]
        if (app != null && event != null && event.occurrence != null) {
            val mapping = event.windowIndex?.let(periodFacts::get)
            val exact = mapping != null &&
                mapping.periodUid == event.occurrence.periodUid &&
                (event.mediaId == null || event.mediaId == mapping.mediaId) &&
                (app.mediaId == null || app.mediaId == mapping.mediaId) &&
                (app.windowIndex == null || app.windowIndex == mapping.windowIndex)
            if (exact) {
                protocol.updateApplicationCurrent(mapping.mediaId, mapping.periodUid, event.occurrence)
                val currentMutation = protocol.snapshot().mutation
                val manualOwnsTarget = currentMutation?.kind == MutationKind.MANUAL &&
                    currentMutation.targetMediaId == mapping.mediaId
                val alreadyAdopted = currentMutation?.kind == MutationKind.AUTO_NEXT &&
                    currentMutation.targetMediaId == mapping.mediaId &&
                    currentMutation.targetOccurrence == event.occurrence
                if (!manualOwnsTarget && !alreadyAdopted) {
                    changed = (protocol.adoptAutoCandidate(mapping.mediaId, event.occurrence) != null) || changed
                }
                changed = true
            }
        }
        return changed
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
        geometry: PcmAudioGeometry,
    ): CommitDisposition {
        if (occurrence != permit.occurrence) return CommitDisposition.StaleNoEffect
        val result = protocol.commitPcmConfigure(
            permit,
            SideEffectReceipt.Completed(
                permit.activationId,
                resourceIdentity,
                facts,
            ),
            geometry,
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
        resourceIdentity: ResourceIdentity,
        failure: String,
    ): CommitDisposition = protocol.commitPcmConfigure(
        permit,
        SideEffectReceipt.TerminalFailure(permit.activationId, resourceIdentity, failure),
        UNKNOWN_PCM_GEOMETRY,
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

    internal fun preparePcmRetainedRetirement(
        adapter: UsbExclusiveShadowAdapter,
        occurrence: PlaybackOccurrence?,
        geometry: PcmAudioGeometry,
    ): PcmRetirementPermit? {
        if (occurrence == null) return null
        val before = protocol.snapshot()
        val owned = before.familyOwnership as? FamilyOwnership.PcmOwned ?: return null
        val mutation = before.mutation ?: return null
        val observed = rawStreams[RawStreamKey(adapter.id, occurrence, topologyEpoch)] ?: return null
        if (
            observed.family != PlaybackFamily.PCM ||
            observed.facts.isBlank() ||
            !mutation.destinationBound ||
            mutation.targetFamily != PlaybackFamily.PCM ||
            mutation.targetOccurrence != occurrence ||
            mutation.targetFacts != observed.facts ||
            mutation.sourceOwnershipId != owned.ownershipId ||
            owned.adapterInstanceId != adapter.id
        ) return null
        return protocol.preparePcmSourceRetirement(
            mutationId = mutation.mutationId,
            sourceAdapterInstanceId = adapter.id,
            targetOccurrence = occurrence,
            targetGeometry = geometry,
            scope = RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED,
        )
    }

    internal fun completePcmRetainedRetirement(
        adapter: UsbExclusiveShadowAdapter,
        retirement: PcmRetirementPermit,
        proof: FamilyProof.PcmRuntimeRetained,
    ): RetainedPcmHandoffPermit? {
        if (retirement.source.adapterInstanceId != adapter.id) return null
        if (!protocol.completePcmRetirement(retirement, proof)) return null
        val occurrence = retirement.targetOccurrence ?: return null
        return protocol.prepareRetainedPcmHandoff(
            retirement.retiringMutationId,
            adapter.id,
            occurrence,
            retirement.source.runtimeIdentity,
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

    internal fun cleanupRequirements(
        adapter: UsbExclusiveShadowAdapter,
        activationId: com.mica.music.media.usb.protocol.ActivationId,
    ): List<CleanupRequirement> = protocol.cleanupRequirementsFor(adapter.id, activationId)

    internal fun completeCleanup(
        adapter: UsbExclusiveShadowAdapter,
        activationId: com.mica.music.media.usb.protocol.ActivationId,
        resourceIdentity: ResourceIdentity,
    ): CommitDisposition? = protocol.completeCleanup(adapter.id, activationId, resourceIdentity)

    /** Freezes an active PCM source for a real delegate reset before successor configure. */
    internal fun preparePcmFullRelease(
        adapter: UsbExclusiveShadowAdapter,
        targetOccurrence: PlaybackOccurrence?,
        targetGeometry: PcmAudioGeometry,
    ): PcmRetirementPermit? {
        val snapshot = protocol.snapshot()
        val owned = snapshot.familyOwnership as? FamilyOwnership.PcmOwned ?: return null
        val mutation = snapshot.mutation ?: return null
        if (
            targetOccurrence == null ||
            owned.adapterInstanceId != adapter.id ||
            mutation.sourceOwnershipId != owned.ownershipId ||
            mutation.targetOccurrence != targetOccurrence ||
            mutation.sourceRetirement != null
        ) return null
        return protocol.preparePcmSourceRetirement(
            mutationId = mutation.mutationId,
            sourceAdapterInstanceId = adapter.id,
            targetOccurrence = targetOccurrence,
            targetGeometry = targetGeometry,
            scope = RetirementScope.FAMILY_RUNTIME_RELEASED,
        )
    }

    /** Freezes the exact PCM source already fenced by stack Retiring. */
    internal fun prepareRetiringPcmFullRelease(
        adapter: UsbExclusiveShadowAdapter,
    ): PcmRetirementPermit? = protocol.prepareRetiringPcmRuntimeRelease(adapter.id)

    /** Accepts only a sink-issued typed proof for the exact frozen PCM source. */
    internal fun completePcmFullRelease(
        adapter: UsbExclusiveShadowAdapter,
        retirement: PcmRetirementPermit,
        proof: FamilyProof.PcmFamilyReleased,
    ): Boolean {
        if (retirement.source.adapterInstanceId != adapter.id) return false
        return protocol.completePcmRetirement(retirement, proof)
    }

    /** Enters the committed data-plane lease and returns the exact object that must be exited. */
    internal fun tryEnterWrite(
        adapter: UsbExclusiveShadowAdapter,
        occurrence: PlaybackOccurrence,
        writeKind: WriteKind,
    ): ActiveWriteLease? = protocol.tryEnterWrite(adapter.id, occurrence, writeKind)

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
        val permit = protocol.prepareDirectStage(
            mutation.mutationId,
            adapter.id,
            occurrence,
            stage,
            runtimeIdentity,
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
                UNKNOWN_PCM_GEOMETRY,
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
            if (occurrence == null) {
                coordinator.emit(
                    this,
                    "DIRECT_POSITION_RESET",
                    UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
                    adapter.id,
                    occurrence,
                    detail = "sourcePositionUs=$sourcePositionUs pendingSeekReset=false",
                )
                return@observeSafely
            }
            val matched = protocol.notePendingDirectSeekReset(
                adapter.id,
                occurrence,
                sourcePositionUs,
            )
            coordinator.emit(
                this,
                "DIRECT_POSITION_RESET",
                if (matched) UsbExclusiveShadowDecision.RAW_OBSERVED else UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
                adapter.id,
                occurrence,
                detail = "sourcePositionUs=$sourcePositionUs pendingSeekReset=$matched",
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
            val permit = pendingDirect[adapter.id]?.takeIf { it.stage == stage }
                ?: protocol.prepareDirectStage(
                    mutation.mutationId,
                    adapter.id,
                    occurrence,
                    stage,
                    runtimeIdentity,
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
        reason: String = "runtime-released",
    ) {
        coordinator.observeSafely(this, "DIRECT_RUNTIME_RELEASED") {
            if (occurrence == null) {
                coordinator.emit(
                    this,
                    "DIRECT_RUNTIME_RELEASED",
                    UsbExclusiveShadowDecision.INSUFFICIENT_EVIDENCE,
                    adapter.id,
                    occurrence,
                    detail = "reason=$reason runtime=${runtimeIdentity.value} typed-proof-missing",
                )
                return@observeSafely
            }
            val snapshot = protocol.snapshot()
            if (snapshot.lifecycle is ProtocolLifecycle.Retiring) {
                val accepted = protocol.completeRetiringDirectFamilyReleaseFromEndpoint(
                    sourceAdapterInstanceId = adapter.id,
                    sourceOccurrence = occurrence,
                    runtimeIdentity = runtimeIdentity,
                )
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
            val accepted = protocol.completeDirectFamilyReleaseFromEndpoint(
                mutation.mutationId,
                adapter.id,
                runtimeIdentity,
            )
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
        geometry: PcmAudioGeometry,
    ): CommitDisposition = stack.commitPcmConfigure(this, permit, occurrence, resourceIdentity, facts, geometry)

    fun failPcmConfigure(
        permit: PcmConfigurePermit,
        resourceIdentity: ResourceIdentity,
        failure: String,
    ): CommitDisposition = stack.failPcmConfigure(this, permit, resourceIdentity, failure)

    fun cleanupRequirements(
        activationId: com.mica.music.media.usb.protocol.ActivationId,
    ): List<CleanupRequirement> = stack.cleanupRequirements(this, activationId)

    fun completeCleanup(
        activationId: com.mica.music.media.usb.protocol.ActivationId,
        resourceIdentity: ResourceIdentity,
    ): CommitDisposition? = stack.completeCleanup(this, activationId, resourceIdentity)

    fun preparePcmRetainedRetirement(
        occurrence: PlaybackOccurrence?,
        geometry: PcmAudioGeometry,
    ): PcmRetirementPermit? = stack.preparePcmRetainedRetirement(this, occurrence, geometry)

    fun completePcmRetainedRetirement(
        retirement: PcmRetirementPermit,
        proof: FamilyProof.PcmRuntimeRetained,
    ): RetainedPcmHandoffPermit? = stack.completePcmRetainedRetirement(this, retirement, proof)

    fun commitRetainedPcmHandoff(permit: RetainedPcmHandoffPermit): CommitDisposition =
        stack.commitRetainedPcmHandoff(this, permit)

    fun preparePcmFullRelease(
        targetOccurrence: PlaybackOccurrence?,
        targetGeometry: PcmAudioGeometry,
    ): PcmRetirementPermit? = stack.preparePcmFullRelease(this, targetOccurrence, targetGeometry)

    fun prepareRetiringPcmFullRelease(): PcmRetirementPermit? =
        stack.prepareRetiringPcmFullRelease(this)

    fun completePcmFullRelease(
        retirement: PcmRetirementPermit,
        proof: FamilyProof.PcmFamilyReleased,
    ): Boolean = stack.completePcmFullRelease(this, retirement, proof)

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

    fun redeemDirectStage(permit: DirectStagePermit): DirectStagePermit? =
        stack.protocol.redeemDirectStage(permit)

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

    fun attachDirectPhysicalEndpoint(
        runtimeIdentity: RuntimeIdentity,
        endpoint: DirectPhysicalRuntimeEndpoint,
    ): Boolean = stack.protocol.attachDirectPhysicalEndpoint(id, runtimeIdentity, endpoint)

    fun commitRetainedDirectHandoff(
        permit: DirectRetainedHandoffPermit,
        receipt: SideEffectReceipt,
    ): CommitDisposition = stack.commitRetainedDirectHandoff(this, permit, receipt)

    fun observeStream(
        occurrence: PlaybackOccurrence,
        family: PlaybackFamily,
        facts: String,
        producerToken: PlaybackTopologyProducerToken? = null,
        producerHandle: StreamProducerHandle? = null,
    ): UsbExclusiveAuthorityObservation {
        val exactProducer = exactStreamProducerToken(occurrence, producerToken, producerHandle)
        return stack.observeRawStream(this, occurrence, family, facts, exactProducer)
    }

    private fun exactStreamProducerToken(
        occurrence: PlaybackOccurrence,
        producerToken: PlaybackTopologyProducerToken?,
        producerHandle: StreamProducerHandle?,
    ): PlaybackTopologyProducerToken? {
        if (producerHandle != null) {
            return producerHandle.takeIf {
                it.stackId == stack.protocol.stackId && it.occurrence == occurrence
            }?.producerToken
        }
        return producerToken
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
        reason: String = "runtime-released",
    ) {
        stack.observeDirectRuntimeReleased(this, occurrence, runtimeIdentity, reason)
    }
}
