package com.mica.music.data.library

import com.mica.music.data.scanner.DiscoveryCompleteness
import com.mica.music.data.scanner.DiscoveryPartitionStatus
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.DiscoveryReport
import com.mica.music.data.scanner.SafFastVerifyPlan
import com.mica.music.data.scanner.SafTreeMetadataEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafAutoProbePlannerTest {

    @Test
    fun onlyAddedChangedAndUnknownBecomeHeavyProbeWork() {
        val added = entry("added")
        val changed = entry("changed")
        val unknown = entry("unknown")

        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(
                added = listOf(added),
                changed = listOf(changed),
                unknown = listOf(unknown),
                unchangedCount = 9_997,
            ),
            playback = LibraryPlaybackIoSnapshot.Idle,
        )

        assertEquals(listOf("added", "changed", "unknown"), plan.ready.map { it.stableObjectKey })
        assertEquals(1, plan.heavyProbeParallelism)
        assertTrue(plan.deferred.isEmpty())
        assertEquals(
            setOf(SafAutoProbeReason.NEW_OBJECT),
            plan.objects.first { it.stableObjectKey == "added" }.reasons,
        )
        assertEquals(
            setOf(SafAutoProbeReason.METADATA_OR_FINGERPRINT_CHANGED),
            plan.objects.first { it.stableObjectKey == "changed" }.reasons,
        )
        assertEquals(
            setOf(SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY),
            plan.objects.first { it.stableObjectKey == "unknown" }.reasons,
        )
    }

    @Test
    fun currentPlaybackObjectIsDeferredButOtherChangedObjectRemainsReady() {
        val current = entry("current", mediaUri = "content://tree/current")
        val other = entry("other", mediaUri = "content://tree/other")

        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(changed = listOf(current, other)),
            playback = LibraryPlaybackIoSnapshot(
                currentStableObjectKey = current.stableObjectKey,
                currentMediaUri = current.mediaUri,
                hasActivePlaybackInstance = true,
            ),
        )

        assertEquals(listOf("other"), plan.ready.map { it.stableObjectKey })
        assertEquals(listOf("current"), plan.deferred.map { it.stableObjectKey })
        assertEquals(1, plan.heavyProbeParallelism)
    }

    @Test
    fun serializedProviderDefersAllHeavyProbeWhilePlaybackInstanceExists() {
        val first = entry("a")
        val second = entry("b")

        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(changed = listOf(first, second)),
            playback = LibraryPlaybackIoSnapshot(
                currentStableObjectKey = "unrelated",
                currentMediaUri = "content://tree/unrelated",
                hasActivePlaybackInstance = true,
                sourceSerializesHeavyIo = true,
            ),
        )

        assertTrue(plan.ready.isEmpty())
        assertEquals(listOf("a", "b"), plan.deferred.map { it.stableObjectKey })
    }

    @Test
    fun systemExternalStorageProviderOnlyDefersCurrentPlaybackObject() {
        val currentUri =
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fcurrent.flac"
        val scoped = LibraryPlaybackIoSnapshot(
            currentStableObjectKey = "current",
            currentMediaUri = currentUri,
            hasActivePlaybackInstance = true,
        ).withSafProviderSerialization("com.android.externalstorage.documents")

        assertTrue(!scoped.sourceSerializesHeavyIo)

        val current = entry("current", mediaUri = currentUri)
        val added = entry(
            "added",
            mediaUri =
                "content://com.android.externalstorage.documents/document/" +
                    "primary%3AMusic%2Fadded.flac",
        )
        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(changed = listOf(current, added)),
            playback = scoped,
        )

        assertEquals(listOf("added"), plan.ready.map { it.stableObjectKey })
        assertEquals(listOf("current"), plan.deferred.map { it.stableObjectKey })
    }

    @Test
    fun sameThirdPartySafAuthorityStillSerializesAllHeavyProbe() {
        val scoped = LibraryPlaybackIoSnapshot(
            currentStableObjectKey = "current",
            currentMediaUri = "content://provider.documents/document/audio",
            hasActivePlaybackInstance = true,
        ).withSafProviderSerialization("provider.documents")

        assertTrue(scoped.sourceSerializesHeavyIo)

        val first = entry("a", mediaUri = "content://provider.documents/document/a")
        val second = entry("b", mediaUri = "content://provider.documents/document/b")
        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(changed = listOf(first, second)),
            playback = scoped,
        )

        assertTrue(plan.ready.isEmpty())
        assertEquals(listOf("a", "b"), plan.deferred.map { it.stableObjectKey })
    }

    @Test
    fun differentSafAuthorityDoesNotGloballySerializeUnrelatedPlayback() {
        val scoped = LibraryPlaybackIoSnapshot(
            currentStableObjectKey = "remote-current",
            currentMediaUri = "content://other.provider/document/audio",
            hasActivePlaybackInstance = true,
        ).withSafProviderSerialization("provider.documents")

        assertTrue(!scoped.sourceSerializesHeavyIo)

        val changed = entry("changed", mediaUri = "content://provider.documents/document/changed")
        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(changed = listOf(changed)),
            playback = scoped,
        )

        assertEquals(listOf("changed"), plan.ready.map { it.stableObjectKey })
        assertTrue(plan.deferred.isEmpty())
    }

    @Test
    fun unknownFingerprintBudgetSelectsPersistedDueDebtInDueOrder() {
        val source = SourceIdentityKey.folder("content://provider/tree/music")
        val unknown = listOf("a", "b", "c", "d", "e").map(::entry)
        val debts = unknown.mapIndexed { index, entry ->
            LibraryRetryItem(
                sourceIdentity = source,
                retryKey = "unknown:" + entry.stableObjectKey,
                activationEpoch = 7L,
                stableObjectKey = entry.stableObjectKey,
                observedFingerprint = safObservedFingerprint(entry),
                retryKind = LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY,
                failureKind = "UNKNOWN_PENDING",
                attemptCount = 0,
                nextRetryAtMs = index * 10L,
            )
        }

        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(unknown = unknown),
            playback = LibraryPlaybackIoSnapshot.Idle,
            retryItems = debts,
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 25L,
            unknownVerifyBudget = 2,
        )

        assertEquals(listOf("a", "b"), plan.ready.map { it.stableObjectKey })
        assertEquals(5, plan.unknownFingerprintCandidateCount)
        assertEquals(3, plan.unknownFingerprintDueCount)
        assertEquals(2, plan.unknownFingerprintSelectedCount)
        assertEquals(1, plan.unknownFingerprintDeferredByBudgetCount)
        assertEquals(2, plan.unknownFingerprintNotDueCount)
    }

    @Test
    fun defaultUnknownVerifyBudgetCapsTenThousandDueObjectsAtThirtyTwo() {
        val unknown = List(10_000) { index ->
            entry("unknown-" + index.toString().padStart(5, '0')).copy(
                lastModifiedMs = 0L,
                sizeBytes = 0L,
            )
        }

        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(unknown = unknown),
            playback = LibraryPlaybackIoSnapshot.Idle,
        )

        assertEquals(32, SafAutoProbePlanner.UNKNOWN_VERIFY_OBJECT_BUDGET)
        assertEquals(10_000, plan.unknownFingerprintCandidateCount)
        assertEquals(10_000, plan.unknownFingerprintDueCount)
        assertEquals(32, plan.unknownFingerprintSelectedCount)
        assertEquals(32, plan.ready.size)
        assertEquals(9_968, plan.unknownFingerprintDeferredByBudgetCount)
    }

    @Test
    fun changedWeakObservationMakesFutureUnknownDebtImmediatelyDue() {
        val source = SourceIdentityKey.folder("content://provider/tree/music")
        val changed = entry("unknown").copy(lastModifiedMs = 0L)
        val oldObservation = changed.copy(externalLyricsSignature = "old")
        val debt = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "unknown:unknown",
            activationEpoch = 7L,
            stableObjectKey = changed.stableObjectKey,
            observedFingerprint = safObservedFingerprint(oldObservation),
            retryKind = LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY,
            failureKind = "UNKNOWN_DEEP_VERIFY_OK",
            attemptCount = 1,
            nextRetryAtMs = 100_000L,
        )

        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(unknown = listOf(changed)),
            playback = LibraryPlaybackIoSnapshot.Idle,
            retryItems = listOf(debt),
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
        )

        assertEquals(listOf("unknown"), plan.ready.map { it.stableObjectKey })
        assertEquals(1, plan.unknownFingerprintDueCount)
    }

    @Test
    fun futureObjectProbeRetryBlocksUnknownDebtUntilItsBackoffExpires() {
        val source = SourceIdentityKey.folder("content://provider/tree/music")
        val unknown = entry("unknown")
        val debt = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "unknown:unknown",
            activationEpoch = 7L,
            stableObjectKey = unknown.stableObjectKey,
            observedFingerprint = safObservedFingerprint(unknown),
            retryKind = LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY,
            failureKind = "UNKNOWN_PENDING",
            attemptCount = 0,
            nextRetryAtMs = 0L,
        )
        val objectRetry = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "probe:unknown",
            activationEpoch = 7L,
            stableObjectKey = unknown.stableObjectKey,
            observedFingerprint = safObservedFingerprint(unknown),
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = "PROBE_FAILED",
            attemptCount = 1,
            nextRetryAtMs = 5_000L,
        )

        val blocked = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(unknown = listOf(unknown)),
            playback = LibraryPlaybackIoSnapshot.Idle,
            retryItems = listOf(debt, objectRetry),
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
        )
        val due = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(unknown = listOf(unknown)),
            playback = LibraryPlaybackIoSnapshot.Idle,
            retryItems = listOf(debt, objectRetry),
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 5_000L,
        )

        assertTrue(blocked.ready.isEmpty())
        assertEquals(0, blocked.unknownFingerprintDueCount)
        assertEquals(listOf("unknown"), due.ready.map { it.stableObjectKey })
    }

    @Test
    fun nonPeriodicPassSkipsPureUnknownFingerprintWork() {
        val unknown = entry("unknown")

        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(unknown = listOf(unknown)),
            playback = LibraryPlaybackIoSnapshot.Idle,
            allowUnknownFingerprintVerify = false,
        )

        assertTrue(plan.isNoOp)
        assertEquals(1, plan.unknownFingerprintCandidateCount)
        assertEquals(0, plan.unknownFingerprintSelectedCount)
    }

    @Test
    fun dueRetryLedgerItemForObservedObjectForcesProbeEvenWithoutVisibleDelta() {
        val source = SourceIdentityKey.folder("content://provider/tree/music")
        val observed = entry("retry")
        val retry = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "probe:retry",
            activationEpoch = 7L,
            stableObjectKey = observed.stableObjectKey,
            observedFingerprint = "fingerprint:v1",
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = "PROBE_FAILED",
            attemptCount = 1,
            nextRetryAtMs = 100L,
        )

        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(),
            playback = LibraryPlaybackIoSnapshot.Idle,
            observedEntries = listOf(observed),
            retryItems = listOf(retry),
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 100L,
            allowUnknownFingerprintVerify = false,
        )

        assertEquals(listOf("retry"), plan.ready.map { it.stableObjectKey })
        assertEquals(
            setOf(SafAutoProbeReason.RETRY_LEDGER),
            plan.ready.single().reasons,
        )
        assertEquals(1, plan.dueRetryCount)
        assertEquals(0, plan.retryMissingObservationCount)
    }

    @Test
    fun tenThousandChangedObjectsAreBoundedToOneHeavyProbeBatch() {
        val changed = List(10_000) { index ->
            entry("doc-" + index.toString().padStart(5, '0'))
        }

        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(changed = changed),
            playback = LibraryPlaybackIoSnapshot.Idle,
        )

        assertEquals(SafAutoProbePlanner.DEFAULT_HEAVY_PROBE_BUDGET, plan.ready.size)
        assertEquals(10_000 - SafAutoProbePlanner.DEFAULT_HEAVY_PROBE_BUDGET, plan.budgetDeferred.size)
        assertTrue(plan.deferred.isEmpty())
        assertEquals("doc-00000", plan.ready.first().stableObjectKey)
        assertEquals("doc-00031", plan.ready.last().stableObjectKey)
        assertEquals("doc-00032", plan.budgetDeferred.first().stableObjectKey)
        assertEquals(1, plan.heavyProbeParallelism)
    }

    @Test
    fun playbackDeferredObjectDoesNotConsumeHeavyProbeBudget() {
        val current = entry("a-current", mediaUri = "content://tree/current")
        val other = listOf(entry("b"), entry("c"), entry("d"))

        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(changed = listOf(current) + other),
            playback = LibraryPlaybackIoSnapshot(
                currentStableObjectKey = current.stableObjectKey,
                currentMediaUri = current.mediaUri,
                hasActivePlaybackInstance = true,
            ),
            heavyProbeBudget = 2,
        )

        assertEquals(listOf("a-current"), plan.deferred.map { it.stableObjectKey })
        assertEquals(listOf("b", "c"), plan.ready.map { it.stableObjectKey })
        assertEquals(listOf("d"), plan.budgetDeferred.map { it.stableObjectKey })
    }

    @Test
    fun alreadyResolvedRevisionAdvancesNextHeavyProbeBatch() {
        val changed = List(100) { index ->
            entry("doc-" + index.toString().padStart(3, '0'))
        }
        val first = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(changed = changed),
            playback = LibraryPlaybackIoSnapshot.Idle,
            heavyProbeBudget = 32,
        )

        val second = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(changed = changed),
            playback = LibraryPlaybackIoSnapshot.Idle,
            heavyProbeBudget = 32,
            alreadyResolvedStableObjectKeys =
                first.ready.mapTo(linkedSetOf(), SafAutoProbeObjectPlan::stableObjectKey),
        )

        assertEquals("doc-000", first.ready.first().stableObjectKey)
        assertEquals("doc-031", first.ready.last().stableObjectKey)
        assertEquals("doc-032", second.ready.first().stableObjectKey)
        assertEquals("doc-063", second.ready.last().stableObjectKey)
        assertEquals(36, second.budgetDeferred.size)
    }

    @Test
    fun noVisibleMetadataWorkProducesNoProbeWorkEvenWithLargeCatalog() {
        val plan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan(unchangedCount = 10_000),
            playback = LibraryPlaybackIoSnapshot(
                hasActivePlaybackInstance = true,
                sourceSerializesHeavyIo = true,
            ),
        )

        assertTrue(plan.isNoOp)
        assertTrue(plan.ready.isEmpty())
        assertTrue(plan.deferred.isEmpty())
    }

    private fun entry(
        key: String,
        mediaUri: String = "content://tree/$key",
    ) = SafTreeMetadataEntry(
        stableObjectKey = key,
        mediaUri = mediaUri,
        fileName = "$key.flac",
        folderPath = "Album",
        filePath = "Album/$key.flac",
        mimeType = "audio/flac",
        sizeBytes = 1_000L,
        lastModifiedMs = 2_000L,
        externalLyricsSignature = "",
    )

    private fun verifyPlan(
        added: List<SafTreeMetadataEntry> = emptyList(),
        changed: List<SafTreeMetadataEntry> = emptyList(),
        unknown: List<SafTreeMetadataEntry> = emptyList(),
        unchangedCount: Int = 0,
    ) = SafFastVerifyPlan(
        added = added,
        changed = changed,
        unknownFingerprint = unknown,
        removedStableObjectKeys = emptySet(),
        unchangedCount = unchangedCount,
        removalSuppressedCount = 0,
        discoveryReport = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.SAF_TREE,
                completeness = DiscoveryCompleteness.COMPLETE,
            ),
        ),
    )
}
