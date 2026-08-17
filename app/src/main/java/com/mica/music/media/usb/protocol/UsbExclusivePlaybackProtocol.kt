package com.mica.music.media.usb.protocol

data class PlaybackStackId(val value: Long)
data class UsbOutputGeneration(val value: Long)
data class MutationId(val value: Long)
data class AdapterInstanceId(val value: Long)
data class FamilyOwnershipId(val value: Long)
data class ActivationId(val value: Long)
data class ResourceIdentity(val value: String)
data class SideEffectReceiptId(val value: Long)
data class PlaybackOccurrence(val periodUid: Any, val windowSequenceNumber: Long)
data class RuntimeIdentity(val value: String)
data class RequestAlias(val value: Long)
data class TopologyReservationId(val value: Long)

data class PcmAudioGeometry(
    val sampleRate: Int,
    val channelCount: Int,
    val pcmEncoding: Int,
    val outputChannels: List<Int>?,
)

enum class PcmDelegateTerminal { RESET_COMPLETED, RELEASE_COMPLETED }

data class PcmTailOrderingProof(
    val sourceOccurrence: PlaybackOccurrence,
    val targetOccurrence: PlaybackOccurrence,
    val sinkBoundarySequence: Long,
)

data class PcmPhysicalSourceIdentity(
    val familyOwnershipId: FamilyOwnershipId,
    val mutationId: MutationId,
    val occurrence: PlaybackOccurrence,
    val adapterInstanceId: AdapterInstanceId,
    val runtimeIdentity: RuntimeIdentity,
    val outputTarget: OutputTarget,
    val geometry: PcmAudioGeometry,
)

data class PcmRetirementPermit(
    val retiringMutationId: MutationId,
    val source: PcmPhysicalSourceIdentity,
    val scope: RetirementScope,
    val targetOccurrence: PlaybackOccurrence?,
    val targetGeometry: PcmAudioGeometry?,
)

enum class PlaybackFamily { PCM, DOP }
enum class MutationKind { MANUAL, AUTO_NEXT, SEEK, OUTPUT_REBUILD }
enum class WriteKind { PCM_DATA, DOP_CONTENT, DOP_GAP }
enum class RetirementScope {
    SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED,
    FAMILY_RUNTIME_RELEASED,
    STACK_TEARDOWN_RELEASED,
}
enum class CleanupContinuation { RETRY_SAME_MUTATION, TERMINAL }
enum class DirectStage { CREATE_RUNTIME, PREFILL, ARM, SOURCE_ACCEPT }
enum class TopologyCommitKind { TOPOLOGY_ONLY, MANUAL_TARGET, QUEUE_CLEAR }
enum class TopologyTransactionPhase { RESERVED_FENCED, DISPATCHED, RECONCILIATION_REQUIRED }

data class ProtocolTopologyReservation(
    val reservationId: TopologyReservationId,
    val stackId: PlaybackStackId,
    val seam: String,
    val kind: TopologyCommitKind,
    val targetMediaId: String?,
    val reservedMutationId: MutationId?,
)

data class TopologyTransactionSnapshot(
    val reservation: ProtocolTopologyReservation,
    val phase: TopologyTransactionPhase,
    val retirementLatched: Boolean,
)

sealed interface OutputTarget {
    data object SharedPcm : OutputTarget
    data class UsbBound(val generation: UsbOutputGeneration) : OutputTarget
    data object Unavailable : OutputTarget
}

sealed interface ProtocolLifecycle {
    data object Active : ProtocolLifecycle
    data class Retiring(val inFlight: Set<ActivationId>) : ProtocolLifecycle
    data object Retired : ProtocolLifecycle
}

data class ApplicationCurrent(
    val mediaId: String?,
    val periodUid: Any?,
    val occurrence: PlaybackOccurrence?,
)

data class CandidateOccurrence(
    val adapterInstanceId: AdapterInstanceId,
    val mediaId: String,
    val occurrence: PlaybackOccurrence,
    val family: PlaybackFamily,
    val facts: String,
)

data class MutationCausalHandle(
    val stackId: PlaybackStackId,
    val mutationId: MutationId,
    val adapterInstanceId: AdapterInstanceId,
    val sourceOccurrence: PlaybackOccurrence,
    val targetSourcePositionUs: Long,
)

data class MutationEpoch(
    val mutationId: MutationId,
    val kind: MutationKind,
    val requestAlias: RequestAlias?,
    val sourceOwnershipId: FamilyOwnershipId?,
    val sourceOccurrence: PlaybackOccurrence?,
    val targetMediaId: String,
    val targetOccurrence: PlaybackOccurrence?,
    val targetFamily: PlaybackFamily,
    val targetFacts: String,
    val destinationAdapterInstanceId: AdapterInstanceId? = null,
    val destinationBound: Boolean = true,
    val causalHandle: MutationCausalHandle? = null,
    val sourceRetirement: SourceRetirementReceipt? = null,
)

sealed interface FamilyProof {
    data class PcmFamilyReleased(
        val runtimeIdentity: RuntimeIdentity,
        val sourceGeometry: PcmAudioGeometry,
        val terminal: PcmDelegateTerminal,
        val sinkBoundarySequence: Long,
    ) : FamilyProof

    data class PcmRuntimeRetained(
        val runtimeIdentity: RuntimeIdentity,
        val sourceGeometry: PcmAudioGeometry,
        val targetGeometry: PcmAudioGeometry,
        val tailOrdering: PcmTailOrderingProof,
    ) : FamilyProof

    data class DirectFamilyReleased(val proof: String) : FamilyProof
    data class DirectRuntimeRetained(val runtimeIdentity: RuntimeIdentity, val proof: String) : FamilyProof
    data class StackReleased(val proof: String) : FamilyProof
}

data class SourceRetirementReceipt(
    val receiptId: SideEffectReceiptId,
    val retiringMutationId: MutationId,
    val sourceFamilyOwnershipId: FamilyOwnershipId,
    val sourceFamily: PlaybackFamily,
    val sourceOccurrence: PlaybackOccurrence?,
    val sourceAdapterInstanceId: AdapterInstanceId,
    val outputTarget: OutputTarget,
    val scope: RetirementScope,
    val semanticPausedAtRetirement: Boolean,
    val familyProof: FamilyProof,
)

data class WriteLeaseIdentity(
    val stackId: PlaybackStackId,
    val outputTarget: OutputTarget,
    val mutationId: MutationId,
    val occurrence: PlaybackOccurrence,
    val adapterInstanceId: AdapterInstanceId,
    val familyOwnershipId: FamilyOwnershipId,
    val activationId: ActivationId,
    val family: PlaybackFamily,
)

/** Revocable/drainable final data-plane capability. */
class ActiveWriteLease internal constructor(val identity: WriteLeaseIdentity) {
    private var revoked = false
    private var semanticPaused = false
    private var entered = 0
    private var activeDopWriteKind: WriteKind = WriteKind.DOP_CONTENT
    private var pendingDopWriteKind: WriteKind? = null
    private var onDrained: (() -> Unit)? = null

    internal fun setOnDrained(callback: () -> Unit) {
        synchronized(this) {
            onDrained = callback
        }
    }

    @Synchronized
    fun updateSemanticPaused(paused: Boolean) {
        if (identity.family == PlaybackFamily.DOP) {
            val target = if (paused) WriteKind.DOP_GAP else WriteKind.DOP_CONTENT
            if (entered == 0) {
                activeDopWriteKind = target
                pendingDopWriteKind = null
            } else if (target == activeDopWriteKind) {
                pendingDopWriteKind = null
            } else {
                pendingDopWriteKind = target
            }
        }
        semanticPaused = paused
    }

    @Synchronized
    fun tryEnter(
        occurrence: PlaybackOccurrence,
        mutationId: MutationId,
        adapterInstanceId: AdapterInstanceId,
        writeKind: WriteKind,
    ): Boolean {
        if (revoked) return false
        if (identity.occurrence != occurrence || identity.mutationId != mutationId || identity.adapterInstanceId != adapterInstanceId) {
            return false
        }
        val allowed = when (identity.family) {
            PlaybackFamily.PCM -> !semanticPaused && writeKind == WriteKind.PCM_DATA
            PlaybackFamily.DOP -> pendingDopWriteKind == null && writeKind == activeDopWriteKind
        }
        if (!allowed) return false
        entered += 1
        return true
    }

    fun exit() {
        val callback = synchronized(this) {
            check(entered > 0) { "write lease exit without enter" }
            entered -= 1
            if (entered == 0) {
                pendingDopWriteKind?.let { activeDopWriteKind = it }
                pendingDopWriteKind = null
                onDrained
            } else {
                null
            }
        }
        callback?.invoke()
    }

    @Synchronized
    fun revoke() {
        revoked = true
    }

    @Synchronized
    fun isDrained(): Boolean = entered == 0

    @Synchronized
    fun isRevoked(): Boolean = revoked

    @Synchronized
    internal fun hasPendingModeDrain(): Boolean = pendingDopWriteKind != null
}

sealed interface FamilyOwnership {
    data object None : FamilyOwnership

    data class PcmOwned(
        val ownershipId: FamilyOwnershipId,
        val mutationId: MutationId,
        val occurrence: PlaybackOccurrence,
        val adapterInstanceId: AdapterInstanceId,
        val semanticPaused: Boolean,
        val runtimeIdentity: RuntimeIdentity,
        val facts: String,
        val geometry: PcmAudioGeometry,
        val writeLease: ActiveWriteLease,
    ) : FamilyOwnership

    data class DopOwned(
        val ownershipId: FamilyOwnershipId,
        val mutationId: MutationId,
        val occurrence: PlaybackOccurrence,
        val adapterInstanceId: AdapterInstanceId,
        val semanticPaused: Boolean,
        val runtimeIdentity: RuntimeIdentity,
        val facts: String,
        val writeLease: ActiveWriteLease,
    ) : FamilyOwnership
}

data class PcmConfigurePermit(
    val activationId: ActivationId,
    val mutationId: MutationId,
    val adapterInstanceId: AdapterInstanceId,
    val occurrence: PlaybackOccurrence,
    val facts: String,
    val outputTarget: OutputTarget,
    val adoptedIntentRevision: IntentRevision,
)

data class RetainedPcmHandoffPermit(
    val activationId: ActivationId,
    val mutationId: MutationId,
    val adapterInstanceId: AdapterInstanceId,
    val occurrence: PlaybackOccurrence,
    val runtimeIdentity: RuntimeIdentity,
    val outputTarget: OutputTarget,
)

data class DirectStagePermit(
    val activationId: ActivationId,
    val mutationId: MutationId,
    val adapterInstanceId: AdapterInstanceId,
    val occurrence: PlaybackOccurrence,
    val stage: DirectStage,
    val runtimeIdentity: RuntimeIdentity,
    val outputTarget: OutputTarget,
    val adoptedIntentRevision: IntentRevision,
)

/**
 * Exact permit for a source-intake boundary on one retained Direct runtime. It is distinct from
 * CREATE/PREFILL/ARM/SOURCE_ACCEPT: no runtime is created, but the old occurrence is still
 * revoked before the carrier/source reset and the new occurrence is committed afterwards.
 */
data class DirectRetainedHandoffPermit(
    val activationId: ActivationId,
    val mutationId: MutationId,
    val adapterInstanceId: AdapterInstanceId,
    val sourceOccurrence: PlaybackOccurrence,
    val targetOccurrence: PlaybackOccurrence,
    val runtimeIdentity: RuntimeIdentity,
    val outputTarget: OutputTarget,
    val adoptedIntentRevision: IntentRevision,
)

sealed interface SideEffectReceipt {
    val activationId: ActivationId

    data class NotStarted(override val activationId: ActivationId) : SideEffectReceipt
    data class Completed(
        override val activationId: ActivationId,
        val resourceIdentity: ResourceIdentity,
        val facts: String,
        val runtimeIdentity: RuntimeIdentity? = null,
    ) : SideEffectReceipt

    data class PartialNeedsCleanup(
        override val activationId: ActivationId,
        val resourceIdentity: ResourceIdentity,
        val facts: String,
        val runtimeIdentity: RuntimeIdentity? = null,
    ) : SideEffectReceipt

    data class TerminalFailure(
        override val activationId: ActivationId,
        val resourceIdentity: ResourceIdentity?,
        val failure: String,
        val runtimeIdentity: RuntimeIdentity? = null,
    ) : SideEffectReceipt
}

