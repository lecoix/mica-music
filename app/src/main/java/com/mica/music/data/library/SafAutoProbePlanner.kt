package com.mica.music.data.library

import com.mica.music.data.scanner.SafFastVerifyPlan
import com.mica.music.data.scanner.SafTreeMetadataEntry

internal enum class SafAutoProbeReason {
    NEW_OBJECT,
    METADATA_OR_FINGERPRINT_CHANGED,
    UNKNOWN_FINGERPRINT_VERIFY,
    RETRY_LEDGER,
}

internal enum class SafAutoProbeDisposition {
    READY,
    DEFER_UNTIL_PLAYBACK_RELEASE,
    DEFER_BY_HEAVY_PROBE_BUDGET,
}

internal data class SafAutoProbeObjectPlan(
    val entry: SafTreeMetadataEntry,
    val reasons: Set<SafAutoProbeReason>,
    val disposition: SafAutoProbeDisposition,
) {
    val stableObjectKey: String
        get() = entry.stableObjectKey
}

internal data class SafAutoProbePlan(
    val objects: List<SafAutoProbeObjectPlan>,
    val heavyProbeParallelism: Int,
    val unknownFingerprintCandidateCount: Int = 0,
    val unknownFingerprintDueCount: Int = 0,
    val unknownFingerprintSelectedCount: Int = 0,
    val unknownFingerprintSuppressedByCauseCount: Int = 0,
    val dueRetryCount: Int = 0,
    val retryMissingObservationCount: Int = 0,
) {
    val ready: List<SafAutoProbeObjectPlan>
        get() = objects.filter { it.disposition == SafAutoProbeDisposition.READY }

    val deferred: List<SafAutoProbeObjectPlan>
        get() = objects.filter {
            it.disposition == SafAutoProbeDisposition.DEFER_UNTIL_PLAYBACK_RELEASE
        }

    val budgetDeferred: List<SafAutoProbeObjectPlan>
        get() = objects.filter {
            it.disposition == SafAutoProbeDisposition.DEFER_BY_HEAVY_PROBE_BUDGET
        }

    val unknownFingerprintDeferredByBudgetCount: Int
        get() = (
            unknownFingerprintDueCount -
                unknownFingerprintSelectedCount -
                unknownFingerprintSuppressedByCauseCount
            ).coerceAtLeast(0)

    val unknownFingerprintNotDueCount: Int
        get() = (unknownFingerprintCandidateCount - unknownFingerprintDueCount)
            .coerceAtLeast(0)

    val isNoOp: Boolean
        get() = objects.isEmpty()
}

internal fun SafAutoProbePlan.shouldRequestBudgetContinuation(
    execution: SafShadowProbeExecutionResult,
): Boolean {
    val hasNonUnknownHeavyDebt = budgetDeferred.any { objectPlan ->
        objectPlan.reasons.any { it != SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY }
    }
    if (hasNonUnknownHeavyDebt) return true

    val hasPureUnknownHeavyDebt = budgetDeferred.any { objectPlan ->
        objectPlan.reasons.isNotEmpty() &&
            objectPlan.reasons.all { it == SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY }
    }
    val hasUnknownSelectionDebt =
        unknownFingerprintDeferredByBudgetCount > 0 || hasPureUnknownHeavyDebt
    if (!hasUnknownSelectionDebt) return false

    val unknownWallBudgetExhausted = execution.issues.any {
        it.kind == SafShadowProbeIssueKind.UNKNOWN_VERIFY_BUDGET_DEFERRED
    }
    if (unknownWallBudgetExhausted) return false

    // Do not spin while every selected UNKNOWN object is playback-deferred or otherwise failed
    // before producing a stable strong fingerprint. Pure UNKNOWN work deferred only by the heavy
    // object budget is safe to resume on the next pass because it has not consumed wall budget yet.
    return hasPureUnknownHeavyDebt ||
        execution.strongValidatedFingerprintsByStableObjectKey.isNotEmpty()
}

