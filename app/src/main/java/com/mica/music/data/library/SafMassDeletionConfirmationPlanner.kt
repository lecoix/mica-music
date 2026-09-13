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

    fun isDueFor(
        existing: LibraryRetryItem?,
        removedStableObjectKeys: Set<String>,
        nowMs: Long,
    ): Boolean =
        existing != null &&
            existing.observedFingerprint == removalFingerprint(removedStableObjectKeys) &&
            existing.nextRetryAtMs <= nowMs

    fun continuationCursor(
        existing: LibraryRetryItem?,
        removedStableObjectKeys: Set<String>,
    ): Int =
        if (
            existing != null &&
            existing.observedFingerprint == removalFingerprint(removedStableObjectKeys)
        ) {
            existing.continuationCursor.coerceAtLeast(0)
        } else {
            0
        }

    fun plan(
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
        nowMs: Long,
        discoveryComplete: Boolean = true,
        quarantineReason: MassDeletionQuarantineReason?,
        removedStableObjectKeys: Set<String>,
        existing: LibraryRetryItem?,
        verification: SafIndependentMissingVerificationResult? = null,
    ): SafMassDeletionRetryPlan {
        val retryKey = LibraryRetryKey.safMassDeletionVerify()

        // Incomplete discovery cannot prove that a prior quarantine has disappeared. Preserve the
        // durable debt exactly as-is and let provider backoff schedule the next observation.
        if (!discoveryComplete) return SafMassDeletionRetryPlan()

        if (quarantineReason == null || removedStableObjectKeys.isEmpty()) {
            return SafMassDeletionRetryPlan(retryDeleteKeys = setOf(retryKey))
        }

        val fingerprint = removalFingerprint(removedStableObjectKeys)
        val sameFingerprint = existing?.observedFingerprint == fingerprint

        if (!sameFingerprint) {
            return scheduleRetry(
                sourceIdentity = sourceIdentity,
                activationEpoch = activationEpoch,
                nowMs = nowMs,
                quarantineReason = quarantineReason,
                fingerprint = fingerprint,
                attempt = 1,
                continuationCursor = 0,
            )
        }

        if (verification != null) {
            if (!verification.batchSucceeded) {
                return scheduleRetry(
                    sourceIdentity = sourceIdentity,
                    activationEpoch = activationEpoch,
                    nowMs = nowMs,
                    quarantineReason = quarantineReason,
                    fingerprint = fingerprint,
                    attempt = existing!!.attemptCount.coerceAtLeast(0) + 1,
                    continuationCursor = 0,
                )
            }

            if (verification.hasMore) {
                val continuation = existing!!.copy(
                    activationEpoch = activationEpoch,
                    failureKind = "MASS_DELETION_${quarantineReason.name}_CONTINUING",
                    nextRetryAtMs = nowMs,
                    continuationCursor = verification.nextCursor,
                )
                return SafMassDeletionRetryPlan(
                    retryUpserts = listOf(continuation),
                    nextRetryDelayMs = 0L,
                    requestBudgetContinuation = true,
                )
            }

            // A successful final batch should cause the publication planner to clear quarantine and
            // enter the deletion branch above. If it did not, fail closed and retry from the start.
            return scheduleRetry(
                sourceIdentity = sourceIdentity,
                activationEpoch = activationEpoch,
                nowMs = nowMs,
                quarantineReason = quarantineReason,
                fingerprint = fingerprint,
                attempt = existing!!.attemptCount.coerceAtLeast(0) + 1,
                continuationCursor = 0,
            )
        }

        if (existing!!.nextRetryAtMs > nowMs) {
            return SafMassDeletionRetryPlan(
                nextRetryDelayMs = (existing.nextRetryAtMs - nowMs).coerceAtLeast(0L),
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