sealed interface CommitDisposition {
    data class CurrentPlaying(val familyOwnershipId: FamilyOwnershipId, val writeLease: ActiveWriteLease) : CommitDisposition
    data class CurrentPaused(val familyOwnershipId: FamilyOwnershipId, val writeLease: ActiveWriteLease) : CommitDisposition
    data object RetryPendingSameMutation : CommitDisposition
    data class CurrentCleanupRequired(val resourceIdentity: ResourceIdentity, val afterCleanup: CleanupContinuation) : CommitDisposition
    data object StaleNoEffect : CommitDisposition
    data class StaleCleanupRequired(val resourceIdentity: ResourceIdentity) : CommitDisposition
    data class RetiringCleanupRequired(val resourceIdentity: ResourceIdentity) : CommitDisposition
    data object TerminalFailure : CommitDisposition
}

data class CleanupRequirement(
    val activationId: ActivationId,
    val resourceIdentity: ResourceIdentity,
    val continuation: CleanupContinuation?,
    val retiring: Boolean,
)

data class DirectActivationState(
    val activationId: ActivationId,
    val mutationId: MutationId,
    val adapterInstanceId: AdapterInstanceId,
    val occurrence: PlaybackOccurrence,
    val runtimeIdentity: RuntimeIdentity,
    val completedStageResources: Map<DirectStage, ResourceIdentity>,
    val pendingStage: DirectStage?,
    val carrierBarrierSatisfied: Boolean,
)

data class UsbExclusiveProtocolSnapshot(
    val lifecycle: ProtocolLifecycle,
    val stackId: PlaybackStackId,
    val adoptedIntent: IntentSnapshot,
    val outputTarget: OutputTarget,
    val applicationCurrent: ApplicationCurrent,
    val mutation: MutationEpoch?,
    val familyOwnership: FamilyOwnership,
    val candidates: Set<CandidateOccurrence>,
    val inFlightActivations: Set<ActivationId>,
    val cleanupRequirements: Set<ResourceIdentity>,
    val topologyTransaction: TopologyTransactionSnapshot?,
)

/**
 * FROZEN_V1 M1 pure protocol model.
 *
 * This reducer owns only logical authority. It performs no Media3, USB, Native, sink or Direct
 * runtime side effects. All externally executed work is represented by finite permits/receipts.
 */
