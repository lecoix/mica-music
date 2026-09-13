package com.mica.music.data.library

import com.mica.music.data.scanner.SafIndependentMissingVerificationResult
import java.security.MessageDigest

internal data class SafMassDeletionRetryPlan(
    val retryUpserts: List<LibraryRetryItem> = emptyList(),
    val retryDeleteKeys: Set<String> = emptySet(),
    val nextRetryDelayMs: Long? = null,
    val requestBudgetContinuation: Boolean = false,
)

internal object SafMassDeletionConfirmationPlanner {
    private const val BASE_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 30L * 60L * 1000L
    private const val CONFIRMED_KEYS_SEPARATOR = '\u0000'

    fun selectExisting(items: Collection<LibraryRetryItem>): LibraryRetryItem? =
        items.firstOrNull {
            it.retryKey == LibraryRetryKey.safMassDeletionVerify() &&
                it.retryKind == LibraryRetryKind.DISCOVERY_PARTITION
        }

    fun removalFingerprint(keys: Set<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        keys.asSequence().sorted().forEach { key ->
            digest.update(key.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        val hex = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        return "mass-removal-v1:${keys.size}:$hex"
    }

    /**
     * A persisted confirmation debt is only reusable as proof/progress for the current quarantine
     * round when it was produced under the same source activation and for the same removal set.
     * Anything else is stale evidence and must not shorten the current round.
     */
    fun matchesCurrentRound(
        existing: LibraryRetryItem?,
        activationEpoch: Long,
        removedStableObjectKeys: Set<String>,
    ): Boolean =
        existing != null &&
            existing.activationEpoch == activationEpoch &&
            existing.observedFingerprint == removalFingerprint(removedStableObjectKeys)

    fun isDueFor(
        existing: LibraryRetryItem?,
        activationEpoch: Long,
        removedStableObjectKeys: Set<String>,
        nowMs: Long,
    ): Boolean =
        matchesCurrentRound(existing, activationEpoch, removedStableObjectKeys) &&
            existing!!.nextRetryAtMs <= nowMs

    fun continuationCursor(
        existing: LibraryRetryItem?,
        activationEpoch: Long,
        removedStableObjectKeys: Set<String>,
    ): Int =
        if (matchesCurrentRound(existing, activationEpoch, removedStableObjectKeys)) {
            existing!!.continuationCursor.coerceAtLeast(0)
        } else {
            0
        }

    /**
     * Object-level missing keys already proven in earlier batches of the current quarantine round.
     * Cursor alone must never be treated as proof for keys that were not recorded here.
     */
    fun confirmedMissingKeys(
        existing: LibraryRetryItem?,
        activationEpoch: Long,
        removedStableObjectKeys: Set<String>,
    ): Set<String> =
        if (matchesCurrentRound(existing, activationEpoch, removedStableObjectKeys)) {
            decodeConfirmedMissingKeys(existing!!.confirmedMissingKeysPayload)
                .filterTo(linkedSetOf()) { it in removedStableObjectKeys }
        } else {
            emptySet()
        }

    fun encodeConfirmedMissingKeys(keys: Set<String>): String =
        keys.asSequence().sorted().joinToString(separator = CONFIRMED_KEYS_SEPARATOR.toString())

    fun decodeConfirmedMissingKeys(payload: String): Set<String> {
        if (payload.isEmpty()) return emptySet()
        return payload.split(CONFIRMED_KEYS_SEPARATOR)
            .filterTo(linkedSetOf()) { it.isNotEmpty() }
    }

    /**
     * A fresh Present observation always beats historical missing proof, regardless of whether the
     * surrounding discovery is FULL, AUTO or PARTIAL. If any proved-missing object is observed
     * again, reset traversal progress too: should that object disappear again later, it must be
     * independently verified from the beginning rather than inherit progress from disproved proof.
     */
    fun invalidatePresentEvidence(
        existing: LibraryRetryItem?,
        observedPresentStableObjectKeys: Set<String>,
    ): LibraryRetryItem? {
        if (existing == null || observedPresentStableObjectKeys.isEmpty()) return existing
        val prior = decodeConfirmedMissingKeys(existing.confirmedMissingKeysPayload)
        if (prior.isEmpty()) return existing
        val retained = prior - observedPresentStableObjectKeys
        if (retained.size == prior.size) return existing
        return existing.copy(
            continuationCursor = 0,
            confirmedMissingKeysPayload = encodeConfirmedMissingKeys(retained),
        )
    }

    /**
     * Accumulates this batch's verified-missing keys onto any durable proof from earlier batches.
     * Only keys that still belong to the current removal set are retained.
     */
    fun accumulateConfirmedMissingKeys(
        existing: LibraryRetryItem?,
        activationEpoch: Long,
        removedStableObjectKeys: Set<String>,
        batchVerifiedMissingKeys: Set<String>,
    ): Set<String> {
        val prior = confirmedMissingKeys(existing, activationEpoch, removedStableObjectKeys)
        return (prior + batchVerifiedMissingKeys)
            .filterTo(linkedSetOf()) { it in removedStableObjectKeys }
    }

    fun plan(
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
        nowMs: Long,
        discoveryComplete: Boolean = true,
        quarantineReason: MassDeletionQuarantineReason?,
        removedStableObjectKeys: Set<String>,
        existing: LibraryRetryItem?,
        observedPresentStableObjectKeys: Set<String> = emptySet(),
        verification: SafIndependentMissingVerificationResult? = null,
    ): SafMassDeletionRetryPlan {
        val retryKey = LibraryRetryKey.safMassDeletionVerify()
        val reconciledExisting = invalidatePresentEvidence(
            existing = existing,
            observedPresentStableObjectKeys = observedPresentStableObjectKeys,
        )
        val presentInvalidatedProof = reconciledExisting != existing

        // Incomplete discovery cannot prove that a prior quarantine has disappeared, so preserve
        // the debt/backoff. It can positively prove individual objects are present, however, and
        // that fresh fact must revoke their old missing proof even on a PARTIAL pass.
        if (!discoveryComplete) {
            return if (presentInvalidatedProof && reconciledExisting != null) {
                SafMassDeletionRetryPlan(retryUpserts = listOf(reconciledExisting))
            } else {
                SafMassDeletionRetryPlan()
            }
        }

        // No quarantine in a complete observation means any persisted confirmation debt is stale.
        if (quarantineReason == null || removedStableObjectKeys.isEmpty()) {
            return if (reconciledExisting != null) {
                SafMassDeletionRetryPlan(retryDeleteKeys = setOf(retryKey))
            } else {
                SafMassDeletionRetryPlan()
            }
        }

        val fingerprint = removalFingerprint(removedStableObjectKeys)
        val sameRound = matchesCurrentRound(reconciledExisting, activationEpoch, removedStableObjectKeys)

        if (!sameRound) {
            return scheduleRetry(
                sourceIdentity = sourceIdentity,
                activationEpoch = activationEpoch,
                nowMs = nowMs,
                quarantineReason = quarantineReason,
                fingerprint = fingerprint,
                attempt = 1,
                continuationCursor = 0,
                confirmedMissingKeysPayload = "",
            )
        }

        val current = checkNotNull(reconciledExisting)
        if (verification != null) {
            if (!verification.batchSucceeded) {
                return scheduleRetry(
                    sourceIdentity = sourceIdentity,
                    activationEpoch = activationEpoch,
                    nowMs = nowMs,
                    quarantineReason = quarantineReason,
                    fingerprint = fingerprint,
                    attempt = current.attemptCount.coerceAtLeast(0) + 1,
                    continuationCursor = 0,
                    confirmedMissingKeysPayload = "",
                )
            }

            val accumulated = accumulateConfirmedMissingKeys(
                existing = current,
                activationEpoch = activationEpoch,
                removedStableObjectKeys = removedStableObjectKeys,
                batchVerifiedMissingKeys = verification.verifiedMissingStableObjectKeys,
            )
            val accumulatedPayload = encodeConfirmedMissingKeys(accumulated)

            if (verification.madeNoProgress) {
                return scheduleRetry(
                    sourceIdentity = sourceIdentity,
                    activationEpoch = activationEpoch,
                    nowMs = nowMs,
                    quarantineReason = quarantineReason,
                    fingerprint = fingerprint,
                    attempt = current.attemptCount.coerceAtLeast(0) + 1,
                    continuationCursor = current.continuationCursor.coerceAtLeast(0),
                    confirmedMissingKeysPayload = accumulatedPayload,
                )
            }

            if (verification.hasMore) {
                val continuation = current.copy(
                    activationEpoch = activationEpoch,
                    failureKind = "MASS_DELETION_${quarantineReason.name}_CONTINUING",
                    nextRetryAtMs = nowMs,
                    continuationCursor = verification.nextCursor,
                    confirmedMissingKeysPayload = accumulatedPayload,
                )
                return SafMassDeletionRetryPlan(
                    retryUpserts = listOf(continuation),
                    nextRetryDelayMs = 0L,
                    requestBudgetContinuation = true,
                )
            }

            // A successful final batch should have produced durable proof for every quarantined
            // key. If publication still remains quarantined, fail closed and restart cleanly.
            return scheduleRetry(
                sourceIdentity = sourceIdentity,
                activationEpoch = activationEpoch,
                nowMs = nowMs,
                quarantineReason = quarantineReason,
                fingerprint = fingerprint,
                attempt = current.attemptCount.coerceAtLeast(0) + 1,
                continuationCursor = 0,
                confirmedMissingKeysPayload = "",
            )
        }

        if (current.nextRetryAtMs > nowMs) {
            return SafMassDeletionRetryPlan(
                nextRetryDelayMs = (current.nextRetryAtMs - nowMs).coerceAtLeast(0L),
            )
        }

        // Due work is only advanced after an actual independent verification result. If the caller
        // did not run one, preserve the debt instead of inventing another failed attempt.
        return SafMassDeletionRetryPlan()
    }

    private fun scheduleRetry(
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
        nowMs: Long,
        quarantineReason: MassDeletionQuarantineReason,
        fingerprint: String,
        attempt: Int,
        continuationCursor: Int,
        confirmedMissingKeysPayload: String,
    ): SafMassDeletionRetryPlan {
        val delayMs = retryDelayMs(attempt)
        val retry = LibraryRetryItem(
            sourceIdentity = sourceIdentity,
            retryKey = LibraryRetryKey.safMassDeletionVerify(),
            activationEpoch = activationEpoch,
            stableObjectKey = LibraryRetryKey.SAF_MASS_DELETION_STABLE_OBJECT_KEY,
            observedFingerprint = fingerprint,
            retryKind = LibraryRetryKind.DISCOVERY_PARTITION,
            failureKind = "MASS_DELETION_${quarantineReason.name}",
            attemptCount = attempt,
            nextRetryAtMs = safeAdd(nowMs, delayMs),
            continuationCursor = continuationCursor,
            confirmedMissingKeysPayload = confirmedMissingKeysPayload,
        )
        return SafMassDeletionRetryPlan(
            retryUpserts = listOf(retry),
            nextRetryDelayMs = delayMs,
        )
    }

    private fun retryDelayMs(attempt: Int): Long {
        var delay = BASE_DELAY_MS
        repeat((attempt - 1).coerceAtLeast(0)) {
            if (delay >= MAX_DELAY_MS) return MAX_DELAY_MS
            delay = (delay * 2L).coerceAtMost(MAX_DELAY_MS)
        }
        return delay
    }

    private fun safeAdd(nowMs: Long, delayMs: Long): Long =
        if (Long.MAX_VALUE - nowMs < delayMs) Long.MAX_VALUE else nowMs + delayMs
}


internal fun SafAutoSyncPublicationPlan.withMassDeletionRetryPlan(
    retryPlan: SafMassDeletionRetryPlan,
): SafAutoSyncPublicationPlan {
    if (retryPlan.retryUpserts.isEmpty() && retryPlan.retryDeleteKeys.isEmpty()) return this
    val mergedUpserts = (
        autoSyncStateMutation.retryUpserts + retryPlan.retryUpserts
    ).associateBy(LibraryRetryItem::retryKey)
        .values
        .sortedBy(LibraryRetryItem::retryKey)
    val upsertKeys = mergedUpserts.mapTo(hashSetOf(), LibraryRetryItem::retryKey)
    val mergedDeletes = (
        autoSyncStateMutation.retryDeleteKeys + retryPlan.retryDeleteKeys
    ).filterNotTo(sortedSetOf()) { it in upsertKeys }
    return copy(
        autoSyncStateMutation = autoSyncStateMutation.copy(
            retryUpserts = mergedUpserts,
            retryDeleteKeys = mergedDeletes,
        ),
    )
}
