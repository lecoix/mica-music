package com.mica.music.data.library

import com.mica.music.data.scanner.SafTreeMetadataEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafRetryPlanningLoaderTest {

    private val source = SourceIdentityKey.folder("content://provider/tree/music")

    @Test
    fun tenKUnknownUsesBoundedLookupBatchesAndSelectsOnlyVerifyBudget() = runTest {
        val entries = List(10_000) { index ->
            entry("unknown-${index.toString().padStart(5, '0')}")
        }.asReversed()
        var maxLookupKeys = 0
        var lookupCalls = 0
        var dueLimit = 0

        val working = SafRetryPlanningLoader.load(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            unknownCandidates = entries,
            cleanupCandidateStableObjectKeys = emptySet(),
            allowUnknownFingerprintVerify = true,
            loadDueObjectRetries = { limit ->
                dueLimit = limit
                emptyList()
            },
            loadByStableObjectKeys = { keys ->
                maxLookupKeys = maxOf(maxLookupKeys, keys.size)
                lookupCalls += 1
                emptyList()
            },
        )

        assertEquals(LibraryRetryPaging.DUE_WORK_BUDGET, dueLimit)
        assertTrue(maxLookupKeys <= LibraryRetryPaging.STABLE_KEY_LOOKUP_BATCH_SIZE)
        assertEquals(10_000, working.unknownFingerprintDueCount)
        assertEquals(
            SafAutoProbePlanner.UNKNOWN_VERIFY_OBJECT_BUDGET,
            working.preselectedUnknownFingerprintVerify.size,
        )
        assertEquals(
            entries.asSequence()
                .sortedBy(SafTreeMetadataEntry::stableObjectKey)
                .take(SafAutoProbePlanner.UNKNOWN_VERIFY_OBJECT_BUDGET)
                .map(SafTreeMetadataEntry::stableObjectKey)
                .toList(),
            working.preselectedUnknownFingerprintVerify.map(SafTreeMetadataEntry::stableObjectKey),
        )
        assertEquals(79, lookupCalls)
        assertEquals(79, working.lookupBatchCount)
        assertEquals(0, working.retryRowsRead)
        assertTrue(working.retryItems.isEmpty())
    }

    @Test
    fun tenKUnknownDebtReadsBoundedRowsAndRetainsOnlyVerifyBudgetHistory() = runTest {
        val entries = List(10_000) { index ->
            entry("unknown-${index.toString().padStart(5, '0')}")
        }
        val ledgerByStableKey = entries.associate { candidate ->
            candidate.stableObjectKey to listOf(unknownDebt(candidate, nextRetryAtMs = 0L))
        }
        var lookupCalls = 0
        var maxLookupKeys = 0
        var maxReturnedRows = 0

        val working = SafRetryPlanningLoader.load(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            unknownCandidates = entries,
            cleanupCandidateStableObjectKeys = emptySet(),
            allowUnknownFingerprintVerify = true,
            loadDueObjectRetries = { emptyList() },
            loadByStableObjectKeys = { keys ->
                lookupCalls += 1
                maxLookupKeys = maxOf(maxLookupKeys, keys.size)
                val rows = keys.flatMap { ledgerByStableKey[it].orEmpty() }
                maxReturnedRows = maxOf(maxReturnedRows, rows.size)
                rows
            },
        )

        assertEquals(10_000, working.unknownFingerprintDueCount)
        assertEquals(SafAutoProbePlanner.UNKNOWN_VERIFY_OBJECT_BUDGET, working.retryItems.size)
        assertEquals(
            entries.take(SafAutoProbePlanner.UNKNOWN_VERIFY_OBJECT_BUDGET)
                .map(SafTreeMetadataEntry::stableObjectKey),
            working.retryItems.map(LibraryRetryItem::stableObjectKey),
        )
        assertEquals(79, lookupCalls)
        assertEquals(79, working.lookupBatchCount)
        assertEquals(10_000, working.retryRowsRead)
        assertTrue(maxLookupKeys <= LibraryRetryPaging.STABLE_KEY_LOOKUP_BATCH_SIZE)
        assertTrue(maxReturnedRows <= LibraryRetryPaging.STABLE_KEY_LOOKUP_BATCH_SIZE)
    }

    @Test
    fun futureDebtBlocksButWeakObservationChangeBecomesImmediatelyDue() = runTest {
        val unchangedFuture = entry("a")
        val changed = entry("b").copy(externalLyricsSignature = "lyrics:new")
        val changedOld = changed.copy(externalLyricsSignature = "lyrics:old")
        val newUnknown = entry("c")
        val blockedByObjectRetry = entry("d")
        val ledger = listOf(
            unknownDebt(unchangedFuture, nextRetryAtMs = 9_000L),
            unknownDebt(changedOld, nextRetryAtMs = 9_000L),
            unknownDebt(blockedByObjectRetry, nextRetryAtMs = 0L),
            LibraryRetryItem(
                sourceIdentity = source,
                retryKey = SafShadowRetryPlanner.retryKey(blockedByObjectRetry.stableObjectKey),
                activationEpoch = 7L,
                stableObjectKey = blockedByObjectRetry.stableObjectKey,
                observedFingerprint = safObservedFingerprint(blockedByObjectRetry),
                retryKind = LibraryRetryKind.OBJECT_PROBE,
                failureKind = "PROBE_FAILED",
                attemptCount = 1,
                nextRetryAtMs = 8_000L,
            ),
        )

        val working = loadFromLedger(
            ledger = ledger,
            unknown = listOf(blockedByObjectRetry, changed, unchangedFuture, newUnknown),
            nowMs = 1_000L,
        )

        assertEquals(2, working.unknownFingerprintDueCount)
        assertEquals(
            listOf("b", "c"),
            working.preselectedUnknownFingerprintVerify.map(SafTreeMetadataEntry::stableObjectKey),
        )
        assertTrue(
            working.retryItems.any {
                it.retryKey == SafUnknownFingerprintDebtPlanner.retryKey("b")
            },
        )
        assertTrue(
            working.retryItems.none {
                it.retryKey == SafUnknownFingerprintDebtPlanner.retryKey("a")
            },
        )
        assertTrue(
            working.retryItems.none {
                it.retryKey == SafShadowRetryPlanner.retryKey("d")
            },
        )
    }

    @Test
    fun unknownCleanupStopsReadingAfterBudgetIsFilled() = runTest {
        val cleanupKeys = List(1_000) { index -> "cleanup-${index.toString().padStart(4, '0')}" }
        val ledger = cleanupKeys.map { key ->
            unknownDebt(entry(key), nextRetryAtMs = 9_000L)
        }
        var lookupCalls = 0
        var maxLookupKeys = 0
        val byStableKey = ledger.groupBy(LibraryRetryItem::stableObjectKey)

        val working = SafRetryPlanningLoader.load(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            unknownCandidates = emptyList(),
            cleanupCandidateStableObjectKeys = cleanupKeys,
            allowUnknownFingerprintVerify = true,
            loadDueObjectRetries = { emptyList() },
            loadByStableObjectKeys = { keys ->
                lookupCalls += 1
                maxLookupKeys = maxOf(maxLookupKeys, keys.size)
                keys.flatMap { byStableKey[it].orEmpty() }
            },
        )

        assertEquals(1, lookupCalls)
        assertTrue(maxLookupKeys <= LibraryRetryPaging.STABLE_KEY_LOOKUP_BATCH_SIZE)
        assertEquals(
            SafUnknownFingerprintDebtPlanner.UNKNOWN_DEBT_CLEANUP_BUDGET,
            working.retryItems.size,
        )
        assertEquals(LibraryRetryPaging.STABLE_KEY_LOOKUP_BATCH_SIZE, working.retryRowsRead)
    }

    private suspend fun loadFromLedger(
        ledger: List<LibraryRetryItem>,
        unknown: List<SafTreeMetadataEntry>,
        nowMs: Long,
    ): SafRetryPlanningWorkingSet {
        val byStableKey = ledger.groupBy(LibraryRetryItem::stableObjectKey)
        return SafRetryPlanningLoader.load(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = nowMs,
            unknownCandidates = unknown,
            cleanupCandidateStableObjectKeys = emptySet(),
            allowUnknownFingerprintVerify = true,
            loadDueObjectRetries = { limit ->
                ledger.asSequence()
                    .filter { it.retryKind == LibraryRetryKind.OBJECT_PROBE }
                    .filter { it.nextRetryAtMs <= nowMs }
                    .take(limit)
                    .toList()
            },
            loadByStableObjectKeys = { keys ->
                assertTrue(keys.size <= LibraryRetryPaging.STABLE_KEY_LOOKUP_BATCH_SIZE)
                keys.flatMap { byStableKey[it].orEmpty() }
            },
        )
    }

    private fun unknownDebt(
        entry: SafTreeMetadataEntry,
        nextRetryAtMs: Long,
    ) = LibraryRetryItem(
        sourceIdentity = source,
        retryKey = SafUnknownFingerprintDebtPlanner.retryKey(entry.stableObjectKey),
        activationEpoch = 7L,
        stableObjectKey = entry.stableObjectKey,
        observedFingerprint = safObservedFingerprint(entry),
        retryKind = LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY,
        failureKind = "UNKNOWN_DEEP_VERIFY_OK:sha256:test",
        attemptCount = 1,
        nextRetryAtMs = nextRetryAtMs,
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
