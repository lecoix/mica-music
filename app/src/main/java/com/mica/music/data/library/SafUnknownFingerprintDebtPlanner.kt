package com.mica.music.data.library

import com.mica.music.data.scanner.SafFingerprintReliability
import com.mica.music.data.scanner.SafTreeMetadataEntry

internal data class SafUnknownFingerprintDebtPlan(
    val retryUpserts: List<LibraryRetryItem>,
    val retryDeleteKeys: Set<String>,
    val observedUnknownCount: Int,
    val selectedDeepVerifyCount: Int,
    val strongVerifiedCount: Int,
) {
    val isNoOp: Boolean
        get() = retryUpserts.isEmpty() && retryDeleteKeys.isEmpty()
}

internal object SafUnknownFingerprintDebtPlanner {
    /**
     * S4-frozen successful UNKNOWN deep-verify cadence.
     *
     * The 10k production-path profiler measured a complete strong-verify sweep at about 12.6
     * minutes and 20.8 GB of logical double-SHA reads even with ~1 MiB audio fixtures. Keeping the
     * old 6-hour provisional cadence would multiply that pathological fallback cost fourfold per
     * day. A 24-hour cadence keeps the fallback bounded to one successful sweep per day while
     * retaining day-scale detection for providers that expose no reliable size/mtime fingerprint.
     * User Full Scan can still repay debt earlier.
     */
    internal const val UNKNOWN_VERIFY_INTERVAL_MS = 24L * 60L * 60L * 1000L
    /**
     * Keep housekeeping mutation bounded by the same conservative object cap as one deep-verify
     * pass. Stale UNKNOWN rows are not authority: leaving excess cleanup for a later pass cannot
     * suppress a current observation because planner due-ness also compares the live weak
     * fingerprint.
     */
    internal const val UNKNOWN_DEBT_CLEANUP_BUDGET = SafAutoProbePlanner.UNKNOWN_VERIFY_OBJECT_BUDGET
    private const val FAILURE_RETRY_DELAY_MS = 30_000L

