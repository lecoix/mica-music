package com.mica.music.data.library

import com.mica.music.data.scanner.SafIndependentMissingVerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafMassDeletionConfirmationPlannerTest {
    private val source = SourceIdentityKey.folder("content://provider/tree/music")
    private val removed = (1..80).mapTo(linkedSetOf()) { "doc-$it" }

    @Test
    fun zeroProgressTimeoutBacksOffInsteadOfRequestingImmediateContinuation() {
        val existing = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 1_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = null,
        ).retryUpserts.single().copy(continuationCursor = 64)
        val plan = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = existing.nextRetryAtMs,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = existing,
            verification = SafIndependentMissingVerificationResult(
                verifiedMissingStableObjectKeys = emptySet(),
                presentStableObjectKeys = emptySet(),
                indeterminateStableObjectKeys = emptySet(),
                nextCursor = 64,
                hasMore = true,
                budgetExhausted = true,
            ),
        )
        assertTrue(!plan.requestBudgetContinuation)
        val backedOff = plan.retryUpserts.single()
        assertEquals(2, backedOff.attemptCount)
        // Confirmed progress inside the same round is kept; only the attempt escalates.
        assertEquals(64, backedOff.continuationCursor)
        assertEquals(existing.observedFingerprint, backedOff.observedFingerprint)
        assertTrue(requireNotNull(plan.nextRetryDelayMs) > 0L)
    }

    @Test
    fun debtFromAnotherActivationEpochIsNotReusedAsProgress() {
        val staleRound = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 1_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = null,
        ).retryUpserts.single().copy(attemptCount = 4, continuationCursor = 64)

        assertTrue(
            !SafMassDeletionConfirmationPlanner.isDueFor(
                existing = staleRound,
                activationEpoch = 4L,
                removedStableObjectKeys = removed,
                nowMs = 999_999L,
            ),
        )
        assertEquals(
            0,
            SafMassDeletionConfirmationPlanner.continuationCursor(
                existing = staleRound,
                activationEpoch = 4L,
                removedStableObjectKeys = removed,
            ),
        )
        val fresh = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 4L,
            nowMs = 100_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = staleRound,
        ).retryUpserts.single()
        assertEquals(1, fresh.attemptCount)
        assertEquals(0, fresh.continuationCursor)
        assertEquals(4L, fresh.activationEpoch)
        assertEquals(130_000L, fresh.nextRetryAtMs)
    }

    @Test
    fun resolvedQuarantineWithoutPersistedDebtIsNoOp() {
        val plan = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 100_000L,
            quarantineReason = null,
            removedStableObjectKeys = removed,
            existing = null,
        )

        assertTrue(plan.retryUpserts.isEmpty())
        assertTrue(plan.retryDeleteKeys.isEmpty())
        assertEquals(null, plan.nextRetryDelayMs)
        assertTrue(!plan.requestBudgetContinuation)
    }

    @Test
    fun firstQuarantineCreatesDurableThirtySecondConfirmationDebt() {
        val plan = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 1_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = null,
        )

        val retry = plan.retryUpserts.single()
        assertEquals(LibraryRetryKind.DISCOVERY_PARTITION, retry.retryKind)
        assertEquals(LibraryRetryKey.SAF_MASS_DELETION_STABLE_OBJECT_KEY, retry.stableObjectKey)
        assertEquals(LibraryRetryKey.safMassDeletionVerify(), retry.retryKey)
        assertEquals(1, retry.attemptCount)
        assertEquals(31_000L, retry.nextRetryAtMs)
        assertEquals(30_000L, plan.nextRetryDelayMs)
    }

    @Test
    fun sameQuarantineBeforeDuePreservesExistingDeadlineWithoutRewrite() {
        val first = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 1_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = null,
        ).retryUpserts.single()

        val plan = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 10_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = first,
        )

        assertTrue(plan.retryUpserts.isEmpty())
        assertTrue(plan.retryDeleteKeys.isEmpty())
        assertEquals(21_000L, plan.nextRetryDelayMs)
    }

    @Test
    fun failedDueConfirmationBacksOffExponentially() {
        val first = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 1_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = null,
        ).retryUpserts.single()

        val plan = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = first.nextRetryAtMs,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = first,
            verification = SafIndependentMissingVerificationResult(
                verifiedMissingStableObjectKeys = emptySet(),
                presentStableObjectKeys = emptySet(),
                indeterminateStableObjectKeys = setOf("doc-1"),
                nextCursor = 1,
                hasMore = true,
            ),
        )

        val retry = plan.retryUpserts.single()
        assertEquals(2, retry.attemptCount)
        assertEquals(60_000L, plan.nextRetryDelayMs)
        assertEquals(first.nextRetryAtMs + 60_000L, retry.nextRetryAtMs)
    }

    @Test
    fun partialDiscoveryPreservesExistingConfirmationDebtExactly() {
        val existing = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 1_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = null,
        ).retryUpserts.single().copy(
            attemptCount = 4,
            nextRetryAtMs = 500_000L,
            continuationCursor = 64,
        )

        val plan = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 100_000L,
            discoveryComplete = false,
            quarantineReason = null,
            removedStableObjectKeys = emptySet(),
            existing = existing,
        )

        assertTrue(plan.retryUpserts.isEmpty())
        assertTrue(plan.retryDeleteKeys.isEmpty())
        assertEquals(null, plan.nextRetryDelayMs)
        assertTrue(!plan.requestBudgetContinuation)
    }

    @Test
    fun partialDiscoveryRevokesProofForExplicitlyPresentObjectsAndResetsCursor() {
        val provedMissing = removed.toList().sorted().take(64).toSet()
        val returned = provedMissing.take(2).toSet()
        val existing = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 1_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = null,
        ).retryUpserts.single().copy(
            attemptCount = 4,
            nextRetryAtMs = 500_000L,
            continuationCursor = 64,
            confirmedMissingKeysPayload =
                SafMassDeletionConfirmationPlanner.encodeConfirmedMissingKeys(provedMissing),
        )

        val plan = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 100_000L,
            discoveryComplete = false,
            quarantineReason = null,
            removedStableObjectKeys = emptySet(),
            existing = existing,
            observedPresentStableObjectKeys = returned,
        )

        val reconciled = plan.retryUpserts.single()
        assertEquals(existing.attemptCount, reconciled.attemptCount)
        assertEquals(existing.nextRetryAtMs, reconciled.nextRetryAtMs)
        assertEquals(0, reconciled.continuationCursor)
        assertEquals(
            provedMissing - returned,
            SafMassDeletionConfirmationPlanner.decodeConfirmedMissingKeys(
                reconciled.confirmedMissingKeysPayload,
            ),
        )
        assertTrue(plan.retryDeleteKeys.isEmpty())
    }

    @Test
    fun successfulBudgetBatchAdvancesCursorWithoutIncreasingAttempt() {
        val existing = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 1_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = null,
        ).retryUpserts.single()
        val firstBatch = removed.toList().sorted().take(64).toSet()

        val plan = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = existing.nextRetryAtMs,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = existing,
            verification = SafIndependentMissingVerificationResult(
                verifiedMissingStableObjectKeys = firstBatch,
                presentStableObjectKeys = emptySet(),
                indeterminateStableObjectKeys = emptySet(),
                providerQueryCount = 64,
                nextCursor = 64,
                hasMore = true,
                budgetExhausted = true,
            ),
        )

        val continued = plan.retryUpserts.single()
        assertEquals(existing.attemptCount, continued.attemptCount)
        assertEquals(64, continued.continuationCursor)
        assertEquals(existing.observedFingerprint, continued.observedFingerprint)
        assertEquals(existing.nextRetryAtMs, continued.nextRetryAtMs)
        assertEquals(
            SafMassDeletionConfirmationPlanner.encodeConfirmedMissingKeys(firstBatch),
            continued.confirmedMissingKeysPayload,
        )
        assertEquals(0L, plan.nextRetryDelayMs)
        assertTrue(plan.requestBudgetContinuation)
    }

    @Test
    fun finalBatchOpensOnlyWhenAccumulatedProofCoversEntireRemovalSet() {
        val sorted = removed.toList().sorted()
        val firstBatch = sorted.take(64).toSet()
        val secondBatch = sorted.drop(64).toSet()
        val existing = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 1_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = null,
        ).retryUpserts.single().copy(
            continuationCursor = 64,
            confirmedMissingKeysPayload =
                SafMassDeletionConfirmationPlanner.encodeConfirmedMissingKeys(firstBatch),
            nextRetryAtMs = 1_000L,
        )

        val accumulated = SafMassDeletionConfirmationPlanner.accumulateConfirmedMissingKeys(
            existing = existing,
            activationEpoch = 3L,
            removedStableObjectKeys = removed,
            batchVerifiedMissingKeys = secondBatch,
        )
        assertEquals(removed, accumulated)

        // Legacy / corrupted debt: cursor claims progress but confirmed payload is empty. The final
        // batch alone must not be treated as proof for the skipped prefix.
        val legacyCursorOnly = existing.copy(confirmedMissingKeysPayload = "")
        val incomplete = SafMassDeletionConfirmationPlanner.accumulateConfirmedMissingKeys(
            existing = legacyCursorOnly,
            activationEpoch = 3L,
            removedStableObjectKeys = removed,
            batchVerifiedMissingKeys = secondBatch,
        )
        assertEquals(secondBatch, incomplete)
        assertTrue(!incomplete.containsAll(removed))
    }

    @Test
    fun failedBatchClearsAccumulatedConfirmedMissingProof() {
        val firstBatch = removed.toList().sorted().take(64).toSet()
        val existing = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 1_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = null,
        ).retryUpserts.single().copy(
            continuationCursor = 64,
            confirmedMissingKeysPayload =
                SafMassDeletionConfirmationPlanner.encodeConfirmedMissingKeys(firstBatch),
        )

        val plan = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = existing.nextRetryAtMs,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = existing,
            verification = SafIndependentMissingVerificationResult(
                verifiedMissingStableObjectKeys = emptySet(),
                presentStableObjectKeys = setOf("doc-65"),
                indeterminateStableObjectKeys = emptySet(),
                nextCursor = 65,
                hasMore = true,
            ),
        )

        val retry = plan.retryUpserts.single()
        assertEquals(0, retry.continuationCursor)
        assertEquals("", retry.confirmedMissingKeysPayload)
    }

    @Test
    fun changedRemovalFingerprintResetsCursorAndAttempt() {
        val existing = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 1_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = null,
        ).retryUpserts.single().copy(
            attemptCount = 5,
            continuationCursor = 64,
        )
        val changed = removed + "doc-new"

        val plan = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 100_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = changed,
            existing = existing,
        )

        val reset = plan.retryUpserts.single()
        assertEquals(1, reset.attemptCount)
        assertEquals(0, reset.continuationCursor)
        assertEquals(130_000L, reset.nextRetryAtMs)
    }

    @Test
    fun resolvedQuarantineDeletesConfirmationDebt() {
        val existing = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 1_000L,
            quarantineReason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            removedStableObjectKeys = removed,
            existing = null,
        ).retryUpserts.single().copy(continuationCursor = 64)
        val plan = SafMassDeletionConfirmationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 100_000L,
            quarantineReason = null,
            removedStableObjectKeys = removed,
            existing = existing,
        )

        assertTrue(plan.retryUpserts.isEmpty())
        assertEquals(setOf(LibraryRetryKey.safMassDeletionVerify()), plan.retryDeleteKeys)
        assertEquals(null, plan.nextRetryDelayMs)
    }
}