internal object SafAutoProbePlanner {
    private const val CONSERVATIVE_HEAVY_PROBE_PARALLELISM = 1
    internal const val DEFAULT_HEAVY_PROBE_BUDGET = 32

    /**
     * S4-frozen UNKNOWN deep-verify object cap.
     *
     * Kept equal to the reliable heavy-probe cap so one AUTO pass has one conservative serialized
     * IO budget. The independent wall-time cap can stop a slow provider before all 32 are consumed.
     */
    internal const val UNKNOWN_VERIFY_OBJECT_BUDGET = 32

    fun plan(
        verifyPlan: SafFastVerifyPlan,
        playback: LibraryPlaybackIoSnapshot,
        observedEntries: List<SafTreeMetadataEntry> =
            verifyPlan.added + verifyPlan.changed + verifyPlan.unknownFingerprint,
        retryItems: List<LibraryRetryItem> = emptyList(),
        sourceIdentity: SourceIdentityKey? = null,
        activationEpoch: Long? = null,
        nowMs: Long = 0L,
        allowUnknownFingerprintVerify: Boolean = true,
        heavyProbeBudget: Int = DEFAULT_HEAVY_PROBE_BUDGET,
        unknownVerifyBudget: Int = UNKNOWN_VERIFY_OBJECT_BUDGET,
        alreadyResolvedStableObjectKeys: Set<String> = emptySet(),
    ): SafAutoProbePlan {
        require(heavyProbeBudget >= 0)
        require(unknownVerifyBudget >= 0)

        val reasonsByKey = linkedMapOf<String, MutableSet<SafAutoProbeReason>>()
        val entriesByKey = linkedMapOf<String, SafTreeMetadataEntry>()

        fun add(entry: SafTreeMetadataEntry, reason: SafAutoProbeReason) {
            if (entry.stableObjectKey in alreadyResolvedStableObjectKeys) return
            entriesByKey[entry.stableObjectKey] = entry
            reasonsByKey.getOrPut(entry.stableObjectKey) { linkedSetOf() } += reason
        }

        verifyPlan.added.forEach { add(it, SafAutoProbeReason.NEW_OBJECT) }
        verifyPlan.changed.forEach {
            add(it, SafAutoProbeReason.METADATA_OR_FINGERPRINT_CHANGED)
        }

        val unknownCandidates = verifyPlan.unknownFingerprint
            .distinctBy(SafTreeMetadataEntry::stableObjectKey)
            .filterNot { it.stableObjectKey in alreadyResolvedStableObjectKeys }
        val unknownDue = selectUnknownFingerprintVerifyDue(
            candidates = unknownCandidates,
            retryItems = retryItems,
            sourceIdentity = sourceIdentity,
            activationEpoch = activationEpoch,
            nowMs = nowMs,
        )
        val selectedUnknown = if (allowUnknownFingerprintVerify) {
            unknownDue.take(unknownVerifyBudget)
        } else {
            emptyList()
        }
        selectedUnknown.forEach {
            add(it, SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY)
        }

        val observedByKey = observedEntries
            .associateBy(SafTreeMetadataEntry::stableObjectKey)
        val dueRetries = retryItems.asSequence()
            .filter { retry ->
                sourceIdentity != null &&
                    retry.sourceIdentity == sourceIdentity &&
                    retry.retryKind == LibraryRetryKind.OBJECT_PROBE &&
                    (retry.activationEpoch == null || retry.activationEpoch == activationEpoch) &&
                    retry.nextRetryAtMs <= nowMs &&
                    retry.stableObjectKey !in alreadyResolvedStableObjectKeys
            }
            .toList()
        var retryMissingObservationCount = 0
        dueRetries.forEach { retry ->
            val entry = observedByKey[retry.stableObjectKey]
            if (entry == null) {
                retryMissingObservationCount += 1
            } else {
                add(entry, SafAutoProbeReason.RETRY_LEDGER)
            }
        }

        var remainingHeavyProbeBudget = heavyProbeBudget
        val objects = entriesByKey.values
            .sortedBy(SafTreeMetadataEntry::stableObjectKey)
            .map { entry ->
                val disposition = when {
                    playback.blocksHeavyProbe(
                        stableObjectKey = entry.stableObjectKey,
                        mediaUri = entry.mediaUri,
                    ) -> SafAutoProbeDisposition.DEFER_UNTIL_PLAYBACK_RELEASE
                    remainingHeavyProbeBudget > 0 -> {
                        remainingHeavyProbeBudget -= 1
                        SafAutoProbeDisposition.READY
                    }
                    else -> SafAutoProbeDisposition.DEFER_BY_HEAVY_PROBE_BUDGET
                }
                SafAutoProbeObjectPlan(
                    entry = entry,
                    reasons = reasonsByKey.getValue(entry.stableObjectKey).toSet(),
                    disposition = disposition,
                )
            }

        return SafAutoProbePlan(
            objects = objects,
            heavyProbeParallelism = CONSERVATIVE_HEAVY_PROBE_PARALLELISM,
            unknownFingerprintCandidateCount = unknownCandidates.size,
            unknownFingerprintDueCount = unknownDue.size,
            unknownFingerprintSelectedCount = selectedUnknown.size,
            unknownFingerprintSuppressedByCauseCount =
                if (allowUnknownFingerprintVerify) 0 else unknownDue.size,
            dueRetryCount = dueRetries.size,
            retryMissingObservationCount = retryMissingObservationCount,
        )
    }

