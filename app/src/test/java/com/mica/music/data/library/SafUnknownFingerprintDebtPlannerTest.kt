package com.mica.music.data.library

import com.mica.music.data.scanner.SafFastVerifyPlan
import com.mica.music.data.scanner.SafTreeMetadataEntry
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafUnknownFingerprintDebtPlannerTest {

    private val source = SourceIdentityKey.folder("content://provider/tree/music")

    @Test
    fun firstUnknownObservationCreatesPersistentDueDebt() {
        val unknown = entry("unknown")

        val plan = plan(
            unknown = unknown,
            existing = emptyList(),
            probePlan = selectedUnknownProbePlan(unknown),
        )

        val debt = plan.retryUpserts.single()
        assertEquals(LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY, debt.retryKind)
        assertEquals(LibraryRetryKey.safUnknownFingerprint("unknown"), debt.retryKey)
        assertEquals("UNKNOWN_PENDING", debt.failureKind)
        assertEquals(1_000L, debt.nextRetryAtMs)
        assertEquals(safObservedFingerprint(unknown), debt.observedFingerprint)
        assertEquals(1, plan.observedUnknownCount)
    }

    @Test
    fun s4FrozenSuccessfulUnknownVerifyIntervalIsTwentyFourHours() {
        assertEquals(
            24L * 60L * 60L * 1_000L,
            SafUnknownFingerprintDebtPlanner.UNKNOWN_VERIFY_INTERVAL_MS,
        )
        val unknown = entry("unknown")
        val existing = debt(
            entry = unknown,
            failureKind = "UNKNOWN_PENDING",
            attemptCount = 0,
            nextRetryAtMs = 0L,
        )
        val execution = SafShadowProbeExecutionResult(
            provisionalSongsByStableObjectKey = mapOf(
                unknown.stableObjectKey to SongFixtures.song(unknown.stableObjectKey),
            ),
            strongValidatedFingerprintsByStableObjectKey = mapOf(
                unknown.stableObjectKey to "sha256:verified",
            ),
            attemptedCount = 1,
        )
        val plan = SafUnknownFingerprintDebtPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            allObservedEntries = listOf(unknown),
            existingRetryItems = listOf(existing),
            probePlan = selectedUnknownProbePlan(unknown),
            execution = execution,
            validation = SafShadowPostValidationResult(
                resolvedSongsByStableObjectKey = execution.provisionalSongsByStableObjectKey,
                issues = emptyList(),
            ),
            authoritativeRemovedStableObjectKeys = emptySet(),
        )

        assertEquals(
            1_000L + SafUnknownFingerprintDebtPlanner.UNKNOWN_VERIFY_INTERVAL_MS,
            plan.retryUpserts.single().nextRetryAtMs,
        )
    }

    @Test
    fun stableStrongVerifyPersistsResultAndMovesNextDueForward() {
        val unknown = entry("unknown")
        val existing = debt(
            entry = unknown,
            failureKind = "UNKNOWN_PENDING",
            attemptCount = 0,
            nextRetryAtMs = 0L,
        )
        val probePlan = selectedUnknownProbePlan(unknown)
        val execution = SafShadowProbeExecutionResult(
            provisionalSongsByStableObjectKey = mapOf(
                unknown.stableObjectKey to SongFixtures.song(unknown.stableObjectKey),
            ),
            strongValidatedFingerprintsByStableObjectKey = mapOf(
                unknown.stableObjectKey to "sha256:verified",
            ),
            attemptedCount = 1,
        )
        val validation = SafShadowPostValidationResult(
            resolvedSongsByStableObjectKey = execution.provisionalSongsByStableObjectKey,
            issues = emptyList(),
        )

        val plan = SafUnknownFingerprintDebtPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            allObservedEntries = listOf(unknown),
            existingRetryItems = listOf(existing),
            probePlan = probePlan,
            execution = execution,
            validation = validation,
            authoritativeRemovedStableObjectKeys = emptySet(),
            verifyIntervalMs = 10_000L,
        )

        val debt = plan.retryUpserts.single()
        assertEquals("UNKNOWN_DEEP_VERIFY_OK:sha256:verified", debt.failureKind)
        assertEquals(1, debt.attemptCount)
        assertEquals(11_000L, debt.nextRetryAtMs)
        assertEquals(1, plan.strongVerifiedCount)

        val beforeDue = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(unknown),
            playback = LibraryPlaybackIoSnapshot.Idle,
            retryItems = listOf(debt),
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 10_999L,
        )
        val afterDue = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(unknown),
            playback = LibraryPlaybackIoSnapshot.Idle,
            retryItems = listOf(debt),
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 11_000L,
        )

        assertTrue(beforeDue.ready.isEmpty())
        assertEquals(listOf("unknown"), afterDue.ready.map { it.stableObjectKey })
    }

    @Test
    fun weakObservationChangeResetsFutureDebtToImmediateDue() {
        val old = entry("unknown")
        val changed = old.copy(externalLyricsSignature = "lyrics:changed")
        val existing = debt(
            entry = old,
            failureKind = "UNKNOWN_DEEP_VERIFY_OK:old",
            attemptCount = 4,
            nextRetryAtMs = 99_000L,
        )

        val plan = plan(
            unknown = changed,
            existing = listOf(existing),
            probePlan = selectedUnknownProbePlan(changed),
        )

        val reset = plan.retryUpserts.single()
        assertEquals("UNKNOWN_INPUT_CHANGED", reset.failureKind)
        assertEquals(0, reset.attemptCount)
        assertEquals(1_000L, reset.nextRetryAtMs)
        assertEquals(safObservedFingerprint(changed), reset.observedFingerprint)
    }

    @Test
    fun selectedFailurePersistsLastResultAndShortRetryDue() {
        val unknown = entry("unknown")
        val existing = debt(
            entry = unknown,
            failureKind = "UNKNOWN_PENDING",
            attemptCount = 0,
            nextRetryAtMs = 0L,
        )

        val plan = SafUnknownFingerprintDebtPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            allObservedEntries = listOf(unknown),
            existingRetryItems = listOf(existing),
            probePlan = selectedUnknownProbePlan(unknown),
            execution = SafShadowProbeExecutionResult(
                issues = listOf(
                    SafShadowProbeIssue(
                        stableObjectKey = unknown.stableObjectKey,
                        kind = SafShadowProbeIssueKind.PROBE_FAILED,
                    ),
                ),
            ),
            validation = SafShadowPostValidationResult(
                resolvedSongsByStableObjectKey = emptyMap(),
                issues = listOf(
                    SafShadowProbeIssue(
                        stableObjectKey = unknown.stableObjectKey,
                        kind = SafShadowProbeIssueKind.PROBE_FAILED,
                    ),
                ),
            ),
            authoritativeRemovedStableObjectKeys = emptySet(),
        )

        val failed = plan.retryUpserts.single()
        assertEquals("UNKNOWN_DEEP_VERIFY_PROBE_FAILED", failed.failureKind)
        assertEquals(31_000L, failed.nextRetryAtMs)
    }

    @Test
    fun tenKUnknownFirstPassPersistsOnlySelectedBudget() {
        val unknownEntries = List(10_000) { index -> entry("unknown-" + (index + 1)) }
        val verifyPlan = SafFastVerifyPlan(
            added = emptyList(),
            changed = emptyList(),
            unknownFingerprint = unknownEntries,
            removedStableObjectKeys = emptySet(),
            unchangedCount = 0,
            removalSuppressedCount = 0,
            discoveryReport = com.mica.music.data.scanner.DiscoveryReport(),
        )
        val probePlan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan,
            playback = LibraryPlaybackIoSnapshot.Idle,
            observedEntries = unknownEntries,
            retryItems = emptyList(),
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
        )

        val plan = SafUnknownFingerprintDebtPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            allObservedEntries = unknownEntries,
            existingRetryItems = emptyList(),
            probePlan = probePlan,
            execution = SafShadowProbeExecutionResult(),
            validation = SafShadowPostValidationResult(
                resolvedSongsByStableObjectKey = emptyMap(),
                issues = emptyList(),
            ),
            authoritativeRemovedStableObjectKeys = emptySet(),
        )

        assertEquals(10_000, plan.observedUnknownCount)
        assertEquals(SafAutoProbePlanner.UNKNOWN_VERIFY_OBJECT_BUDGET, plan.selectedDeepVerifyCount)
        assertEquals(SafAutoProbePlanner.UNKNOWN_VERIFY_OBJECT_BUDGET, plan.retryUpserts.size)
        assertEquals(
            probePlan.objects.map { it.stableObjectKey }.toSet(),
            plan.retryUpserts.map { it.stableObjectKey }.toSet(),
        )
        assertTrue(plan.retryDeleteKeys.isEmpty())
    }

    @Test
    fun tenKStaleUnknownCleanupIsBoundedPerPass() {
        val unknownEntries = List(10_000) { index -> entry("cleanup-" + (index + 1)) }
        val reliableEntries = unknownEntries.map { it.copy(lastModifiedMs = 5_678L) }
        val existing = unknownEntries.map {
            debt(
                entry = it,
                failureKind = "UNKNOWN_DEEP_VERIFY_OK:old",
                attemptCount = 1,
                nextRetryAtMs = 99_000L,
            )
        }

        val plan = SafUnknownFingerprintDebtPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            allObservedEntries = reliableEntries,
            existingRetryItems = existing,
            probePlan = emptyProbePlan(),
            execution = SafShadowProbeExecutionResult(),
            validation = SafShadowPostValidationResult(
                resolvedSongsByStableObjectKey = emptyMap(),
                issues = emptyList(),
            ),
            authoritativeRemovedStableObjectKeys = emptySet(),
        )

        assertTrue(plan.retryUpserts.isEmpty())
        assertEquals(
            SafUnknownFingerprintDebtPlanner.UNKNOWN_DEBT_CLEANUP_BUDGET,
            plan.retryDeleteKeys.size,
        )
        assertEquals(0, plan.observedUnknownCount)
    }

    @Test
    fun reliableObservationAndAuthoritativeRemovalClearUnknownDebt() {
        val unknownA = entry("a")
        val unknownB = entry("b")
        val debts = listOf(
            debt(unknownA, "UNKNOWN_PENDING", 0, 0L),
            debt(unknownB, "UNKNOWN_PENDING", 0, 0L),
        )
        val reliableA = unknownA.copy(lastModifiedMs = 5_678L)

        val plan = SafUnknownFingerprintDebtPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            allObservedEntries = listOf(reliableA),
            existingRetryItems = debts,
            probePlan = emptyProbePlan(),
            execution = SafShadowProbeExecutionResult(),
            validation = SafShadowPostValidationResult(resolvedSongsByStableObjectKey = emptyMap(), issues = emptyList()),
            authoritativeRemovedStableObjectKeys = setOf("b"),
        )

        assertTrue(plan.retryUpserts.isEmpty())
        assertEquals(
            setOf(
                LibraryRetryKey.safUnknownFingerprint("a"),
                LibraryRetryKey.safUnknownFingerprint("b"),
            ),
            plan.retryDeleteKeys,
        )
    }

    private fun plan(
        unknown: SafTreeMetadataEntry,
        existing: List<LibraryRetryItem>,
        probePlan: SafAutoProbePlan = emptyProbePlan(),
    ) = SafUnknownFingerprintDebtPlanner.plan(
        sourceIdentity = source,
        activationEpoch = 7L,
        nowMs = 1_000L,
        allObservedEntries = listOf(unknown),
        existingRetryItems = existing,
        probePlan = probePlan,
        execution = SafShadowProbeExecutionResult(),
        validation = SafShadowPostValidationResult(resolvedSongsByStableObjectKey = emptyMap(), issues = emptyList()),
        authoritativeRemovedStableObjectKeys = emptySet(),
    )

    private fun debt(
        entry: SafTreeMetadataEntry,
        failureKind: String,
        attemptCount: Int,
        nextRetryAtMs: Long,
    ) = LibraryRetryItem(
        sourceIdentity = source,
        retryKey = LibraryRetryKey.safUnknownFingerprint(entry.stableObjectKey),
        activationEpoch = 7L,
        stableObjectKey = entry.stableObjectKey,
        observedFingerprint = safObservedFingerprint(entry),
        retryKind = LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY,
        failureKind = failureKind,
        attemptCount = attemptCount,
        nextRetryAtMs = nextRetryAtMs,
    )

    private fun selectedUnknownProbePlan(entry: SafTreeMetadataEntry) = SafAutoProbePlan(
        objects = listOf(
            SafAutoProbeObjectPlan(
                entry = entry,
                reasons = setOf(SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY),
                disposition = SafAutoProbeDisposition.READY,
            ),
        ),
        heavyProbeParallelism = 1,
        unknownFingerprintCandidateCount = 1,
        unknownFingerprintDueCount = 1,
        unknownFingerprintSelectedCount = 1,
    )

    private fun emptyProbePlan() = SafAutoProbePlan(
        objects = emptyList(),
        heavyProbeParallelism = 1,
    )

    private fun verifyPlan(entry: SafTreeMetadataEntry) = SafFastVerifyPlan(
        added = emptyList(),
        changed = emptyList(),
        unknownFingerprint = listOf(entry),
        removedStableObjectKeys = emptySet(),
        unchangedCount = 0,
        removalSuppressedCount = 0,
        discoveryReport = com.mica.music.data.scanner.DiscoveryReport(),
    )

    private fun entry(key: String) = SafTreeMetadataEntry(
        stableObjectKey = key,
        mediaUri = "content://provider/document/$key",
        fileName = "$key.flac",
        folderPath = "Album",
        filePath = "Album/$key.flac",
        mimeType = "audio/flac",
        sizeBytes = 1_234L,
        lastModifiedMs = 0L,
        externalLyricsSignature = "lyrics:v1",
    )
}
