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
    val destinationBound: Boolean = true,
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

    private enum class ActivationKind { PCM_CONFIGURE, RETAINED_PCM, DIRECT }

    @Synchronized
    fun registerAdapter(adapterInstanceId: AdapterInstanceId): Boolean {
        if (lifecycle !is ProtocolLifecycle.Active) return false
        adapters += adapterInstanceId
        return true
    }

    @Synchronized
    fun observeAdapterStarted(adapterInstanceId: AdapterInstanceId, occurrence: PlaybackOccurrence): Boolean {
        if (lifecycle !is ProtocolLifecycle.Active || adapterInstanceId !in adapters) return false
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

    @Synchronized
    fun updateApplicationCurrent(mediaId: String?, periodUid: Any?, occurrence: PlaybackOccurrence?) {
        if (lifecycle !is ProtocolLifecycle.Active) return
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
        if (kind == MutationKind.AUTO_NEXT) return null
        return beginMutationLocked(
            kind = kind,
            targetMediaId = targetMediaId,
            targetFamily = targetFamily,
            targetFacts = targetFacts,
            targetOccurrence = targetOccurrence,
            requestAlias = requestAlias,
            causalHandleFactory = causalHandleFactory,
        )
    }

    @Synchronized
    fun adoptAutoCandidate(mediaId: String, occurrence: PlaybackOccurrence): MutationEpoch? {
        if (lifecycle !is ProtocolLifecycle.Active) return null
        if (applicationCurrent.mediaId != mediaId || applicationCurrent.occurrence != occurrence) return null
        val candidate = candidates.singleOrNull { it.mediaId == mediaId && it.occurrence == occurrence } ?: return null
        return beginMutationLocked(
            kind = MutationKind.AUTO_NEXT,
            targetMediaId = mediaId,
            targetFamily = candidate.family,
            targetFacts = candidate.facts,
            targetOccurrence = occurrence,
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
        if (lifecycle !is ProtocolLifecycle.Active || targetMediaId.isBlank()) return null
        val source = familyOwnership
        val id = MutationId(nextMutationId + 1)
        issuedRetirementReceiptsByMutation.clear()
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
        targetFamily: PlaybackFamily,
        targetFacts: String,
        occurrence: PlaybackOccurrence,
    ): Boolean {
        if (lifecycle !is ProtocolLifecycle.Active || targetFacts.isBlank()) return false
        val epoch = mutation ?: return false
        if (epoch.mutationId != mutationId || epoch.kind != MutationKind.MANUAL) return false
        if (epoch.destinationBound) {
            return epoch.targetFamily == targetFamily &&
                epoch.targetFacts == targetFacts &&
                epoch.targetOccurrence == occurrence
        }
        mutation = epoch.copy(
            targetOccurrence = occurrence,
            targetFamily = targetFamily,
            targetFacts = targetFacts,
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
        requestAlias: RequestAlias? = null,
        causalHandleFactory: ((MutationId) -> MutationCausalHandle)? = null,
    ): MutationEpoch? {
        val source = familyOwnership
        val id = MutationId(nextMutationId + 1)
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
            handle
        } else {
            if (causalHandleFactory != null) return null
            null
        }
        issuedRetirementReceiptsByMutation.clear()
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
            causalHandle = causalHandle,
        )
        mutation = epoch
        return epoch
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
            commitFamilyLocked(
                PlaybackFamily.PCM,
                permit.mutationId,
                permit.adapterInstanceId,
                permit.occurrence,
                RuntimeIdentity("pcm:${receipt.resourceIdentityOrNull()?.value ?: "configured"}"),
                permit.facts,
                permit.activationId,
            )
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
        val facts = mutation?.targetFacts ?: return CommitDisposition.StaleNoEffect
        val result = commitFamilyLocked(
            PlaybackFamily.PCM,
            permit.mutationId,
            permit.adapterInstanceId,
            permit.occurrence,
            permit.runtimeIdentity,
            facts,
            permit.activationId,
        )
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
        }
        updateRetiringBarrierLocked(requirement.activationId)
        return disposition
    }

    @Synchronized
    fun updateOutputTarget(target: OutputTarget) {
        if (lifecycle !is ProtocolLifecycle.Active) return
        if (outputTarget != target) {
            issuedRetirementReceiptsByMutation.clear()
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
        issuedRetirementReceiptsByMutation.clear()
        familyOwnership.writeLeaseOrNull()?.revoke()
        reclassifyUncommittedAuthorityLocked(retiring = true)
        lifecycle = ProtocolLifecycle.Retiring(activations.keys.toSet())
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
    )

    @Synchronized
    fun currentWriteLease(): ActiveWriteLease? = familyOwnership.writeLeaseOrNull()

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
        if (!epoch.destinationBound) return false
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
            PlaybackFamily.PCM -> FamilyOwnership.PcmOwned(ownershipId, mutationId, occurrence, adapterInstanceId, paused, runtimeIdentity, facts, lease)
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
        lifecycle = if (activations.isEmpty() && cleanupRequirements.isEmpty() && committedLeaseDrained) {
            ProtocolLifecycle.Retired
        } else {
            ProtocolLifecycle.Retiring(activations.keys.toSet())
        }
    }

    private fun onCommittedWriteLeaseDrained(ownershipId: FamilyOwnershipId) {
        synchronized(this) {
            if (familyOwnership.ownershipIdOrNull() == ownershipId) {
                reevaluateRetiringLocked()
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
                        proof.compatibilityFacts.isNotBlank() &&
                        proof.tailOrderingProof.isNotBlank()
                is FamilyProof.StackReleased ->
                    receipt.scope != RetirementScope.SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED && proof.proof.isNotBlank()
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
            RetirementScope.FAMILY_RUNTIME_RELEASED,
            RetirementScope.STACK_TEARDOWN_RELEASED,
            -> receipt.familyProof is FamilyProof.StackReleased
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

    private fun SideEffectReceipt.resourceIdentityOrNull(): ResourceIdentity? = when (this) {
        is SideEffectReceipt.NotStarted -> null
        is SideEffectReceipt.Completed -> resourceIdentity
        is SideEffectReceipt.PartialNeedsCleanup -> resourceIdentity
        is SideEffectReceipt.TerminalFailure -> resourceIdentity
    }
}