    fun plan(
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
        nowMs: Long,
        allObservedEntries: Collection<SafTreeMetadataEntry>,
        existingRetryItems: List<LibraryRetryItem>,
        probePlan: SafAutoProbePlan,
        execution: SafShadowProbeExecutionResult,
        validation: SafShadowPostValidationResult,
        authoritativeRemovedStableObjectKeys: Set<String>,
        verifyIntervalMs: Long = UNKNOWN_VERIFY_INTERVAL_MS,
    ): SafUnknownFingerprintDebtPlan {
        require(verifyIntervalMs >= 0L)

        val selectedUnknownKeys = probePlan.objects.asSequence()
            .filter { SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY in it.reasons }
            .mapTo(linkedSetOf(), SafAutoProbeObjectPlan::stableObjectKey)
        val cleanupStableKeys = linkedSetOf<String>()
        val existingByKey = linkedMapOf<String, MutableList<LibraryRetryItem>>()
        existingRetryItems.asSequence()
            .filter { it.sourceIdentity == sourceIdentity }
            .filter { it.retryKind == LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY }
            .filter { it.activationEpoch == null || it.activationEpoch == activationEpoch }
            .forEach { item ->
                val selected = item.stableObjectKey in selectedUnknownKeys
                val retainedForCleanup = item.stableObjectKey in cleanupStableKeys
                if (!selected && !retainedForCleanup) {
                    if (cleanupStableKeys.size >= UNKNOWN_DEBT_CLEANUP_BUDGET) {
                        return@forEach
                    }
                    cleanupStableKeys += item.stableObjectKey
                }
                existingByKey.getOrPut(item.stableObjectKey) { mutableListOf() } += item
            }
        val issueByKey = validation.issues
            .groupBy(SafShadowProbeIssue::stableObjectKey)
            .mapValues { (_, issues) -> issues.last() }

        // UNKNOWN inventory itself is pass-scoped O(N), but debt mutation is bounded by the
        // selected verify budget plus the bounded RetryLedger rows loaded for this pass. Do not
        // retain another 10k-sized map just to update those target keys.
        val targetStableKeys = buildSet {
            addAll(selectedUnknownKeys)
            addAll(existingByKey.keys)
        }
        var observedUnknownCount = 0
        val targetEntriesByKey = linkedMapOf<String, SafTreeMetadataEntry>()
        allObservedEntries.forEach { entry ->
            if (entry.fingerprintReliability == SafFingerprintReliability.UNKNOWN) {
                observedUnknownCount += 1
            }
            if (entry.stableObjectKey in targetStableKeys) {
                targetEntriesByKey[entry.stableObjectKey] = entry
            }
        }

        val cleanupDeleteCandidates = sortedSetOf<String>()
        existingByKey.forEach { (stableKey, items) ->
            val observed = targetEntriesByKey[stableKey]
            if (
                stableKey in authoritativeRemovedStableObjectKeys ||
                (observed != null &&
                    observed.fingerprintReliability != SafFingerprintReliability.UNKNOWN)
            ) {
                items.mapTo(cleanupDeleteCandidates, LibraryRetryItem::retryKey)
            }
        }
        val deleteKeys = cleanupDeleteCandidates
            .asSequence()
            .take(UNKNOWN_DEBT_CLEANUP_BUDGET)
            .toCollection(linkedSetOf())

        val upserts = mutableListOf<LibraryRetryItem>()
        var strongVerifiedCount = 0
        selectedUnknownKeys.forEach { stableKey ->
            val entry = targetEntriesByKey[stableKey]
                ?.takeIf { it.fingerprintReliability == SafFingerprintReliability.UNKNOWN }
                ?: return@forEach
            val previousItems = existingByKey[stableKey].orEmpty()
            val previous = previousItems.maxByOrNull(LibraryRetryItem::nextRetryAtMs)
            val canonicalRetryKey = retryKey(stableKey)
            val currentWeakObservation = safObservedFingerprint(entry)
            val strongFingerprint =
                execution.strongValidatedFingerprintsByStableObjectKey[stableKey]
                    ?.takeIf { stableKey in validation.resolvedSongsByStableObjectKey }
            val selected = stableKey in selectedUnknownKeys
            val issue = issueByKey[stableKey]

            val nextItem = when {
                strongFingerprint != null -> {
                    strongVerifiedCount += 1
                    LibraryRetryItem(
                        sourceIdentity = sourceIdentity,
                        retryKey = canonicalRetryKey,
                        activationEpoch = activationEpoch,
                        stableObjectKey = stableKey,
                        observedFingerprint = currentWeakObservation,
                        retryKind = LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY,
                        failureKind = "UNKNOWN_DEEP_VERIFY_OK:$strongFingerprint",
                        attemptCount = (previous?.attemptCount ?: 0) + 1,
                        nextRetryAtMs = safeAdd(nowMs, verifyIntervalMs),
                    )
                }

                selected &&
                    issue != null &&
                    issue.kind != SafShadowProbeIssueKind.PLAYBACK_DEFERRED &&
                    issue.kind != SafShadowProbeIssueKind.UNKNOWN_VERIFY_BUDGET_DEFERRED -> {
                    LibraryRetryItem(
                        sourceIdentity = sourceIdentity,
                        retryKey = canonicalRetryKey,
                        activationEpoch = activationEpoch,
                        stableObjectKey = stableKey,
                        observedFingerprint = currentWeakObservation,
                        retryKind = LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY,
                        failureKind = "UNKNOWN_DEEP_VERIFY_${issue.kind.name}",
                        attemptCount = previous?.attemptCount ?: 0,
                        nextRetryAtMs = safeAdd(nowMs, FAILURE_RETRY_DELAY_MS),
                    )
                }

                selected && previous == null -> {
                    LibraryRetryItem(
                        sourceIdentity = sourceIdentity,
                        retryKey = canonicalRetryKey,
                        activationEpoch = activationEpoch,
                        stableObjectKey = stableKey,
                        observedFingerprint = currentWeakObservation,
                        retryKind = LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY,
                        failureKind = "UNKNOWN_PENDING",
                        attemptCount = 0,
                        nextRetryAtMs = nowMs,
                    )
                }

                selected && previous != null &&
                    previous.observedFingerprint != currentWeakObservation -> {
                    LibraryRetryItem(
                        sourceIdentity = sourceIdentity,
                        retryKey = canonicalRetryKey,
                        activationEpoch = activationEpoch,
                        stableObjectKey = stableKey,
                        observedFingerprint = currentWeakObservation,
                        retryKind = LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY,
                        failureKind = "UNKNOWN_INPUT_CHANGED",
                        attemptCount = 0,
                        nextRetryAtMs = nowMs,
                    )
                }

                else -> null
            }

            if (nextItem != null) {
                previousItems.mapTo(deleteKeys, LibraryRetryItem::retryKey)
                upserts += nextItem
            }
        }

        val upsertKeys = upserts.mapTo(hashSetOf(), LibraryRetryItem::retryKey)
        deleteKeys.removeAll(upsertKeys)

        return SafUnknownFingerprintDebtPlan(
            retryUpserts = upserts.sortedBy(LibraryRetryItem::retryKey),
            retryDeleteKeys = deleteKeys.toSortedSet(),
            observedUnknownCount = observedUnknownCount,
            selectedDeepVerifyCount = selectedUnknownKeys.size,
            strongVerifiedCount = strongVerifiedCount,
        )
    }

    internal fun retryKey(stableObjectKey: String): String =
        LibraryRetryKey.safUnknownFingerprint(stableObjectKey)

    private fun safeAdd(nowMs: Long, delayMs: Long): Long =
        if (Long.MAX_VALUE - nowMs < delayMs) Long.MAX_VALUE else nowMs + delayMs
}
