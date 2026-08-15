package com.mica.music.media.usb.protocol

import java.util.concurrent.atomic.AtomicInteger

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
    val causalHandle: MutationCausalHandle? = null,
    val sourceRetirement: SourceRetirementReceipt? = null,
)

sealed interface FamilyProof {
    data class PcmRuntimeRetained(
        val runtimeIdentity: RuntimeIdentity,
        val compatibilityFacts: String,
        val tailOrderingProof: String,
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
    private val entered = AtomicInteger(0)

    @Synchronized
    fun updateSemanticPaused(paused: Boolean) {
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
            PlaybackFamily.DOP -> if (semanticPaused) writeKind == WriteKind.DOP_GAP else writeKind == WriteKind.DOP_CONTENT
        }
        if (!allowed) return false
        entered.incrementAndGet()
        return true
    }

    fun exit() {
        check(entered.decrementAndGet() >= 0) { "write lease exit without enter" }
    }

    @Synchronized
    fun revoke() {
        revoked = true
    }

    fun isDrained(): Boolean = entered.get() == 0

    @Synchronized
    fun isRevoked(): Boolean = revoked
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
        val writeLease: ActiveWriteLease,
    ) : FamilyOwnership

    data class DopOwned(
        val ownershipId: FamilyOwnershipId,
        val mutationId: MutationId,
        val occurrence: PlaybackOccurrence,
        val adapterInstanceId: AdapterInstanceId,
        val semanticPaused: Boolean,
        val runtimeIdentity: RuntimeIdentity,
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
    ) : SideEffectReceipt

    data class PartialNeedsCleanup(
        override val activationId: ActivationId,
        val resourceIdentity: ResourceIdentity,
        val facts: String,
    ) : SideEffectReceipt

    data class TerminalFailure(
        override val activationId: ActivationId,
        val resourceIdentity: ResourceIdentity?,
        val failure: String,
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
    val completedStages: Set<DirectStage>,
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
    private val startedAdapters = linkedSetOf<AdapterInstanceId>()
    private val candidates = linkedSetOf<CandidateOccurrence>()
    private var nextMutationId = 0L
    private var nextActivationId = 0L
    private var nextOwnershipId = 0L
    private var nextReceiptId = 0L
    private var mutation: MutationEpoch? = null
    private var familyOwnership: FamilyOwnership = FamilyOwnership.None
    private val activations = linkedMapOf<ActivationId, ActivationRecord>()
    private val cleanupRequirements = linkedMapOf<ResourceIdentity, CleanupRequirement>()
    private var directActivation: DirectActivationState? = null

    private data class ActivationRecord(
        val activationId: ActivationId,
        val mutationId: MutationId,
        val adapterInstanceId: AdapterInstanceId,
        val occurrence: PlaybackOccurrence,
        val family: PlaybackFamily,
        val outputTarget: OutputTarget,
        val kind: ActivationKind,
    )

    private enum class ActivationKind { PCM_CONFIGURE, RETAINED_PCM, DIRECT }

    @Synchronized
    fun registerAdapter(adapterInstanceId: AdapterInstanceId): Boolean {
        if (lifecycle is ProtocolLifecycle.Retired) return false
        adapters += adapterInstanceId
        return true
    }

    @Synchronized
    fun observeAdapterStarted(adapterInstanceId: AdapterInstanceId, occurrence: PlaybackOccurrence): Boolean {
        if (lifecycle !is ProtocolLifecycle.Active || adapterInstanceId !in adapters) return false
        val currentMutation = mutation ?: return false
        if (currentMutation.targetOccurrence != occurrence) return false
        startedAdapters += adapterInstanceId
        return true
    }

    @Synchronized
    fun adoptLatestIntent(): IntentSnapshot {
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
    fun restoreAfterTechnicalQuiesce(@Suppress("UNUSED_PARAMETER") captured: IntentRevision): IntentSnapshot = adoptLatestIntent()

    @Synchronized
    fun updateApplicationCurrent(mediaId: String?, periodUid: Any?, occurrence: PlaybackOccurrence?) {
        if (lifecycle is ProtocolLifecycle.Retired) return
        applicationCurrent = ApplicationCurrent(mediaId, periodUid, occurrence)
    }

    @Synchronized
    fun observeCandidate(candidate: CandidateOccurrence): Boolean {
        if (lifecycle !is ProtocolLifecycle.Active || candidate.adapterInstanceId !in adapters) return false
        candidates.removeAll { it.adapterInstanceId == candidate.adapterInstanceId && it.occurrence == candidate.occurrence }
        candidates += candidate
        return true
    }

    @Synchronized
    fun beginMutation(
        kind: MutationKind,
        targetMediaId: String,
        targetFamily: PlaybackFamily,
        targetFacts: String,
        targetOccurrence: PlaybackOccurrence? = null,
        requestAlias: RequestAlias? = null,
        causalHandleFactory: ((MutationId) -> MutationCausalHandle)? = null,
    ): MutationEpoch? {
        if (lifecycle !is ProtocolLifecycle.Active) return null
        invalidateUncommittedAuthorityLocked()
        val source = familyOwnership
        val id = MutationId(++nextMutationId)
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
            causalHandle = causalHandleFactory?.invoke(id),
        )
        mutation = epoch
        return epoch
    }

    @Synchronized
    fun adoptAutoCandidate(mediaId: String, occurrence: PlaybackOccurrence): MutationEpoch? {
        if (lifecycle !is ProtocolLifecycle.Active) return null
        if (applicationCurrent.mediaId != mediaId || applicationCurrent.occurrence != occurrence) return null
        val candidate = candidates.singleOrNull { it.mediaId == mediaId && it.occurrence == occurrence } ?: return null
        return beginMutation(
            kind = MutationKind.AUTO_NEXT,
            targetMediaId = mediaId,
            targetFamily = candidate.family,
            targetFacts = candidate.facts,
            targetOccurrence = occurrence,
        )
    }

    @Synchronized
    fun bindTargetOccurrence(mutationId: MutationId, occurrence: PlaybackOccurrence): Boolean {
        val epoch = mutation ?: return false
        if (lifecycle !is ProtocolLifecycle.Active || epoch.mutationId != mutationId) return false
        if (applicationCurrent.mediaId != epoch.targetMediaId || applicationCurrent.occurrence != occurrence) return false
        mutation = epoch.copy(targetOccurrence = occurrence)
        return true
    }

    @Synchronized
    fun acceptSourceRetirement(receipt: SourceRetirementReceipt): Boolean {
        val epoch = mutation ?: return false
        if (lifecycle !is ProtocolLifecycle.Active || receipt.retiringMutationId != epoch.mutationId) return false
        if (receipt.sourceFamilyOwnershipId != epoch.sourceOwnershipId || receipt.sourceOccurrence != epoch.sourceOccurrence) return false
        val owned = familyOwnership
        if (owned.ownershipIdOrNull() != receipt.sourceFamilyOwnershipId) return false
        val lease = owned.writeLeaseOrNull() ?: return false
        lease.revoke()
        if (!lease.isDrained()) return false
        if (receipt.semanticPausedAtRetirement != (ledger.snapshot().desired == PlaybackIntent.PAUSE)) return false
        mutation = epoch.copy(sourceRetirement = receipt)
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
        val owned = familyOwnership
        if (lifecycle !is ProtocolLifecycle.Active || epoch.mutationId != mutationId) return null
        val ownershipId = owned.ownershipIdOrNull() ?: return null
        val occurrence = owned.occurrenceOrNull()
        val lease = owned.writeLeaseOrNull() ?: return null
        lease.revoke()
        if (!lease.isDrained()) return null
        return SourceRetirementReceipt(
            receiptId = SideEffectReceiptId(++nextReceiptId),
            retiringMutationId = mutationId,
            sourceFamilyOwnershipId = ownershipId,
            sourceFamily = owned.familyOrNull()!!,
            sourceOccurrence = occurrence,
            sourceAdapterInstanceId = sourceAdapterInstanceId,
            outputTarget = outputTarget,
            scope = scope,
            semanticPausedAtRetirement = ledger.snapshot().desired == PlaybackIntent.PAUSE,
            familyProof = familyProof,
        )
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
    fun commitPcmConfigure(permit: PcmConfigurePermit, receipt: SideEffectReceipt): CommitDisposition {
        return commitActivationLocked(permit.activationId, permit.mutationId, permit.adapterInstanceId, permit.occurrence, PlaybackFamily.PCM, receipt) {
            commitFamilyLocked(PlaybackFamily.PCM, permit.mutationId, permit.adapterInstanceId, permit.occurrence, RuntimeIdentity("pcm:${receipt.resourceIdentityOrNull()?.value ?: "configured"}"), permit.activationId)
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
        if (proof.runtimeIdentity != runtimeIdentity || hasConflictingCleanupOrActivationLocked()) return null
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
        val result = commitFamilyLocked(PlaybackFamily.PCM, permit.mutationId, permit.adapterInstanceId, permit.occurrence, permit.runtimeIdentity, permit.activationId)
        updateRetiringBarrierLocked(permit.activationId)
        return result
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
            val created = DirectActivationState(id, mutationId, adapterInstanceId, occurrence, runtimeIdentity, emptySet(), carrierBarrierSatisfied)
            directActivation = created
            activations[id] = ActivationRecord(id, mutationId, adapterInstanceId, occurrence, PlaybackFamily.DOP, outputTarget, ActivationKind.DIRECT)
            created
        } else {
            if (current.mutationId != mutationId || current.adapterInstanceId != adapterInstanceId || current.occurrence != occurrence) return null
            current
        }
        val expectedPrevious = when (stage) {
            DirectStage.CREATE_RUNTIME -> emptySet()
            DirectStage.PREFILL -> setOf(DirectStage.CREATE_RUNTIME)
            DirectStage.ARM -> setOf(DirectStage.CREATE_RUNTIME, DirectStage.PREFILL)
            DirectStage.SOURCE_ACCEPT -> setOf(DirectStage.CREATE_RUNTIME, DirectStage.PREFILL, DirectStage.ARM)
        }
        if (activation.completedStages != expectedPrevious) return null
        if (stage == DirectStage.ARM && adapterInstanceId !in startedAdapters) return null
        if (epoch.kind == MutationKind.SEEK && stage == DirectStage.SOURCE_ACCEPT && !activation.carrierBarrierSatisfied) return null
        return DirectStagePermit(activation.activationId, mutationId, adapterInstanceId, occurrence, stage, outputTarget, adoptedIntent.revision)
    }

    @Synchronized
    fun commitDirectStage(permit: DirectStagePermit): CommitDisposition? {
        val activation = directActivation ?: return CommitDisposition.StaleNoEffect
        if (activation.activationId != permit.activationId || !activationIsCurrentLocked(activations[permit.activationId] ?: return CommitDisposition.StaleNoEffect, permit.mutationId, permit.adapterInstanceId, permit.occurrence)) {
            return CommitDisposition.StaleNoEffect
        }
        adoptLatestIntent()
        if (
            adoptedIntent.desired != PlaybackIntent.PLAY ||
            adoptedIntent.revision != permit.adoptedIntentRevision
        ) return null
        val completed = activation.completedStages + permit.stage
        directActivation = activation.copy(completedStages = completed)
        if (permit.stage != DirectStage.SOURCE_ACCEPT) return null
        activations.remove(permit.activationId)
        val result = commitFamilyLocked(PlaybackFamily.DOP, permit.mutationId, permit.adapterInstanceId, permit.occurrence, activation.runtimeIdentity, permit.activationId)
        directActivation = null
        updateRetiringBarrierLocked(permit.activationId)
        return result
    }

    @Synchronized
    fun commitSideEffect(receipt: SideEffectReceipt): CommitDisposition {
        val record = activations[receipt.activationId]
        if (record == null) return CommitDisposition.StaleNoEffect
        val current = activationIsCurrentLocked(record, record.mutationId, record.adapterInstanceId, record.occurrence)
        val retiring = lifecycle is ProtocolLifecycle.Retiring
        val disposition = when (receipt) {
            is SideEffectReceipt.NotStarted -> if (current && !retiring) CommitDisposition.RetryPendingSameMutation else CommitDisposition.StaleNoEffect
            is SideEffectReceipt.Completed -> when {
                retiring -> requireCleanupLocked(record, receipt.resourceIdentity, null, retiring = true)
                current -> CommitDisposition.RetryPendingSameMutation
                else -> requireCleanupLocked(record, receipt.resourceIdentity, null, retiring = false, stale = true)
            }
            is SideEffectReceipt.PartialNeedsCleanup -> when {
                retiring -> requireCleanupLocked(record, receipt.resourceIdentity, null, retiring = true)
                current -> requireCleanupLocked(record, receipt.resourceIdentity, CleanupContinuation.RETRY_SAME_MUTATION, retiring = false)
                else -> requireCleanupLocked(record, receipt.resourceIdentity, null, retiring = false, stale = true)
            }
            is SideEffectReceipt.TerminalFailure -> {
                val resource = receipt.resourceIdentity
                when {
                    resource == null -> if (current && !retiring) CommitDisposition.TerminalFailure else CommitDisposition.StaleNoEffect
                    retiring -> requireCleanupLocked(record, resource, null, retiring = true)
                    current -> requireCleanupLocked(record, resource, CleanupContinuation.TERMINAL, retiring = false)
                    else -> requireCleanupLocked(record, resource, null, retiring = false, stale = true)
                }
            }
        }
        if (disposition !is CommitDisposition.CurrentCleanupRequired && disposition !is CommitDisposition.StaleCleanupRequired && disposition !is CommitDisposition.RetiringCleanupRequired) {
            activations.remove(receipt.activationId)
            updateRetiringBarrierLocked(receipt.activationId)
        }
        return disposition
    }

    @Synchronized
    fun completeCleanup(resourceIdentity: ResourceIdentity): CommitDisposition? {
        val requirement = cleanupRequirements.remove(resourceIdentity) ?: return null
        activations.remove(requirement.activationId)
        val disposition = when (requirement.continuation) {
            CleanupContinuation.RETRY_SAME_MUTATION -> CommitDisposition.RetryPendingSameMutation
            CleanupContinuation.TERMINAL -> CommitDisposition.TerminalFailure
            null -> CommitDisposition.StaleNoEffect
        }
        updateRetiringBarrierLocked(requirement.activationId)
        return disposition
    }

    @Synchronized
    fun updateOutputTarget(target: OutputTarget) {
        if (lifecycle is ProtocolLifecycle.Retired) return
        if (outputTarget != target) {
            outputTarget = target
            directActivation = null
        }
    }

    @Synchronized
    fun isOutputBindingCurrent(target: OutputTarget): Boolean = lifecycle !is ProtocolLifecycle.Retired && outputTarget == target

    @Synchronized
    fun beginRetiring() {
        if (lifecycle !is ProtocolLifecycle.Active) return
        familyOwnership.writeLeaseOrNull()?.revoke()
        val inFlight = activations.keys.toSet()
        lifecycle = if (inFlight.isEmpty() && cleanupRequirements.isEmpty()) ProtocolLifecycle.Retired else ProtocolLifecycle.Retiring(inFlight)
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
    )

    @Synchronized
    fun currentWriteLease(): ActiveWriteLease? = familyOwnership.writeLeaseOrNull()

    @Synchronized
    fun installOwnedFamilyForModel(
        family: PlaybackFamily,
        mutationId: MutationId,
        adapterInstanceId: AdapterInstanceId,
        occurrence: PlaybackOccurrence,
        runtimeIdentity: RuntimeIdentity,
    ): CommitDisposition {
        check(lifecycle is ProtocolLifecycle.Active)
        mutation = MutationEpoch(mutationId, MutationKind.MANUAL, null, null, null, "model", occurrence, family, "model")
        if (mutationId.value > nextMutationId) nextMutationId = mutationId.value
        return commitFamilyLocked(family, mutationId, adapterInstanceId, occurrence, runtimeIdentity, ActivationId(++nextActivationId))
    }

    private fun canPrepareLocked(
        mutationId: MutationId,
        adapterInstanceId: AdapterInstanceId,
        occurrence: PlaybackOccurrence,
        family: PlaybackFamily,
        facts: String,
        requirePlay: Boolean,
    ): Boolean {
        if (lifecycle !is ProtocolLifecycle.Active || adapterInstanceId !in adapters) return false
        val epoch = mutation ?: return false
        if (epoch.mutationId != mutationId || epoch.targetFamily != family || epoch.targetFacts != facts || epoch.targetOccurrence != occurrence) return false
        if (applicationCurrent.mediaId != epoch.targetMediaId || applicationCurrent.occurrence != occurrence) return false
        if (epoch.sourceOwnershipId != null && epoch.sourceRetirement == null) return false
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
        require(receipt.activationId == activationId)
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
        activationId: ActivationId,
    ): CommitDisposition {
        adoptLatestIntent()
        familyOwnership.writeLeaseOrNull()?.revoke()
        val ownershipId = FamilyOwnershipId(++nextOwnershipId)
        val lease = ActiveWriteLease(WriteLeaseIdentity(stackId, outputTarget, mutationId, occurrence, adapterInstanceId, ownershipId, activationId, family))
        val paused = adoptedIntent.desired == PlaybackIntent.PAUSE
        lease.updateSemanticPaused(paused)
        familyOwnership = when (family) {
            PlaybackFamily.PCM -> FamilyOwnership.PcmOwned(ownershipId, mutationId, occurrence, adapterInstanceId, paused, runtimeIdentity, lease)
            PlaybackFamily.DOP -> FamilyOwnership.DopOwned(ownershipId, mutationId, occurrence, adapterInstanceId, paused, runtimeIdentity, lease)
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

    private fun hasConflictingCleanupLocked(): Boolean = cleanupRequirements.isNotEmpty()

    private fun hasConflictingCleanupOrActivationLocked(): Boolean = cleanupRequirements.isNotEmpty() || activations.isNotEmpty()

    private fun invalidateUncommittedAuthorityLocked() {
        directActivation = null
    }

    private fun updateRetiringBarrierLocked(completedActivation: ActivationId) {
        val retiring = lifecycle as? ProtocolLifecycle.Retiring ?: return
        val remaining = retiring.inFlight - completedActivation
        lifecycle = if (remaining.isEmpty() && cleanupRequirements.isEmpty()) ProtocolLifecycle.Retired else ProtocolLifecycle.Retiring(remaining)
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

    private fun SideEffectReceipt.resourceIdentityOrNull(): ResourceIdentity? = when (this) {
        is SideEffectReceipt.NotStarted -> null
        is SideEffectReceipt.Completed -> resourceIdentity
        is SideEffectReceipt.PartialNeedsCleanup -> resourceIdentity
        is SideEffectReceipt.TerminalFailure -> resourceIdentity
    }
}