class UsbExclusivePlaybackProtocol(
    private val ledger: PlaybackIntentLedger,
    val stackId: PlaybackStackId,
    initialOutputTarget: OutputTarget,
) {
    private var lifecycle: ProtocolLifecycle = ProtocolLifecycle.Active
    private var adoptedIntent: IntentSnapshot = ledger.snapshot()
    private var outputTarget: OutputTarget = initialOutputTarget
    private var applicationCurrent = ApplicationCurrent(null, null, null)
    private val adapters = linkedSetOf<AdapterInstanceId>()
    private val startedAuthorities = linkedMapOf<AdapterInstanceId, StartedAuthority>()
    private val candidates = linkedSetOf<CandidateOccurrence>()
    private var nextMutationId = 0L
    private var nextActivationId = 0L
    private var nextOwnershipId = 0L
    private var nextReceiptId = 0L
    private val issuedRetirementReceiptsByMutation = linkedMapOf<MutationId, SourceRetirementReceipt>()
    private var mutation: MutationEpoch? = null
    private var familyOwnership: FamilyOwnership = FamilyOwnership.None
    private val activations = linkedMapOf<ActivationId, ActivationRecord>()
    private val cleanupRequirements = linkedMapOf<ResourceIdentity, CleanupRequirement>()
    private var directActivation: DirectActivationState? = null
    private var preparedPcmRetirement: PcmRetirementPermit? = null
    private var retiringPcmRuntimeRelease: RetiringPcmRuntimeRelease? = null
    private var issuedRetiringPcmRuntimeReceipt: SourceRetirementReceipt? = null
    private var retiringDirectRuntimeRelease: RetiringDirectRuntimeRelease? = null
    private var issuedRetiringDirectRuntimeReceipt: SourceRetirementReceipt? = null
    private var nextTopologyReservationId = 0L
    private var topologyTransaction: TopologyTransaction? = null

    private data class StartedAuthority(
        val mutationId: MutationId,
        val occurrence: PlaybackOccurrence,
        val activationId: ActivationId,
    )

    private data class ActivationRecord(
        val activationId: ActivationId,
        val mutationId: MutationId,
        val adapterInstanceId: AdapterInstanceId,
        val occurrence: PlaybackOccurrence,
        val family: PlaybackFamily,
        val outputTarget: OutputTarget,
        val kind: ActivationKind,
    )

    /** Frozen PCM physical identity that must be proved terminal before Retired. */
    private data class RetiringPcmRuntimeRelease(
        val source: PcmPhysicalSourceIdentity,
        val observedFamilyProof: FamilyProof.PcmFamilyReleased? = null,
    )

    /**
     * Teardown evidence for a committed Direct family survives the source mutation epoch.
     * The committed mutation id is provenance, not a new mutation authority.
     */
    private data class RetiringDirectRuntimeRelease(
        val sourceFamilyOwnershipId: FamilyOwnershipId,
        val sourceMutationId: MutationId,
        val sourceOccurrence: PlaybackOccurrence,
        val sourceAdapterInstanceId: AdapterInstanceId,
        val runtimeIdentity: RuntimeIdentity,
        val outputTarget: OutputTarget,
        /** Raw exact release evidence held until the committed lease is actually drained. */
        val observedFamilyProof: FamilyProof.DirectFamilyReleased? = null,
    )

    private data class CapturedTopologySource(
        val ownershipId: FamilyOwnershipId,
        val mutationId: MutationId,
        val occurrence: PlaybackOccurrence,
        val adapterInstanceId: AdapterInstanceId,
        val family: PlaybackFamily,
        val facts: String,
        val runtimeIdentity: RuntimeIdentity,
        val writeLease: ActiveWriteLease,
    )

    private data class TopologyCommitPlan(
        val kind: TopologyCommitKind,
        val targetMediaId: String?,
        val reservedMutationId: MutationId?,
        val capturedSource: CapturedTopologySource?,
    )

    private data class TopologyTransaction(
        val reservation: ProtocolTopologyReservation,
        val plan: TopologyCommitPlan,
        var phase: TopologyTransactionPhase = TopologyTransactionPhase.RESERVED_FENCED,
        var retirementLatched: Boolean = false,
    )

    private enum class ActivationKind { PCM_CONFIGURE, RETAINED_PCM, RETAINED_DIRECT, DIRECT }

    @Synchronized
    fun registerAdapter(adapterInstanceId: AdapterInstanceId): Boolean {
        if (lifecycle !is ProtocolLifecycle.Active) return false
        adapters += adapterInstanceId
        return true
    }

    @Synchronized
    fun observeAdapterStarted(adapterInstanceId: AdapterInstanceId, occurrence: PlaybackOccurrence): Boolean {
        if (lifecycle !is ProtocolLifecycle.Active || topologyTransaction != null || adapterInstanceId !in adapters) return false
        val currentMutation = mutation ?: return false
        if (currentMutation.targetOccurrence != occurrence) return false
        val direct = directActivation ?: return false
        if (
            direct.mutationId != currentMutation.mutationId ||
            direct.adapterInstanceId != adapterInstanceId ||
            direct.occurrence != occurrence
        ) return false
        startedAuthorities[adapterInstanceId] = StartedAuthority(currentMutation.mutationId, occurrence, direct.activationId)
        return true
    }

    @Synchronized
    fun adoptLatestIntent(): IntentSnapshot {
        if (lifecycle !is ProtocolLifecycle.Active) return adoptedIntent
        val latest = ledger.snapshot()
        adoptedIntent = latest
        familyOwnership = when (val owned = familyOwnership) {
            FamilyOwnership.None -> owned
            is FamilyOwnership.PcmOwned -> {
                val paused = latest.desired == PlaybackIntent.PAUSE
                owned.writeLease.updateSemanticPaused(paused)
                owned.copy(semanticPaused = paused)
            }
            is FamilyOwnership.DopOwned -> {
                val paused = latest.desired == PlaybackIntent.PAUSE
                owned.writeLease.updateSemanticPaused(paused)
                owned.copy(semanticPaused = paused)
            }
        }
        return latest
    }

    @Synchronized
    fun captureTechnicalIntentFence(): IntentRevision = ledger.snapshot().revision

    @Synchronized
    fun restoreAfterTechnicalQuiesce(@Suppress("UNUSED_PARAMETER") captured: IntentRevision): IntentSnapshot =
        if (lifecycle is ProtocolLifecycle.Active) adoptLatestIntent() else adoptedIntent

    /**
     * Acquires the one protocol-owned topology fence. This is the only fallible authority gate for
     * a real application topology side effect: all state needed by the later commit is captured
     * here, before the caller leaves the protocol lock to invoke Media3.
     */
    @Synchronized
    fun reserveTopologyMutation(
        seam: String,
        kind: TopologyCommitKind,
        targetMediaId: String? = null,
    ): ProtocolTopologyReservation? {
        if (
            lifecycle !is ProtocolLifecycle.Active ||
            topologyTransaction != null ||
            activations.isNotEmpty() ||
            cleanupRequirements.isNotEmpty()
        ) return null
        if (seam.isBlank()) return null
        if (kind == TopologyCommitKind.MANUAL_TARGET && targetMediaId.isNullOrBlank()) return null
        if (kind != TopologyCommitKind.MANUAL_TARGET && targetMediaId != null) return null

        val source = captureTopologySourceLocked()
        if (kind == TopologyCommitKind.QUEUE_CLEAR && familyOwnership !is FamilyOwnership.None && source == null) {
            return null
        }
        if (kind == TopologyCommitKind.QUEUE_CLEAR && source?.writeLease?.isDrained() == false) {
            return null
        }
        val reservedMutationId = when {
            kind == TopologyCommitKind.MANUAL_TARGET -> MutationId(++nextMutationId)
            kind == TopologyCommitKind.QUEUE_CLEAR && source != null -> MutationId(++nextMutationId)
            else -> null
        }
        val reservation = ProtocolTopologyReservation(
            reservationId = TopologyReservationId(++nextTopologyReservationId),
            stackId = stackId,
            seam = seam,
            kind = kind,
            targetMediaId = targetMediaId,
            reservedMutationId = reservedMutationId,
        )
        topologyTransaction = TopologyTransaction(
            reservation = reservation,
            plan = TopologyCommitPlan(kind, targetMediaId, reservedMutationId, source),
        )
        return reservation
    }

    /** Records that the canonical Media3 topology side effect returned successfully. */
    @Synchronized
    fun markTopologyDispatchSucceeded(reservation: ProtocolTopologyReservation): Boolean {
        val transaction = topologyTransaction ?: return false
        if (
            transaction.reservation != reservation ||
            transaction.phase != TopologyTransactionPhase.RESERVED_FENCED
        ) return false
        transaction.phase = TopologyTransactionPhase.DISPATCHED
        return true
    }

    /**
     * Ordinary abort is legal only while the transaction is still pre-dispatch and the caller can
     * prove no topology side effect occurred.
     */
    @Synchronized
    fun abortTopologyMutation(reservation: ProtocolTopologyReservation): Boolean {
        val transaction = topologyTransaction ?: return false
        if (
            transaction.reservation != reservation ||
            transaction.phase != TopologyTransactionPhase.RESERVED_FENCED
        ) return false
        finishTopologyTransactionLocked(transaction.retirementLatched)
        return true
    }

    /**
     * A thrown/unknown Media3 result after dispatch has started may not be rewritten as a clean
     * abort. Keep the fence installed, revoke current write authority, and wait for the existing
     * stack retirement/rebuild boundary to reconcile the unknown topology.
     */
    @Synchronized
    fun markTopologyDispatchUncertain(reservation: ProtocolTopologyReservation): Boolean {
        val transaction = topologyTransaction ?: return false
        if (
            transaction.reservation != reservation ||
            transaction.phase != TopologyTransactionPhase.RESERVED_FENCED
        ) return false
        transaction.phase = TopologyTransactionPhase.RECONCILIATION_REQUIRED
        familyOwnership.writeLeaseOrNull()?.revoke()
        startedAuthorities.clear()
        return true
    }

    /**
     * Monotonic post-dispatch commit. No live canBegin/begin predicate is evaluated here: the
     * commit consumes only the exact plan captured while acquiring the fence.
     */
    @Synchronized
    fun commitTopologyMutation(reservation: ProtocolTopologyReservation): Boolean {
        val transaction = topologyTransaction ?: return false
        if (
            transaction.reservation != reservation ||
            transaction.phase != TopologyTransactionPhase.DISPATCHED
        ) return false
        val plan = transaction.plan
        issuedRetirementReceiptsByMutation.clear()
        preparedPcmRetirement = null
        reclassifyUncommittedAuthorityLocked(retiring = false)
        startedAuthorities.clear()
        candidates.clear()

        mutation = when (plan.kind) {
            TopologyCommitKind.TOPOLOGY_ONLY -> null
            TopologyCommitKind.MANUAL_TARGET -> {
                val id = checkNotNull(plan.reservedMutationId)
                MutationEpoch(
                    mutationId = id,
                    kind = MutationKind.MANUAL,
                    requestAlias = null,
                    sourceOwnershipId = plan.capturedSource?.ownershipId,
                    sourceOccurrence = plan.capturedSource?.occurrence,
                    targetMediaId = checkNotNull(plan.targetMediaId),
                    targetOccurrence = null,
                    targetFamily = PlaybackFamily.PCM,
                    targetFacts = "",
                    destinationAdapterInstanceId = null,
                    destinationBound = false,
                )
            }
            TopologyCommitKind.QUEUE_CLEAR -> {
                applicationCurrent = ApplicationCurrent(null, null, null)
                val source = plan.capturedSource
                if (source == null) {
                    null
                } else {
                    source.writeLease.revoke()
                    MutationEpoch(
                        mutationId = checkNotNull(plan.reservedMutationId),
                        kind = MutationKind.MANUAL,
                        requestAlias = null,
                        sourceOwnershipId = source.ownershipId,
                        sourceOccurrence = source.occurrence,
                        targetMediaId = "__queue_clear__:${plan.reservedMutationId.value}",
                        targetOccurrence = source.occurrence,
                        targetFamily = source.family,
                        targetFacts = source.facts,
                        destinationAdapterInstanceId = source.adapterInstanceId,
                        destinationBound = true,
                    )
                }
            }
        }
        finishTopologyTransactionLocked(transaction.retirementLatched)
        return true
    }

    @Synchronized
    fun updateApplicationCurrent(mediaId: String?, periodUid: Any?, occurrence: PlaybackOccurrence?) {
        if (lifecycle !is ProtocolLifecycle.Active) return
        applicationCurrent = ApplicationCurrent(mediaId, periodUid, occurrence)
    }

    /**
     * Fences an explicit queue clear without leaving the committed source lease writable. The
     * source remains represented by a bound teardown mutation until its exact runtime release is
     * observed; no successor activation can be prepared because application currentness is empty.
     */
    @Synchronized
    fun canBeginQueueClear(): Boolean {
        if (
            lifecycle !is ProtocolLifecycle.Active ||
            topologyTransaction != null ||
            activations.isNotEmpty() ||
            cleanupRequirements.isNotEmpty()
        ) return false
        val source = familyOwnership
        val lease = source.writeLeaseOrNull()
        if (lease != null && !lease.isDrained()) return false
        if (source is FamilyOwnership.None) return true
        val adapter = source.adapterInstanceIdOrNull() ?: return false
        return source.familyOrNull() != null &&
            source.factsOrNull() != null &&
            source.occurrenceOrNull() != null &&
            adapter in adapters
    }

    @Synchronized
    fun beginQueueClear(): Boolean {
        if (!canBeginQueueClear()) return false
        val source = familyOwnership
        val lease = source.writeLeaseOrNull()
        if (source is FamilyOwnership.None) {
            mutation = null
            applicationCurrent = ApplicationCurrent(null, null, null)
            return true
        }
        val sourceFamily = source.familyOrNull() ?: return false
        val sourceFacts = source.factsOrNull() ?: return false
        val sourceOccurrence = source.occurrenceOrNull() ?: return false
        val clearMutation = beginMutationLocked(
            kind = MutationKind.MANUAL,
            targetMediaId = "__queue_clear__:${nextMutationId + 1}",
            targetFamily = sourceFamily,
            targetFacts = sourceFacts,
            targetOccurrence = sourceOccurrence,
            destinationAdapterInstanceId = source.adapterInstanceIdOrNull(),
        )
        lease?.revoke()
        applicationCurrent = ApplicationCurrent(null, null, null)
        return clearMutation != null
    }

    @Synchronized
    fun observeCandidate(candidate: CandidateOccurrence): Boolean {
        if (lifecycle !is ProtocolLifecycle.Active || candidate.adapterInstanceId !in adapters) return false
        candidates.removeAll { it.adapterInstanceId == candidate.adapterInstanceId && it.occurrence == candidate.occurrence }
        candidates += candidate
        return true
    }

    @Synchronized
    fun clearObservedCandidates(): Boolean {
        if (lifecycle !is ProtocolLifecycle.Active) return false
        candidates.clear()
        return true
    }

    @Synchronized
    fun beginMutation(
        kind: MutationKind,
        targetMediaId: String,
        targetFamily: PlaybackFamily,
        targetFacts: String,
        targetOccurrence: PlaybackOccurrence? = null,
        destinationAdapterInstanceId: AdapterInstanceId? = null,
        requestAlias: RequestAlias? = null,
        causalHandleFactory: ((MutationId) -> MutationCausalHandle)? = null,
    ): MutationEpoch? {
        if (lifecycle !is ProtocolLifecycle.Active || topologyTransaction != null) return null
        if (kind == MutationKind.AUTO_NEXT) return null
        return beginMutationLocked(
            kind = kind,
            targetMediaId = targetMediaId,
            targetFamily = targetFamily,
            targetFacts = targetFacts,
            targetOccurrence = targetOccurrence,
            destinationAdapterInstanceId = destinationAdapterInstanceId,
            requestAlias = requestAlias,
            causalHandleFactory = causalHandleFactory,
        )
    }

    @Synchronized
    fun adoptAutoCandidate(mediaId: String, occurrence: PlaybackOccurrence): MutationEpoch? {
        if (lifecycle !is ProtocolLifecycle.Active || topologyTransaction != null) return null
        if (applicationCurrent.mediaId != mediaId || applicationCurrent.occurrence != occurrence) return null
        val candidate = candidates.singleOrNull { it.mediaId == mediaId && it.occurrence == occurrence } ?: return null
        return beginMutationLocked(
            kind = MutationKind.AUTO_NEXT,
            targetMediaId = mediaId,
            targetFamily = candidate.family,
            targetFacts = candidate.facts,
            targetOccurrence = occurrence,
            destinationAdapterInstanceId = candidate.adapterInstanceId,
        )
    }

    /**
     * Raw manual-navigation staging seam. A mutation identity is minted before Exo dispatch while
     * renderer destination facts are still unknown. No activation authority exists until the same
     * mutation is later bound by an exact raw stream observation.
     */
    @Synchronized
    fun beginManualMutationUnbound(
        targetMediaId: String,
        requestAlias: RequestAlias? = null,
    ): MutationEpoch? {
        if (lifecycle !is ProtocolLifecycle.Active || topologyTransaction != null || targetMediaId.isBlank()) return null
        val source = familyOwnership
        val id = MutationId(nextMutationId + 1)
        issuedRetirementReceiptsByMutation.clear()
        preparedPcmRetirement = null
        nextMutationId = id.value
        reclassifyUncommittedAuthorityLocked(retiring = false)
        return MutationEpoch(
            mutationId = id,
            kind = MutationKind.MANUAL,
            requestAlias = requestAlias,
            sourceOwnershipId = source.ownershipIdOrNull(),
            sourceOccurrence = source.occurrenceOrNull(),
            targetMediaId = targetMediaId,
            targetOccurrence = null,
            targetFamily = PlaybackFamily.PCM,
            targetFacts = "",
            destinationBound = false,
        ).also { mutation = it }
    }

    @Synchronized
    fun bindManualDestination(
        mutationId: MutationId,
        destinationAdapterInstanceId: AdapterInstanceId,
        targetFamily: PlaybackFamily,
        targetFacts: String,
        occurrence: PlaybackOccurrence,
    ): Boolean {
        if (
            lifecycle !is ProtocolLifecycle.Active ||
            topologyTransaction != null ||
            targetFacts.isBlank() ||
            destinationAdapterInstanceId !in adapters
        ) return false
        val epoch = mutation ?: return false
        if (epoch.mutationId != mutationId || epoch.kind != MutationKind.MANUAL) return false
        if (epoch.destinationBound) {
            return epoch.destinationAdapterInstanceId == destinationAdapterInstanceId &&
                epoch.targetFamily == targetFamily &&
                epoch.targetFacts == targetFacts &&
                epoch.targetOccurrence == occurrence
        }
        mutation = epoch.copy(
            targetOccurrence = occurrence,
            targetFamily = targetFamily,
            targetFacts = targetFacts,
            destinationAdapterInstanceId = destinationAdapterInstanceId,
            destinationBound = true,
        )
        return true
    }

    private fun beginMutationLocked(
        kind: MutationKind,
        targetMediaId: String,
        targetFamily: PlaybackFamily,
        targetFacts: String,
        targetOccurrence: PlaybackOccurrence?,
        destinationAdapterInstanceId: AdapterInstanceId? = null,
        requestAlias: RequestAlias? = null,
        causalHandleFactory: ((MutationId) -> MutationCausalHandle)? = null,
    ): MutationEpoch? {
        val source = familyOwnership
        val id = MutationId(nextMutationId + 1)
        val resolvedDestinationAdapter = if (kind == MutationKind.SEEK) {
            source.adapterInstanceIdOrNull()
        } else {
            // Adapter registration proves only lifecycle membership. For every non-SEEK bound
            // destination, exact adapter authority must be supplied by renderer/candidate stream
            // provenance (or a separately modeled handoff); registry cardinality is never proof.
            destinationAdapterInstanceId
        }
        val causalHandle = if (kind == MutationKind.SEEK) {
            val ownedAdapter = source.adapterInstanceIdOrNull() ?: return null
            val ownedOccurrence = source.occurrenceOrNull() ?: return null
            val handle = causalHandleFactory?.invoke(id) ?: return null
            if (
                handle.stackId != stackId ||
                handle.mutationId != id ||
                handle.adapterInstanceId != ownedAdapter ||
                handle.sourceOccurrence != ownedOccurrence ||
                handle.targetSourcePositionUs < 0L ||
                targetOccurrence != ownedOccurrence
            ) return null
            if (destinationAdapterInstanceId != null && destinationAdapterInstanceId != ownedAdapter) return null
            handle
        } else {
            if (causalHandleFactory != null) return null
            if (targetOccurrence != null && resolvedDestinationAdapter == null) return null
            if (resolvedDestinationAdapter != null && resolvedDestinationAdapter !in adapters) return null
            null
        }
        issuedRetirementReceiptsByMutation.clear()
        preparedPcmRetirement = null
        nextMutationId = id.value
        reclassifyUncommittedAuthorityLocked(retiring = false)
        val epoch = MutationEpoch(
            mutationId = id,
            kind = kind,
            requestAlias = requestAlias,
            sourceOwnershipId = source.ownershipIdOrNull(),
            sourceOccurrence = source.occurrenceOrNull(),
            targetMediaId = targetMediaId,
            targetOccurrence = targetOccurrence,
            targetFamily = targetFamily,
            targetFacts = targetFacts,
            destinationAdapterInstanceId = causalHandle?.adapterInstanceId ?: resolvedDestinationAdapter,
            causalHandle = causalHandle,
        )
        mutation = epoch
        return epoch
    }

    @Synchronized
    fun bindTargetOccurrence(mutationId: MutationId, occurrence: PlaybackOccurrence): Boolean {
        val epoch = mutation ?: return false
        if (lifecycle !is ProtocolLifecycle.Active || topologyTransaction != null || epoch.mutationId != mutationId) return false
        if (applicationCurrent.mediaId != epoch.targetMediaId || applicationCurrent.occurrence != occurrence) return false
        mutation = epoch.copy(targetOccurrence = occurrence)
        return true
    }

    @Synchronized
    fun acceptSourceRetirement(receipt: SourceRetirementReceipt): Boolean {
        val epoch = mutation ?: return false
        if (!epoch.destinationBound) return false
        val owned = familyOwnership
        val issued = issuedRetirementReceiptsByMutation[epoch.mutationId] ?: return false
        if (
            lifecycle !is ProtocolLifecycle.Active ||
            issued != receipt ||
            epoch.sourceRetirement != null ||
            !retirementReceiptMatchesLocked(epoch, owned, receipt)
        ) return false
        val lease = owned.writeLeaseOrNull() ?: return false
        if (!lease.isRevoked() || !lease.isDrained()) return false
        issuedRetirementReceiptsByMutation.remove(epoch.mutationId)
        mutation = epoch.copy(sourceRetirement = receipt)
        if (owned is FamilyOwnership.PcmOwned) preparedPcmRetirement = null
        if (receipt.scope != RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED) {
            familyOwnership = FamilyOwnership.None
        }
        return true
    }

    @Synchronized
    fun mintRetirementReceipt(
        mutationId: MutationId,
        sourceAdapterInstanceId: AdapterInstanceId,
        scope: RetirementScope,
        familyProof: FamilyProof,
    ): SourceRetirementReceipt? {
        val epoch = mutation ?: return null
        if (!epoch.destinationBound) return null
        val owned = familyOwnership
        if (
            lifecycle !is ProtocolLifecycle.Active ||
            epoch.mutationId != mutationId ||
            epoch.sourceRetirement != null
        ) return null
        val ownershipId = owned.ownershipIdOrNull() ?: return null
        val occurrence = owned.occurrenceOrNull()
        val lease = owned.writeLeaseOrNull() ?: return null
        if (owned is FamilyOwnership.PcmOwned) {
            val prepared = preparedPcmRetirement ?: return null
            if (
                prepared.retiringMutationId != mutationId ||
                prepared.source != pcmSourceIdentityLocked(owned) ||
                prepared.source.adapterInstanceId != sourceAdapterInstanceId ||
                prepared.scope != scope ||
                !pcmProofMatchesPermitLocked(prepared, familyProof)
            ) return null
        }

        issuedRetirementReceiptsByMutation[mutationId]?.let { issued ->
            if (
                issued.sourceFamilyOwnershipId != ownershipId ||
                issued.sourceAdapterInstanceId != sourceAdapterInstanceId ||
                issued.scope != scope ||
                issued.familyProof != familyProof ||
                !retirementReceiptMatchesLocked(epoch, owned, issued)
            ) return null
            return issued
        }

        val preflight = SourceRetirementReceipt(
            receiptId = SideEffectReceiptId(nextReceiptId + 1),
            retiringMutationId = mutationId,
            sourceFamilyOwnershipId = ownershipId,
            sourceFamily = owned.familyOrNull() ?: return null,
            sourceOccurrence = occurrence,
            sourceAdapterInstanceId = sourceAdapterInstanceId,
            outputTarget = lease.identity.outputTarget,
            scope = scope,
            semanticPausedAtRetirement = false,
            familyProof = familyProof,
        )
        if (!retirementReceiptMatchesLocked(epoch, owned, preflight)) return null
        lease.revoke()
        if (!lease.isDrained()) return null
        val candidate = preflight.copy(
            semanticPausedAtRetirement = ledger.snapshot().desired == PlaybackIntent.PAUSE,
        )
        nextReceiptId += 1
        issuedRetirementReceiptsByMutation[mutationId] = candidate
        return candidate
    }

    /**
     * Mints the exact Direct-family release receipt after the stack has entered Retiring.
     * This is deliberately separate from the mutation-epoch receipt path: a committed source
     * may already have been followed by a destination-bound successor epoch.
     */
    @Synchronized
    fun mintRetiringDirectRuntimeReceipt(
        sourceAdapterInstanceId: AdapterInstanceId,
        sourceOccurrence: PlaybackOccurrence,
        runtimeIdentity: RuntimeIdentity,
        familyProof: FamilyProof.DirectFamilyReleased,
    ): SourceRetirementReceipt? {
        if (lifecycle !is ProtocolLifecycle.Retiring || familyProof.proof.isBlank()) return null
        val pending = retiringDirectRuntimeRelease ?: return null
        val owned = familyOwnership as? FamilyOwnership.DopOwned ?: return null
        val lease = owned.writeLease
        if (!retiringDirectRuntimeMatchesLocked(pending, owned, sourceAdapterInstanceId, sourceOccurrence, runtimeIdentity)) return null
        if (!lease.isRevoked()) return null

        // Release observation is allowed to arrive before the data-plane lease drains, but the
        // retirement receipt itself is not minted until the lease is closed. This keeps the
        // receipt provenance aligned with FROZEN_V1 while preserving the exact observation.
        if (!lease.isDrained()) {
            retiringDirectRuntimeRelease = pending.copy(observedFamilyProof = familyProof)
            return null
        }

        issuedRetiringDirectRuntimeReceipt?.let { issued ->
            return issued.takeIf {
                it.sourceFamilyOwnershipId == pending.sourceFamilyOwnershipId &&
                    it.sourceOccurrence == pending.sourceOccurrence &&
                    it.sourceAdapterInstanceId == pending.sourceAdapterInstanceId &&
                    it.outputTarget == pending.outputTarget &&
                    it.familyProof == familyProof
            }
        }

        val receipt = SourceRetirementReceipt(
            receiptId = SideEffectReceiptId(nextReceiptId + 1),
            retiringMutationId = pending.sourceMutationId,
            sourceFamilyOwnershipId = pending.sourceFamilyOwnershipId,
            sourceFamily = PlaybackFamily.DOP,
            sourceOccurrence = pending.sourceOccurrence,
            sourceAdapterInstanceId = pending.sourceAdapterInstanceId,
            outputTarget = pending.outputTarget,
            scope = RetirementScope.FAMILY_RUNTIME_RELEASED,
            semanticPausedAtRetirement = ledger.snapshot().desired == PlaybackIntent.PAUSE,
            familyProof = pending.observedFamilyProof ?: familyProof,
        )
        nextReceiptId += 1
        issuedRetiringDirectRuntimeReceipt = receipt
        return receipt
    }

    /**
     * Accepts only the receipt issued by the teardown-specific Direct release seam. It clears
     * the old family authority before reevaluating Retiring, so no successor can write through it.
     */
    @Synchronized
    fun acceptRetiringDirectRuntimeReceipt(receipt: SourceRetirementReceipt): Boolean {
        if (lifecycle !is ProtocolLifecycle.Retiring || issuedRetiringDirectRuntimeReceipt != receipt) return false
        val pending = retiringDirectRuntimeRelease ?: return false
        val owned = familyOwnership as? FamilyOwnership.DopOwned ?: return false
        val proof = receipt.familyProof as? FamilyProof.DirectFamilyReleased ?: return false
        if (
            receipt.scope != RetirementScope.FAMILY_RUNTIME_RELEASED ||
                receipt.sourceFamily != PlaybackFamily.DOP ||
                receipt.retiringMutationId != pending.sourceMutationId ||
                !retiringDirectRuntimeMatchesLocked(
                    pending,
                    owned,
                    receipt.sourceAdapterInstanceId,
                    receipt.sourceOccurrence ?: return false,
                    pending.runtimeIdentity,
                ) ||
                receipt.familyProof != proof ||
                !owned.writeLease.isRevoked() ||
                !owned.writeLease.isDrained()
        ) return false

        issuedRetiringDirectRuntimeReceipt = null
        retiringDirectRuntimeRelease = null
        familyOwnership = FamilyOwnership.None
        reevaluateRetiringLocked()
        return true
    }

    @Synchronized
    fun preparePcmConfigure(
        mutationId: MutationId,
        adapterInstanceId: AdapterInstanceId,
        occurrence: PlaybackOccurrence,
        facts: String,
    ): PcmConfigurePermit? {
        if (!canPrepareLocked(mutationId, adapterInstanceId, occurrence, PlaybackFamily.PCM, facts, requirePlay = true)) return null
        if (hasConflictingCleanupOrActivationLocked()) return null
        val activationId = ActivationId(++nextActivationId)
        val record = ActivationRecord(activationId, mutationId, adapterInstanceId, occurrence, PlaybackFamily.PCM, outputTarget, ActivationKind.PCM_CONFIGURE)
        activations[activationId] = record
        return PcmConfigurePermit(activationId, mutationId, adapterInstanceId, occurrence, facts, outputTarget, adoptedIntent.revision)
    }

    @Synchronized
    fun commitPcmConfigure(
        permit: PcmConfigurePermit,
        receipt: SideEffectReceipt,
        geometry: PcmAudioGeometry,
    ): CommitDisposition {
        return commitActivationLocked(permit.activationId, permit.mutationId, permit.adapterInstanceId, permit.occurrence, PlaybackFamily.PCM, receipt) {
            commitFamilyLocked(
                PlaybackFamily.PCM,
                permit.mutationId,
                permit.adapterInstanceId,
                permit.occurrence,
                RuntimeIdentity("pcm:${receipt.resourceIdentityOrNull()?.value ?: "configured"}"),
                permit.facts,
                permit.activationId,
                pcmGeometry = geometry,
            )
        }
    }

    /**
     * Closes the exact committed PCM source before a physical full-release or retained boundary.
     * The returned permit freezes the old ownership/runtime/output/geometry identity before the
     * delegate side effect or first successor write can occur.
     */
    @Synchronized
    fun preparePcmSourceRetirement(
        mutationId: MutationId,
        sourceAdapterInstanceId: AdapterInstanceId,
        targetOccurrence: PlaybackOccurrence,
        targetGeometry: PcmAudioGeometry,
        scope: RetirementScope,
    ): PcmRetirementPermit? {
        if (lifecycle !is ProtocolLifecycle.Active || topologyTransaction != null) return null
        if (scope == RetirementScope.STACK_TEARDOWN_RELEASED) return null
        val epoch = mutation ?: return null
        val owned = familyOwnership as? FamilyOwnership.PcmOwned ?: return null
        if (
            epoch.mutationId != mutationId ||
            !epoch.destinationBound ||
            epoch.sourceOwnershipId != owned.ownershipId ||
            epoch.sourceOccurrence != owned.occurrence ||
            epoch.targetOccurrence != targetOccurrence ||
            owned.adapterInstanceId != sourceAdapterInstanceId ||
            epoch.sourceRetirement != null
        ) return null
        if (
            scope == RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED &&
            (epoch.targetFamily != PlaybackFamily.PCM || targetGeometry != owned.geometry)
        ) return null

        val source = pcmSourceIdentityLocked(owned)
        val candidate = PcmRetirementPermit(
            retiringMutationId = mutationId,
            source = source,
            scope = scope,
            targetOccurrence = targetOccurrence,
            targetGeometry = targetGeometry,
        )
        preparedPcmRetirement?.let { return it.takeIf { prepared -> prepared == candidate } }
        owned.writeLease.revoke()
        if (!owned.writeLease.isDrained()) return null
        preparedPcmRetirement = candidate
        return candidate
    }

    /** Freezes the stack-teardown PCM source that beginRetiring() already revoked. */
    @Synchronized
    fun prepareRetiringPcmRuntimeRelease(sourceAdapterInstanceId: AdapterInstanceId): PcmRetirementPermit? {
        if (lifecycle !is ProtocolLifecycle.Retiring) return null
        val pending = retiringPcmRuntimeRelease ?: return null
        val owned = familyOwnership as? FamilyOwnership.PcmOwned ?: return null
        if (
            pending.source != pcmSourceIdentityLocked(owned) ||
            sourceAdapterInstanceId != pending.source.adapterInstanceId ||
            !owned.writeLease.isRevoked() ||
            !owned.writeLease.isDrained()
        ) return null
        return PcmRetirementPermit(
            retiringMutationId = pending.source.mutationId,
            source = pending.source,
            scope = RetirementScope.STACK_TEARDOWN_RELEASED,
            targetOccurrence = null,
            targetGeometry = null,
        )
    }

    /** Accepts only sink/runtime-issued typed PCM physical proof for the exact prepared source. */
    @Synchronized
    fun completePcmRetirement(
        permit: PcmRetirementPermit,
        proof: FamilyProof,
    ): Boolean {
        if (!pcmProofMatchesPermitLocked(permit, proof)) return false
        return when (permit.scope) {
            RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED,
            RetirementScope.FAMILY_RUNTIME_RELEASED,
            -> {
                if (lifecycle !is ProtocolLifecycle.Active || preparedPcmRetirement != permit) return false
                val receipt = mintRetirementReceipt(
                    permit.retiringMutationId,
                    permit.source.adapterInstanceId,
                    permit.scope,
                    proof,
                ) ?: return false
                val accepted = acceptSourceRetirement(receipt)
                if (accepted) preparedPcmRetirement = null
                accepted
            }
            RetirementScope.STACK_TEARDOWN_RELEASED -> {
                val canonical = canonicalStackTeardownPcmPermitLocked() ?: return false
                if (permit != canonical) return false
                val pcmProof = proof as? FamilyProof.PcmFamilyReleased ?: return false
                val receipt = mintRetiringPcmRuntimeReceiptLocked(pcmProof) ?: return false
                acceptRetiringPcmRuntimeReceiptLocked(receipt)
            }
        }
    }

    @Synchronized
    fun prepareRetainedPcmHandoff(
        mutationId: MutationId,
        adapterInstanceId: AdapterInstanceId,
        occurrence: PlaybackOccurrence,
        runtimeIdentity: RuntimeIdentity,
    ): RetainedPcmHandoffPermit? {
        val epoch = mutation ?: return null
        if (!canPrepareLocked(mutationId, adapterInstanceId, occurrence, PlaybackFamily.PCM, epoch.targetFacts, requirePlay = false)) return null
        val retirement = epoch.sourceRetirement ?: return null
        if (retirement.scope != RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED) return null
        val proof = retirement.familyProof as? FamilyProof.PcmRuntimeRetained ?: return null
        if (
            proof.runtimeIdentity != runtimeIdentity ||
            proof.targetGeometry != proof.sourceGeometry ||
            proof.tailOrdering.targetOccurrence != occurrence ||
            hasConflictingCleanupOrActivationLocked()
        ) return null
        val activationId = ActivationId(++nextActivationId)
        activations[activationId] = ActivationRecord(activationId, mutationId, adapterInstanceId, occurrence, PlaybackFamily.PCM, outputTarget, ActivationKind.RETAINED_PCM)
        return RetainedPcmHandoffPermit(activationId, mutationId, adapterInstanceId, occurrence, runtimeIdentity, outputTarget)
    }

    @Synchronized
    fun commitRetainedPcmHandoff(permit: RetainedPcmHandoffPermit): CommitDisposition {
        val record = activations[permit.activationId] ?: return CommitDisposition.StaleNoEffect
        if (!activationIsCurrentLocked(record, permit.mutationId, permit.adapterInstanceId, permit.occurrence)) {
            activations.remove(permit.activationId)
            updateRetiringBarrierLocked(permit.activationId)
            return CommitDisposition.StaleNoEffect
        }
        activations.remove(permit.activationId)
        val epoch = mutation ?: return CommitDisposition.StaleNoEffect
        val proof = epoch.sourceRetirement?.familyProof as? FamilyProof.PcmRuntimeRetained
            ?: return CommitDisposition.StaleNoEffect
        val result = commitFamilyLocked(
            PlaybackFamily.PCM,
            permit.mutationId,
            permit.adapterInstanceId,
            permit.occurrence,
            permit.runtimeIdentity,
            epoch.targetFacts,
            permit.activationId,
            pcmGeometry = proof.targetGeometry,
        )
        updateRetiringBarrierLocked(permit.activationId)
        return result
    }

    @Synchronized
    fun prepareRetainedDirectHandoff(
        mutationId: MutationId,
        adapterInstanceId: AdapterInstanceId,
        sourceOccurrence: PlaybackOccurrence,
        targetOccurrence: PlaybackOccurrence,
        runtimeIdentity: RuntimeIdentity,
    ): DirectRetainedHandoffPermit? {
        val epoch = mutation ?: return null
        val owned = familyOwnership as? FamilyOwnership.DopOwned ?: return null
        if (
            lifecycle !is ProtocolLifecycle.Active ||
            topologyTransaction != null ||
            adapterInstanceId !in adapters ||
            !epoch.destinationBound ||
            epoch.mutationId != mutationId ||
            epoch.targetFamily != PlaybackFamily.DOP ||
            epoch.targetOccurrence != targetOccurrence ||
            applicationCurrent.occurrence != targetOccurrence ||
            outputTarget is OutputTarget.Unavailable ||
            epoch.sourceOwnershipId != owned.ownershipId ||
            epoch.sourceRetirement != null ||
            owned.occurrence != sourceOccurrence ||
            owned.adapterInstanceId != adapterInstanceId ||
            owned.runtimeIdentity != runtimeIdentity ||
            sourceOccurrence == targetOccurrence
        ) return null
        adoptLatestIntent()
        if (hasConflictingCleanupOrActivationLocked()) return null
        owned.writeLease.revoke()
        if (!owned.writeLease.isDrained()) return null
        val activationId = ActivationId(++nextActivationId)
        activations[activationId] = ActivationRecord(
            activationId,
            mutationId,
            adapterInstanceId,
            targetOccurrence,
            PlaybackFamily.DOP,
            outputTarget,
            ActivationKind.RETAINED_DIRECT,
        )
        return DirectRetainedHandoffPermit(
            activationId,
            mutationId,
            adapterInstanceId,
            sourceOccurrence,
            targetOccurrence,
            runtimeIdentity,
            outputTarget,
            adoptedIntent.revision,
        )
    }

    @Synchronized
    fun commitRetainedDirectHandoff(
        permit: DirectRetainedHandoffPermit,
        receipt: SideEffectReceipt,
    ): CommitDisposition {
        val record = activations[permit.activationId] ?: return CommitDisposition.StaleNoEffect
        if (
            record.kind != ActivationKind.RETAINED_DIRECT ||
            receipt.activationId != permit.activationId ||
            record.mutationId != permit.mutationId ||
            record.adapterInstanceId != permit.adapterInstanceId ||
            record.occurrence != permit.targetOccurrence ||
            record.outputTarget != permit.outputTarget ||
            !receiptRuntimeMatchesRetainedDirectPermit(receipt, permit)
        ) return CommitDisposition.StaleNoEffect
        val epoch = mutation
        val owned = familyOwnership as? FamilyOwnership.DopOwned
        if (
            owned != null &&
                (owned.occurrence != permit.sourceOccurrence ||
                    owned.adapterInstanceId != permit.adapterInstanceId ||
                    owned.runtimeIdentity != permit.runtimeIdentity)
        ) return CommitDisposition.StaleNoEffect
        val current =
            epoch != null &&
                owned != null &&
                lifecycle is ProtocolLifecycle.Active &&
                epoch.destinationBound &&
                epoch.mutationId == permit.mutationId &&
                epoch.targetOccurrence == permit.targetOccurrence &&
                epoch.sourceRetirement == null &&
                epoch.sourceOwnershipId == owned.ownershipId &&
                applicationCurrent.occurrence == permit.targetOccurrence &&
                owned.occurrence == permit.sourceOccurrence &&
                owned.adapterInstanceId == permit.adapterInstanceId &&
                owned.runtimeIdentity == permit.runtimeIdentity &&
                outputTarget == permit.outputTarget &&
                owned.writeLease.isRevoked() &&
                owned.writeLease.isDrained()
        val retiring = lifecycle is ProtocolLifecycle.Retiring
        return when (receipt) {
            is SideEffectReceipt.NotStarted -> {
                activations.remove(permit.activationId)
                updateRetiringBarrierLocked(permit.activationId)
                if (current && !retiring) {
                    CommitDisposition.RetryPendingSameMutation
                } else {
                    CommitDisposition.StaleNoEffect
                }
            }
            is SideEffectReceipt.Completed -> if (retiring || !current) {
                requireCleanupLocked(
                    record = record,
                    resourceIdentity = receipt.resourceIdentity,
                    continuation = null,
                    retiring = retiring,
                    stale = !retiring,
                )
            } else {
                activations.remove(permit.activationId)
                val result = commitFamilyLocked(
                    PlaybackFamily.DOP,
                    permit.mutationId,
                    permit.adapterInstanceId,
                    permit.targetOccurrence,
                    permit.runtimeIdentity,
                    checkNotNull(epoch).targetFacts,
                    permit.activationId,
                )
                updateRetiringBarrierLocked(permit.activationId)
                result
            }
            is SideEffectReceipt.PartialNeedsCleanup -> requireCleanupLocked(
                record = record,
                resourceIdentity = receipt.resourceIdentity,
                continuation = CleanupContinuation.TERMINAL.takeIf { current && !retiring },
                retiring = retiring,
                stale = !current && !retiring,
            )
            is SideEffectReceipt.TerminalFailure -> {
                val resource = receipt.resourceIdentity
                if (resource == null) {
                    activations.remove(permit.activationId)
                    updateRetiringBarrierLocked(permit.activationId)
                    if (current && !retiring) CommitDisposition.TerminalFailure else CommitDisposition.StaleNoEffect
                } else {
                    requireCleanupLocked(
                        record = record,
                        resourceIdentity = resource,
                        continuation = CleanupContinuation.TERMINAL.takeIf { current && !retiring },
                        retiring = retiring,
                        stale = !current && !retiring,
                    )
                }
            }
        }
    }

    @Synchronized
    fun prepareDirectStage(
        mutationId: MutationId,
        adapterInstanceId: AdapterInstanceId,
        occurrence: PlaybackOccurrence,
        stage: DirectStage,
        runtimeIdentity: RuntimeIdentity,
        carrierBarrierSatisfied: Boolean = false,
    ): DirectStagePermit? {
        val epoch = mutation ?: return null
        if (!canPrepareLocked(mutationId, adapterInstanceId, occurrence, PlaybackFamily.DOP, epoch.targetFacts, requirePlay = true)) return null
        if (hasConflictingCleanupLocked()) return null
        val current = directActivation
        val activation = if (current == null) {
            if (stage != DirectStage.CREATE_RUNTIME || hasConflictingCleanupOrActivationLocked()) return null
            val id = ActivationId(++nextActivationId)
            val created = DirectActivationState(
                activationId = id,
                mutationId = mutationId,
                adapterInstanceId = adapterInstanceId,
                occurrence = occurrence,
                runtimeIdentity = runtimeIdentity,
                completedStageResources = emptyMap(),
                pendingStage = null,
                carrierBarrierSatisfied = carrierBarrierSatisfied,
            )
            directActivation = created
            activations[id] = ActivationRecord(id, mutationId, adapterInstanceId, occurrence, PlaybackFamily.DOP, outputTarget, ActivationKind.DIRECT)
            created
        } else {
            if (
                current.mutationId != mutationId ||
                current.adapterInstanceId != adapterInstanceId ||
                current.occurrence != occurrence ||
                current.runtimeIdentity != runtimeIdentity
            ) return null
            current
        }
        if (activation.pendingStage != null) return null
        val expectedPrevious = when (stage) {
            DirectStage.CREATE_RUNTIME -> emptySet()
            DirectStage.PREFILL -> setOf(DirectStage.CREATE_RUNTIME)
            DirectStage.ARM -> setOf(DirectStage.CREATE_RUNTIME, DirectStage.PREFILL)
            DirectStage.SOURCE_ACCEPT -> setOf(DirectStage.CREATE_RUNTIME, DirectStage.PREFILL, DirectStage.ARM)
        }
        if (activation.completedStageResources.keys != expectedPrevious) return null
        if (stage == DirectStage.ARM) {
            val started = startedAuthorities[adapterInstanceId] ?: return null
            if (
                started.mutationId != mutationId ||
                started.occurrence != occurrence ||
                started.activationId != activation.activationId
            ) return null
        }
        val effectiveCarrierBarrier = activation.carrierBarrierSatisfied || carrierBarrierSatisfied
        if (
            epoch.kind == MutationKind.SEEK &&
            stage == DirectStage.SOURCE_ACCEPT &&
            (epoch.causalHandle == null || !effectiveCarrierBarrier)
        ) return null
        directActivation = activation.copy(
            pendingStage = stage,
            carrierBarrierSatisfied = effectiveCarrierBarrier,
        )
        return DirectStagePermit(
            activation.activationId,
            mutationId,
            adapterInstanceId,
            occurrence,
            stage,
            runtimeIdentity,
            outputTarget,
            adoptedIntent.revision,
        )
    }

    @Synchronized
    fun commitDirectStage(permit: DirectStagePermit, receipt: SideEffectReceipt): CommitDisposition? {
        val record = activations[permit.activationId] ?: return CommitDisposition.StaleNoEffect
        if (record.kind != ActivationKind.DIRECT || receipt.activationId != permit.activationId) return CommitDisposition.StaleNoEffect
        val activation = directActivation
        if (
            activation == null ||
            activation.activationId != permit.activationId ||
            activation.pendingStage != permit.stage ||
            activation.runtimeIdentity != permit.runtimeIdentity ||
            activation.mutationId != permit.mutationId ||
            activation.adapterInstanceId != permit.adapterInstanceId ||
            activation.occurrence != permit.occurrence
        ) return CommitDisposition.StaleNoEffect
        if (!receiptRuntimeMatchesDirectPermit(receipt, permit)) return CommitDisposition.StaleNoEffect

        val current = activationIsCurrentLocked(record, permit.mutationId, permit.adapterInstanceId, permit.occurrence)
        val retiring = lifecycle is ProtocolLifecycle.Retiring
        return when (receipt) {
            is SideEffectReceipt.NotStarted -> {
                directActivation = activation.copy(pendingStage = null)
                if (current && !retiring) {
                    CommitDisposition.RetryPendingSameMutation
                } else if (activation.completedStageResources.isNotEmpty()) {
                    directActivation = null
                    requireDirectAbortCleanupLocked(
                        record = record,
                        activation = activation,
                        receiptResource = null,
                        continuation = null,
                        retiring = retiring,
                        stale = !retiring,
                    )
                } else {
                    removeDirectActivationLocked(permit.activationId)
                    CommitDisposition.StaleNoEffect
                }
            }
            is SideEffectReceipt.Completed -> when {
                retiring -> {
                    directActivation = null
                    requireDirectAbortCleanupLocked(
                        record = record,
                        activation = activation,
                        receiptResource = receipt.resourceIdentity,
                        continuation = null,
                        retiring = true,
                        stale = false,
                    )
                }
                !current -> {
                    directActivation = null
                    requireDirectAbortCleanupLocked(
                        record = record,
                        activation = activation,
                        receiptResource = receipt.resourceIdentity,
                        continuation = null,
                        retiring = false,
                        stale = true,
                    )
                }
                else -> {
                    val progressed = activation.copy(
                        completedStageResources = activation.completedStageResources + (permit.stage to receipt.resourceIdentity),
                        pendingStage = null,
                    )
                    directActivation = progressed
                    if (permit.stage != DirectStage.SOURCE_ACCEPT) {
                        null
                    } else {
                        val facts = mutation?.targetFacts ?: return CommitDisposition.StaleNoEffect
                        activations.remove(permit.activationId)
                        directActivation = null
                        startedAuthorities.remove(permit.adapterInstanceId)
                        val result = commitFamilyLocked(
                            PlaybackFamily.DOP,
                            permit.mutationId,
                            permit.adapterInstanceId,
                            permit.occurrence,
                            activation.runtimeIdentity,
                            facts,
                            permit.activationId,
                        )
                        updateRetiringBarrierLocked(permit.activationId)
                        result
                    }
                }
            }
            is SideEffectReceipt.PartialNeedsCleanup -> {
                directActivation = activation.copy(pendingStage = null)
                when {
                    retiring -> {
                        directActivation = null
                        requireDirectAbortCleanupLocked(
                            record = record,
                            activation = activation,
                            receiptResource = receipt.resourceIdentity,
                            continuation = null,
                            retiring = true,
                            stale = false,
                        )
                    }
                    current -> requireCleanupLocked(
                        record,
                        receipt.resourceIdentity,
                        CleanupContinuation.RETRY_SAME_MUTATION,
                        retiring = false,
                    )
                    else -> {
                        directActivation = null
                        requireDirectAbortCleanupLocked(
                            record = record,
                            activation = activation,
                            receiptResource = receipt.resourceIdentity,
                            continuation = null,
                            retiring = false,
                            stale = true,
                        )
                    }
                }
            }
            is SideEffectReceipt.TerminalFailure -> {
                directActivation = activation.copy(pendingStage = null)
                val resource = receipt.resourceIdentity
                when {
                    resource == null -> {
                        if (activation.completedStageResources.isEmpty()) {
                            removeDirectActivationLocked(permit.activationId)
                            if (current && !retiring) CommitDisposition.TerminalFailure else CommitDisposition.StaleNoEffect
                        } else {
                            directActivation = null
                            requireDirectAbortCleanupLocked(
                                record = record,
                                activation = activation,
                                receiptResource = null,
                                continuation = if (current && !retiring) CleanupContinuation.TERMINAL else null,
                                retiring = retiring,
                                stale = !current && !retiring,
                            )
                        }
                    }
                    retiring -> {
                        directActivation = null
                        requireDirectAbortCleanupLocked(
                            record = record,
                            activation = activation,
                            receiptResource = resource,
                            continuation = null,
                            retiring = true,
                            stale = false,
                        )
                    }
                    current -> {
                        directActivation = activation.copy(pendingStage = null)
                        requireDirectAbortCleanupLocked(
                            record = record,
                            activation = activation,
                            receiptResource = resource,
                            continuation = CleanupContinuation.TERMINAL,
                            retiring = false,
                            stale = false,
                        )
                    }
                    else -> {
                        directActivation = null
                        requireDirectAbortCleanupLocked(
                            record = record,
                            activation = activation,
                            receiptResource = resource,
                            continuation = null,
                            retiring = false,
                            stale = true,
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    fun completeCleanup(resourceIdentity: ResourceIdentity): CommitDisposition? {
        val requirement = cleanupRequirements.remove(resourceIdentity) ?: return null
        val record = activations[requirement.activationId]
        val stillCurrent = record != null && activationIsCurrentLocked(
            record,
            record.mutationId,
            record.adapterInstanceId,
            record.occurrence,
        )
        val siblings = cleanupRequirements.values.filter { it.activationId == requirement.activationId }
        val aggregateContinuation = requirement.continuation ?: siblings.firstNotNullOfOrNull { it.continuation }
        if (siblings.isNotEmpty()) {
            if (aggregateContinuation != null && siblings.none { it.continuation != null }) {
                val carrier = siblings.first()
                cleanupRequirements[carrier.resourceIdentity] = carrier.copy(continuation = aggregateContinuation)
            }
            updateRetiringBarrierLocked(requirement.activationId)
            return CommitDisposition.StaleNoEffect
        }
        val disposition = when {
            lifecycle !is ProtocolLifecycle.Active || !stillCurrent -> CommitDisposition.StaleNoEffect
            aggregateContinuation == CleanupContinuation.RETRY_SAME_MUTATION -> CommitDisposition.RetryPendingSameMutation
            aggregateContinuation == CleanupContinuation.TERMINAL -> CommitDisposition.TerminalFailure
            else -> CommitDisposition.StaleNoEffect
        }
        val preserveDirectRetry =
            disposition == CommitDisposition.RetryPendingSameMutation && record?.kind == ActivationKind.DIRECT
        if (!preserveDirectRetry) {
            activations.remove(requirement.activationId)
            if (directActivation?.activationId == requirement.activationId) directActivation = null
            startedAuthorities.entries.removeAll { it.value.activationId == requirement.activationId }
        }
        updateRetiringBarrierLocked(requirement.activationId)
        return disposition
    }

    /** Returns only cleanup requirements owned by the exact adapter activation. */
    @Synchronized
    fun cleanupRequirementsFor(
        adapterInstanceId: AdapterInstanceId,
        activationId: ActivationId,
    ): List<CleanupRequirement> {
        val record = activations[activationId] ?: return emptyList()
        if (record.adapterInstanceId != adapterInstanceId) return emptyList()
        return cleanupRequirements.values.filter { it.activationId == activationId }
    }

    /** Completes one exact owner-scoped cleanup requirement; forged/duplicate identities are no-op. */
    @Synchronized
    fun completeCleanup(
        adapterInstanceId: AdapterInstanceId,
        activationId: ActivationId,
        resourceIdentity: ResourceIdentity,
    ): CommitDisposition? {
        val requirement = cleanupRequirements[resourceIdentity] ?: return null
        val record = activations[requirement.activationId] ?: return null
        if (
            requirement.activationId != activationId ||
            record.adapterInstanceId != adapterInstanceId
        ) return null
        return completeCleanup(resourceIdentity)
    }

    @Synchronized
    fun updateOutputTarget(target: OutputTarget) {
        if (lifecycle !is ProtocolLifecycle.Active) return
        if (outputTarget != target) {
            issuedRetirementReceiptsByMutation.clear()
            preparedPcmRetirement = null
            familyOwnership.writeLeaseOrNull()?.revoke()
            reclassifyUncommittedAuthorityLocked(retiring = false)
            outputTarget = target
        }
    }

    @Synchronized
    fun isOutputBindingCurrent(target: OutputTarget): Boolean = lifecycle is ProtocolLifecycle.Active && outputTarget == target

    @Synchronized
    fun beginRetiring() {
        if (lifecycle !is ProtocolLifecycle.Active) return
        val transaction = topologyTransaction
        if (transaction != null) {
            if (transaction.phase == TopologyTransactionPhase.RECONCILIATION_REQUIRED) {
                topologyTransaction = null
                beginRetiringLocked()
            } else {
                transaction.retirementLatched = true
            }
            return
        }
        beginRetiringLocked()
    }

    private fun beginRetiringLocked() {
        if (lifecycle !is ProtocolLifecycle.Active) return
        issuedRetirementReceiptsByMutation.clear()
        preparedPcmRetirement = null
        issuedRetiringPcmRuntimeReceipt = null
        familyOwnership.writeLeaseOrNull()?.revoke()
        reclassifyUncommittedAuthorityLocked(retiring = true)
        lifecycle = ProtocolLifecycle.Retiring(activations.keys.toSet())
        retiringPcmRuntimeRelease = (familyOwnership as? FamilyOwnership.PcmOwned)?.let { owned ->
            RetiringPcmRuntimeRelease(pcmSourceIdentityLocked(owned))
        }
        retiringDirectRuntimeRelease = (familyOwnership as? FamilyOwnership.DopOwned)?.let { owned ->
            RetiringDirectRuntimeRelease(
                sourceFamilyOwnershipId = owned.ownershipId,
                sourceMutationId = owned.mutationId,
                sourceOccurrence = owned.occurrence,
                sourceAdapterInstanceId = owned.adapterInstanceId,
                runtimeIdentity = owned.runtimeIdentity,
                outputTarget = owned.writeLease.identity.outputTarget,
            )
        }
        reevaluateRetiringLocked()
    }

    @Synchronized
    fun snapshot(): UsbExclusiveProtocolSnapshot = UsbExclusiveProtocolSnapshot(
        lifecycle = lifecycle,
        stackId = stackId,
        adoptedIntent = adoptedIntent,
        outputTarget = outputTarget,
        applicationCurrent = applicationCurrent,
        mutation = mutation,
        familyOwnership = familyOwnership,
        candidates = candidates.toSet(),
        inFlightActivations = activations.keys.toSet(),
        cleanupRequirements = cleanupRequirements.keys.toSet(),
        topologyTransaction = topologyTransaction?.let {
            TopologyTransactionSnapshot(it.reservation, it.phase, it.retirementLatched)
        },
    )

    @Synchronized
    fun currentWriteLease(): ActiveWriteLease? = familyOwnership.writeLeaseOrNull()

    /**
     * Atomically gates a new data-plane admission with the protocol-owned topology fence. Existing
     * entered writers may still drain and exit, but no new write admission may start while a
     * topology transaction is outside the protocol lock.
     */
    @Synchronized
    fun tryEnterWrite(
        adapterInstanceId: AdapterInstanceId,
        occurrence: PlaybackOccurrence,
        writeKind: WriteKind,
    ): ActiveWriteLease? {
        if (lifecycle !is ProtocolLifecycle.Active || topologyTransaction != null) return null
        val lease = familyOwnership.writeLeaseOrNull() ?: return null
        if (lease.identity.adapterInstanceId != adapterInstanceId || lease.identity.occurrence != occurrence) return null
        return lease.takeIf {
            it.tryEnter(occurrence, lease.identity.mutationId, adapterInstanceId, writeKind)
        }
    }

    private fun captureTopologySourceLocked(): CapturedTopologySource? = when (val source = familyOwnership) {
        FamilyOwnership.None -> null
        is FamilyOwnership.PcmOwned -> CapturedTopologySource(
            source.ownershipId,
            source.mutationId,
            source.occurrence,
            source.adapterInstanceId,
            PlaybackFamily.PCM,
            source.facts,
            source.runtimeIdentity,
            source.writeLease,
        )
        is FamilyOwnership.DopOwned -> CapturedTopologySource(
            source.ownershipId,
            source.mutationId,
            source.occurrence,
            source.adapterInstanceId,
            PlaybackFamily.DOP,
            source.facts,
            source.runtimeIdentity,
            source.writeLease,
        )
    }

    private fun finishTopologyTransactionLocked(retirementLatched: Boolean) {
        topologyTransaction = null
        if (retirementLatched) beginRetiringLocked()
    }

    private fun canPrepareLocked(
        mutationId: MutationId,
        adapterInstanceId: AdapterInstanceId,
        occurrence: PlaybackOccurrence,
        family: PlaybackFamily,
        facts: String,
        requirePlay: Boolean,
    ): Boolean {
        if (lifecycle !is ProtocolLifecycle.Active || topologyTransaction != null || adapterInstanceId !in adapters) return false
        val epoch = mutation ?: return false
        if (!epoch.destinationBound) return false
        if (epoch.destinationAdapterInstanceId != adapterInstanceId) return false
        if (epoch.mutationId != mutationId || epoch.targetFamily != family || epoch.targetFacts != facts || epoch.targetOccurrence != occurrence) return false
        if (applicationCurrent.mediaId != epoch.targetMediaId || applicationCurrent.occurrence != occurrence) return false
        if (epoch.sourceOwnershipId != null) {
            val retirement = epoch.sourceRetirement ?: return false
            if (!retirementReceiptSufficientForTargetLocked(epoch, retirement)) return false
        }
        if (outputTarget is OutputTarget.Unavailable) return false
        adoptLatestIntent()
        if (requirePlay && adoptedIntent.desired != PlaybackIntent.PLAY) return false
        return true
    }

    private fun commitActivationLocked(
        activationId: ActivationId,
        mutationId: MutationId,
        adapterInstanceId: AdapterInstanceId,
        occurrence: PlaybackOccurrence,
        family: PlaybackFamily,
        receipt: SideEffectReceipt,
        onCompletedCurrent: () -> CommitDisposition,
    ): CommitDisposition {
        val record = activations[activationId] ?: return CommitDisposition.StaleNoEffect
        if (receipt.activationId != activationId || record.family != family) return CommitDisposition.StaleNoEffect
        val current = activationIsCurrentLocked(record, mutationId, adapterInstanceId, occurrence)
        val retiring = lifecycle is ProtocolLifecycle.Retiring
        val disposition = when (receipt) {
            is SideEffectReceipt.NotStarted -> if (current && !retiring) CommitDisposition.RetryPendingSameMutation else CommitDisposition.StaleNoEffect
            is SideEffectReceipt.Completed -> when {
                retiring -> requireCleanupLocked(record, receipt.resourceIdentity, null, retiring = true)
                current -> onCompletedCurrent()
                else -> requireCleanupLocked(record, receipt.resourceIdentity, null, retiring = false, stale = true)
            }
            is SideEffectReceipt.PartialNeedsCleanup -> when {
                retiring -> requireCleanupLocked(record, receipt.resourceIdentity, null, retiring = true)
                current -> requireCleanupLocked(record, receipt.resourceIdentity, CleanupContinuation.RETRY_SAME_MUTATION, retiring = false)
                else -> requireCleanupLocked(record, receipt.resourceIdentity, null, retiring = false, stale = true)
            }
            is SideEffectReceipt.TerminalFailure -> when {
                receipt.resourceIdentity == null -> if (current && !retiring) CommitDisposition.TerminalFailure else CommitDisposition.StaleNoEffect
                retiring -> requireCleanupLocked(record, receipt.resourceIdentity, null, retiring = true)
                current -> requireCleanupLocked(record, receipt.resourceIdentity, CleanupContinuation.TERMINAL, retiring = false)
                else -> requireCleanupLocked(record, receipt.resourceIdentity, null, retiring = false, stale = true)
            }
        }
        if (disposition !is CommitDisposition.CurrentCleanupRequired && disposition !is CommitDisposition.StaleCleanupRequired && disposition !is CommitDisposition.RetiringCleanupRequired) {
            activations.remove(activationId)
            updateRetiringBarrierLocked(activationId)
        }
        return disposition
    }

    private fun commitFamilyLocked(
        family: PlaybackFamily,
        mutationId: MutationId,
        adapterInstanceId: AdapterInstanceId,
        occurrence: PlaybackOccurrence,
        runtimeIdentity: RuntimeIdentity,
        facts: String,
        activationId: ActivationId,
        pcmGeometry: PcmAudioGeometry? = null,
    ): CommitDisposition {
        adoptLatestIntent()
        familyOwnership.writeLeaseOrNull()?.let { previous ->
            previous.revoke()
            if (!previous.isDrained()) return CommitDisposition.StaleNoEffect
        }
        val ownershipId = FamilyOwnershipId(++nextOwnershipId)
        val lease = ActiveWriteLease(WriteLeaseIdentity(stackId, outputTarget, mutationId, occurrence, adapterInstanceId, ownershipId, activationId, family))
        val paused = adoptedIntent.desired == PlaybackIntent.PAUSE
        lease.updateSemanticPaused(paused)
        lease.setOnDrained { onCommittedWriteLeaseDrained(ownershipId) }
        familyOwnership = when (family) {
            PlaybackFamily.PCM -> FamilyOwnership.PcmOwned(
                ownershipId,
                mutationId,
                occurrence,
                adapterInstanceId,
                paused,
                runtimeIdentity,
                facts,
                checkNotNull(pcmGeometry) { "PCM ownership requires exact audio geometry" },
                lease,
            )
            PlaybackFamily.DOP -> FamilyOwnership.DopOwned(ownershipId, mutationId, occurrence, adapterInstanceId, paused, runtimeIdentity, facts, lease)
        }
        mutation = null
        return if (paused) CommitDisposition.CurrentPaused(ownershipId, lease) else CommitDisposition.CurrentPlaying(ownershipId, lease)
    }

    private fun activationIsCurrentLocked(record: ActivationRecord, mutationId: MutationId, adapterInstanceId: AdapterInstanceId, occurrence: PlaybackOccurrence): Boolean {
        if (lifecycle !is ProtocolLifecycle.Active) return false
        val epoch = mutation ?: return false
        return record.mutationId == mutationId && record.adapterInstanceId == adapterInstanceId && record.occurrence == occurrence &&
            epoch.mutationId == mutationId && epoch.targetOccurrence == occurrence && outputTarget == record.outputTarget && applicationCurrent.occurrence == occurrence
    }

    private fun requireCleanupLocked(
        record: ActivationRecord,
        resourceIdentity: ResourceIdentity,
        continuation: CleanupContinuation?,
        retiring: Boolean,
        stale: Boolean = false,
    ): CommitDisposition {
        cleanupRequirements[resourceIdentity] = CleanupRequirement(record.activationId, resourceIdentity, continuation, retiring)
        return when {
            retiring -> CommitDisposition.RetiringCleanupRequired(resourceIdentity)
            stale -> CommitDisposition.StaleCleanupRequired(resourceIdentity)
            else -> CommitDisposition.CurrentCleanupRequired(resourceIdentity, checkNotNull(continuation))
        }
    }

    private fun requireDirectAbortCleanupLocked(
        record: ActivationRecord,
        activation: DirectActivationState,
        receiptResource: ResourceIdentity?,
        continuation: CleanupContinuation?,
        retiring: Boolean,
        stale: Boolean,
    ): CommitDisposition {
        val resources = linkedSetOf<ResourceIdentity>()
        resources += activation.completedStageResources.values
        receiptResource?.let { resources += it }
        check(resources.isNotEmpty()) { "Direct abort cleanup requires at least one live resource" }
        resources.forEach { resource ->
            cleanupRequirements[resource] = CleanupRequirement(
                activationId = record.activationId,
                resourceIdentity = resource,
                continuation = continuation,
                retiring = retiring,
            )
        }
        val primary = receiptResource ?: resources.first()
        return when {
            retiring -> CommitDisposition.RetiringCleanupRequired(primary)
            stale -> CommitDisposition.StaleCleanupRequired(primary)
            else -> CommitDisposition.CurrentCleanupRequired(primary, checkNotNull(continuation))
        }
    }

    private fun hasConflictingCleanupLocked(): Boolean = cleanupRequirements.isNotEmpty()

    private fun hasConflictingCleanupOrActivationLocked(): Boolean = cleanupRequirements.isNotEmpty() || activations.isNotEmpty()

    private fun reclassifyUncommittedAuthorityLocked(retiring: Boolean) {
        startedAuthorities.clear()
        val direct = directActivation ?: return
        if (direct.pendingStage != null) return
        val record = activations[direct.activationId] ?: run {
            directActivation = null
            return
        }
        val resources = direct.completedStageResources.values.toSet()
        if (resources.isEmpty()) {
            removeDirectActivationLocked(direct.activationId)
            return
        }
        resources.forEach { resource ->
            requireCleanupLocked(
                record = record,
                resourceIdentity = resource,
                continuation = null,
                retiring = retiring,
                stale = !retiring,
            )
        }
        directActivation = null
    }

    private fun updateRetiringBarrierLocked(@Suppress("UNUSED_PARAMETER") completedActivation: ActivationId) {
        reevaluateRetiringLocked()
    }

    private fun reevaluateRetiringLocked() {
        if (lifecycle !is ProtocolLifecycle.Retiring) return
        val committedLeaseDrained = familyOwnership.writeLeaseOrNull()?.isDrained() ?: true
        lifecycle = if (
            activations.isEmpty() &&
                cleanupRequirements.isEmpty() &&
                committedLeaseDrained &&
                retiringPcmRuntimeRelease == null &&
                retiringDirectRuntimeRelease == null
        ) {
            ProtocolLifecycle.Retired
        } else {
            ProtocolLifecycle.Retiring(activations.keys.toSet())
        }
    }

    /**
     * Stack-teardown completion may use only the exact permit that names the canonical
     * retiring PCM source. Matching runtime/geometry alone is not identity.
     */
    private fun canonicalStackTeardownPcmPermitLocked(): PcmRetirementPermit? {
        if (lifecycle !is ProtocolLifecycle.Retiring) return null
        val pending = retiringPcmRuntimeRelease ?: return null
        val owned = familyOwnership as? FamilyOwnership.PcmOwned ?: return null
        val source = pcmSourceIdentityLocked(owned)
        if (pending.source != source) return null
        return PcmRetirementPermit(
            retiringMutationId = source.mutationId,
            source = source,
            scope = RetirementScope.STACK_TEARDOWN_RELEASED,
            targetOccurrence = null,
            targetGeometry = null,
        )
    }

    private fun pcmSourceIdentityLocked(owned: FamilyOwnership.PcmOwned): PcmPhysicalSourceIdentity =
        PcmPhysicalSourceIdentity(
            familyOwnershipId = owned.ownershipId,
            mutationId = owned.mutationId,
            occurrence = owned.occurrence,
            adapterInstanceId = owned.adapterInstanceId,
            runtimeIdentity = owned.runtimeIdentity,
            outputTarget = owned.writeLease.identity.outputTarget,
            geometry = owned.geometry,
        )

    private fun pcmProofMatchesPermitLocked(permit: PcmRetirementPermit, proof: FamilyProof): Boolean =
        when (permit.scope) {
            RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED -> {
                val retained = proof as? FamilyProof.PcmRuntimeRetained ?: return false
                retained.runtimeIdentity == permit.source.runtimeIdentity &&
                    retained.sourceGeometry == permit.source.geometry &&
                    retained.targetGeometry == permit.targetGeometry &&
                    retained.sourceGeometry == retained.targetGeometry &&
                    retained.tailOrdering.sourceOccurrence == permit.source.occurrence &&
                    retained.tailOrdering.targetOccurrence == permit.targetOccurrence &&
                    retained.tailOrdering.sinkBoundarySequence > 0L
            }
            RetirementScope.FAMILY_RUNTIME_RELEASED,
            RetirementScope.STACK_TEARDOWN_RELEASED,
            -> {
                val released = proof as? FamilyProof.PcmFamilyReleased ?: return false
                released.runtimeIdentity == permit.source.runtimeIdentity &&
                    released.sourceGeometry == permit.source.geometry &&
                    released.sinkBoundarySequence > 0L
            }
        }

    private fun mintRetiringPcmRuntimeReceiptLocked(
        proof: FamilyProof.PcmFamilyReleased,
    ): SourceRetirementReceipt? {
        if (lifecycle !is ProtocolLifecycle.Retiring) return null
        val pending = retiringPcmRuntimeRelease ?: return null
        val owned = familyOwnership as? FamilyOwnership.PcmOwned ?: return null
        val lease = owned.writeLease
        if (
            pending.source != pcmSourceIdentityLocked(owned) ||
            proof.runtimeIdentity != pending.source.runtimeIdentity ||
            proof.sourceGeometry != pending.source.geometry ||
            proof.sinkBoundarySequence <= 0L ||
            !lease.isRevoked()
        ) return null
        if (!lease.isDrained()) {
            retiringPcmRuntimeRelease = pending.copy(observedFamilyProof = proof)
            return null
        }
        issuedRetiringPcmRuntimeReceipt?.let { issued ->
            return issued.takeIf {
                it.sourceFamilyOwnershipId == pending.source.familyOwnershipId &&
                    it.sourceOccurrence == pending.source.occurrence &&
                    it.sourceAdapterInstanceId == pending.source.adapterInstanceId &&
                    it.outputTarget == pending.source.outputTarget &&
                    it.familyProof == proof
            }
        }
        val receipt = SourceRetirementReceipt(
            receiptId = SideEffectReceiptId(nextReceiptId + 1),
            retiringMutationId = pending.source.mutationId,
            sourceFamilyOwnershipId = pending.source.familyOwnershipId,
            sourceFamily = PlaybackFamily.PCM,
            sourceOccurrence = pending.source.occurrence,
            sourceAdapterInstanceId = pending.source.adapterInstanceId,
            outputTarget = pending.source.outputTarget,
            scope = RetirementScope.STACK_TEARDOWN_RELEASED,
            semanticPausedAtRetirement = ledger.snapshot().desired == PlaybackIntent.PAUSE,
            familyProof = pending.observedFamilyProof ?: proof,
        )
        nextReceiptId += 1
        issuedRetiringPcmRuntimeReceipt = receipt
        return receipt
    }

    private fun acceptRetiringPcmRuntimeReceiptLocked(receipt: SourceRetirementReceipt): Boolean {
        if (lifecycle !is ProtocolLifecycle.Retiring || issuedRetiringPcmRuntimeReceipt != receipt) return false
        val pending = retiringPcmRuntimeRelease ?: return false
        val owned = familyOwnership as? FamilyOwnership.PcmOwned ?: return false
        val proof = receipt.familyProof as? FamilyProof.PcmFamilyReleased ?: return false
        if (
            pending.source != pcmSourceIdentityLocked(owned) ||
            receipt.scope != RetirementScope.STACK_TEARDOWN_RELEASED ||
            receipt.sourceFamily != PlaybackFamily.PCM ||
            receipt.retiringMutationId != pending.source.mutationId ||
            receipt.sourceFamilyOwnershipId != pending.source.familyOwnershipId ||
            receipt.sourceOccurrence != pending.source.occurrence ||
            receipt.sourceAdapterInstanceId != pending.source.adapterInstanceId ||
            receipt.outputTarget != pending.source.outputTarget ||
            proof.runtimeIdentity != pending.source.runtimeIdentity ||
            proof.sourceGeometry != pending.source.geometry ||
            proof.sinkBoundarySequence <= 0L ||
            !owned.writeLease.isRevoked() ||
            !owned.writeLease.isDrained()
        ) return false
        issuedRetiringPcmRuntimeReceipt = null
        retiringPcmRuntimeRelease = null
        familyOwnership = FamilyOwnership.None
        reevaluateRetiringLocked()
        return true
    }

    private fun retiringDirectRuntimeMatchesLocked(
        pending: RetiringDirectRuntimeRelease,
        owned: FamilyOwnership.DopOwned,
        sourceAdapterInstanceId: AdapterInstanceId,
        sourceOccurrence: PlaybackOccurrence,
        runtimeIdentity: RuntimeIdentity,
    ): Boolean =
        pending.sourceFamilyOwnershipId == owned.ownershipId &&
            pending.sourceMutationId == owned.mutationId &&
            pending.sourceOccurrence == owned.occurrence &&
            pending.sourceAdapterInstanceId == owned.adapterInstanceId &&
            pending.runtimeIdentity == owned.runtimeIdentity &&
            pending.outputTarget == owned.writeLease.identity.outputTarget &&
            sourceAdapterInstanceId == pending.sourceAdapterInstanceId &&
            sourceOccurrence == pending.sourceOccurrence &&
            runtimeIdentity == pending.runtimeIdentity

    private fun onCommittedWriteLeaseDrained(ownershipId: FamilyOwnershipId) {
        synchronized(this) {
            if (familyOwnership.ownershipIdOrNull() == ownershipId) {
                if (issuedRetiringPcmRuntimeReceipt == null && retiringPcmRuntimeRelease?.observedFamilyProof != null) {
                    mintRetiringPcmRuntimeReceiptLocked(retiringPcmRuntimeRelease!!.observedFamilyProof!!)
                }
                issuedRetiringPcmRuntimeReceipt?.let { receipt ->
                    acceptRetiringPcmRuntimeReceiptLocked(receipt)
                    return@synchronized
                }
                if (issuedRetiringDirectRuntimeReceipt == null && retiringDirectRuntimeRelease?.observedFamilyProof != null) {
                    mintRetiringDirectRuntimeReceipt(
                        sourceAdapterInstanceId = retiringDirectRuntimeRelease!!.sourceAdapterInstanceId,
                        sourceOccurrence = retiringDirectRuntimeRelease!!.sourceOccurrence,
                        runtimeIdentity = retiringDirectRuntimeRelease!!.runtimeIdentity,
                        familyProof = retiringDirectRuntimeRelease!!.observedFamilyProof!!,
                    )
                }
                issuedRetiringDirectRuntimeReceipt?.let { receipt ->
                    acceptRetiringDirectRuntimeReceipt(receipt)
                } ?: reevaluateRetiringLocked()
            }
        }
    }

    private fun removeDirectActivationLocked(activationId: ActivationId) {
        activations.remove(activationId)
        if (directActivation?.activationId == activationId) directActivation = null
        startedAuthorities.entries.removeAll { it.value.activationId == activationId }
        reevaluateRetiringLocked()
    }

    private fun receiptRuntimeMatchesDirectPermit(receipt: SideEffectReceipt, permit: DirectStagePermit): Boolean =
        when (receipt) {
            is SideEffectReceipt.NotStarted -> true
            is SideEffectReceipt.Completed -> receipt.runtimeIdentity == permit.runtimeIdentity
            is SideEffectReceipt.PartialNeedsCleanup -> receipt.runtimeIdentity == permit.runtimeIdentity
            is SideEffectReceipt.TerminalFailure ->
                receipt.resourceIdentity == null || receipt.runtimeIdentity == permit.runtimeIdentity
        }

    private fun receiptRuntimeMatchesRetainedDirectPermit(
        receipt: SideEffectReceipt,
        permit: DirectRetainedHandoffPermit,
    ): Boolean = when (receipt) {
        is SideEffectReceipt.NotStarted -> true
        is SideEffectReceipt.Completed -> receipt.runtimeIdentity == permit.runtimeIdentity
        is SideEffectReceipt.PartialNeedsCleanup -> receipt.runtimeIdentity == permit.runtimeIdentity
        is SideEffectReceipt.TerminalFailure ->
            receipt.resourceIdentity == null || receipt.runtimeIdentity == permit.runtimeIdentity
    }

    private fun retirementReceiptMatchesLocked(
        epoch: MutationEpoch,
        owned: FamilyOwnership,
        receipt: SourceRetirementReceipt,
    ): Boolean {
        val lease = owned.writeLeaseOrNull() ?: return false
        if (
            receipt.retiringMutationId != epoch.mutationId ||
            receipt.sourceFamilyOwnershipId != epoch.sourceOwnershipId ||
            receipt.sourceFamilyOwnershipId != owned.ownershipIdOrNull() ||
            receipt.sourceOccurrence != epoch.sourceOccurrence ||
            receipt.sourceOccurrence != owned.occurrenceOrNull() ||
            receipt.sourceAdapterInstanceId != owned.adapterInstanceIdOrNull() ||
            receipt.sourceFamily != owned.familyOrNull() ||
            receipt.outputTarget != lease.identity.outputTarget
        ) return false
        return retirementReceiptSufficientForOwnedTargetLocked(epoch, owned, receipt)
    }

    private fun retirementReceiptSufficientForOwnedTargetLocked(
        epoch: MutationEpoch,
        owned: FamilyOwnership,
        receipt: SourceRetirementReceipt,
    ): Boolean {
        if (!retirementReceiptSufficientForTargetLocked(epoch, receipt)) return false
        return when (owned) {
            FamilyOwnership.None -> false
            is FamilyOwnership.PcmOwned -> when (val proof = receipt.familyProof) {
                is FamilyProof.PcmRuntimeRetained ->
                    receipt.scope == RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED &&
                        epoch.targetFamily == PlaybackFamily.PCM &&
                        proof.runtimeIdentity == owned.runtimeIdentity &&
                        proof.sourceGeometry == owned.geometry &&
                        proof.targetGeometry == owned.geometry &&
                        proof.tailOrdering.sourceOccurrence == owned.occurrence &&
                        proof.tailOrdering.targetOccurrence == epoch.targetOccurrence &&
                        proof.tailOrdering.sinkBoundarySequence > 0L
                is FamilyProof.PcmFamilyReleased ->
                    receipt.scope == RetirementScope.FAMILY_RUNTIME_RELEASED &&
                        proof.runtimeIdentity == owned.runtimeIdentity &&
                        proof.sourceGeometry == owned.geometry &&
                        proof.sinkBoundarySequence > 0L
                else -> false
            }
            is FamilyOwnership.DopOwned -> when (val proof = receipt.familyProof) {
                is FamilyProof.DirectRuntimeRetained ->
                    receipt.scope == RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED &&
                        epoch.targetFamily == PlaybackFamily.DOP &&
                        epoch.targetFacts == owned.facts &&
                        proof.runtimeIdentity == owned.runtimeIdentity &&
                        proof.proof.isNotBlank()
                is FamilyProof.DirectFamilyReleased ->
                    receipt.scope == RetirementScope.FAMILY_RUNTIME_RELEASED && proof.proof.isNotBlank()
                is FamilyProof.StackReleased ->
                    receipt.scope != RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED && proof.proof.isNotBlank()
                else -> false
            }
        }
    }

    private fun retirementReceiptSufficientForTargetLocked(
        epoch: MutationEpoch,
        receipt: SourceRetirementReceipt,
    ): Boolean = when (receipt.sourceFamily) {
        PlaybackFamily.PCM -> when (receipt.scope) {
            RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED ->
                epoch.targetFamily == PlaybackFamily.PCM && receipt.familyProof is FamilyProof.PcmRuntimeRetained
            RetirementScope.FAMILY_RUNTIME_RELEASED -> receipt.familyProof is FamilyProof.PcmFamilyReleased
            RetirementScope.STACK_TEARDOWN_RELEASED -> receipt.familyProof is FamilyProof.PcmFamilyReleased
        }
        PlaybackFamily.DOP -> when (receipt.scope) {
            RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED ->
                epoch.targetFamily == PlaybackFamily.DOP && receipt.familyProof is FamilyProof.DirectRuntimeRetained
            RetirementScope.FAMILY_RUNTIME_RELEASED ->
                receipt.familyProof is FamilyProof.DirectFamilyReleased || receipt.familyProof is FamilyProof.StackReleased
            RetirementScope.STACK_TEARDOWN_RELEASED -> receipt.familyProof is FamilyProof.StackReleased
        }
    }

    private fun FamilyOwnership.ownershipIdOrNull(): FamilyOwnershipId? = when (this) {
        FamilyOwnership.None -> null
        is FamilyOwnership.PcmOwned -> ownershipId
        is FamilyOwnership.DopOwned -> ownershipId
    }

    private fun FamilyOwnership.occurrenceOrNull(): PlaybackOccurrence? = when (this) {
        FamilyOwnership.None -> null
        is FamilyOwnership.PcmOwned -> occurrence
        is FamilyOwnership.DopOwned -> occurrence
    }

    private fun FamilyOwnership.adapterInstanceIdOrNull(): AdapterInstanceId? = when (this) {
        FamilyOwnership.None -> null
        is FamilyOwnership.PcmOwned -> adapterInstanceId
        is FamilyOwnership.DopOwned -> adapterInstanceId
    }

    private fun FamilyOwnership.writeLeaseOrNull(): ActiveWriteLease? = when (this) {
        FamilyOwnership.None -> null
        is FamilyOwnership.PcmOwned -> writeLease
        is FamilyOwnership.DopOwned -> writeLease
    }

    private fun FamilyOwnership.familyOrNull(): PlaybackFamily? = when (this) {
        FamilyOwnership.None -> null
        is FamilyOwnership.PcmOwned -> PlaybackFamily.PCM
        is FamilyOwnership.DopOwned -> PlaybackFamily.DOP
    }

    private fun FamilyOwnership.factsOrNull(): String? = when (this) {
        FamilyOwnership.None -> null
        is FamilyOwnership.PcmOwned -> facts
        is FamilyOwnership.DopOwned -> facts
    }

    private fun SideEffectReceipt.resourceIdentityOrNull(): ResourceIdentity? = when (this) {
        is SideEffectReceipt.NotStarted -> null
        is SideEffectReceipt.Completed -> resourceIdentity
        is SideEffectReceipt.PartialNeedsCleanup -> resourceIdentity
        is SideEffectReceipt.TerminalFailure -> resourceIdentity
    }
}