    private fun selectUnknownFingerprintVerifyDue(
        candidates: List<SafTreeMetadataEntry>,
        retryItems: List<LibraryRetryItem>,
        sourceIdentity: SourceIdentityKey?,
        activationEpoch: Long?,
        nowMs: Long,
    ): List<SafTreeMetadataEntry> {
        if (candidates.isEmpty()) return emptyList()

        val scopedItems = retryItems.asSequence()
            .filter { retry ->
                sourceIdentity == null ||
                    (
                        retry.sourceIdentity == sourceIdentity &&
                            (retry.activationEpoch == null ||
                                retry.activationEpoch == activationEpoch)
                        )
            }
            .toList()
        val unknownDebtByKey = scopedItems.asSequence()
            .filter { it.retryKind == LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY }
            .groupBy(LibraryRetryItem::stableObjectKey)
            .mapValues { (_, items) -> items.maxByOrNull(LibraryRetryItem::nextRetryAtMs)!! }
        val objectRetryByKey = scopedItems.asSequence()
            .filter { it.retryKind == LibraryRetryKind.OBJECT_PROBE }
            .groupBy(LibraryRetryItem::stableObjectKey)
            .mapValues { (_, items) -> items.maxByOrNull(LibraryRetryItem::nextRetryAtMs)!! }

        return candidates.asSequence()
            .map { entry ->
                val debt = unknownDebtByKey[entry.stableObjectKey]
                val currentObservedFingerprint = safObservedFingerprint(entry)
                val debtDueAt = when {
                    debt == null -> Long.MIN_VALUE
                    debt.observedFingerprint != currentObservedFingerprint -> Long.MIN_VALUE
                    else -> debt.nextRetryAtMs
                }
                val objectRetryBlocksUntil =
                    objectRetryByKey[entry.stableObjectKey]
                        ?.nextRetryAtMs
                        ?.takeIf { it > nowMs }
                        ?: Long.MIN_VALUE
                val effectiveDueAt = maxOf(debtDueAt, objectRetryBlocksUntil)
                Triple(entry, effectiveDueAt, currentObservedFingerprint)
            }
            .filter { (_, dueAt, _) -> dueAt <= nowMs }
            .sortedWith(
                compareBy<Triple<SafTreeMetadataEntry, Long, String>>(
                    { it.second },
                    { it.first.stableObjectKey },
                ),
            )
            .map { it.first }
            .toList()
    }
}
