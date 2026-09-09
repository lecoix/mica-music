package com.mica.music.data.library

import com.mica.music.data.scanner.SafTreeMetadataEntry

internal data class SafRetryPlanningWorkingSet(
    val retryItems: List<LibraryRetryItem>,
    val preselectedUnknownFingerprintVerify: List<SafTreeMetadataEntry>,
    val unknownFingerprintDueCount: Int,
    val lookupBatchCount: Int,
    val retryRowsRead: Int,
)

/**
 * Builds the retry state needed by one SAF AUTO pass without materializing the source's complete
 * RetryLedger. UNKNOWN candidates are inspected in stable-key batches and only the best bounded
 * verification budget is retained as full work.
 */
internal object SafRetryPlanningLoader {

    suspend fun load(
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
        nowMs: Long,
        unknownCandidates: List<SafTreeMetadataEntry>,
        cleanupCandidateStableObjectKeys: Collection<String>,
        allowUnknownFingerprintVerify: Boolean,
        loadDueObjectRetries: suspend (Int) -> List<LibraryRetryItem>,
        loadByStableObjectKeys: suspend (Collection<String>) -> List<LibraryRetryItem>,
        unknownVerifyBudget: Int = SafAutoProbePlanner.UNKNOWN_VERIFY_OBJECT_BUDGET,
        unknownCleanupBudget: Int = SafUnknownFingerprintDebtPlanner.UNKNOWN_DEBT_CLEANUP_BUDGET,
    ): SafRetryPlanningWorkingSet {
        require(unknownVerifyBudget >= 0)
        require(unknownCleanupBudget >= 0)

        var lookupBatchCount = 0
        var retryRowsRead = 0
        val dueObjectRetries = loadDueObjectRetries(LibraryRetryPaging.DUE_WORK_BUDGET)
            .also { retryRowsRead += it.size }
            .filter { retry ->
                retry.sourceIdentity == sourceIdentity &&
                    retry.retryKind == LibraryRetryKind.OBJECT_PROBE &&
                    (retry.activationEpoch == null || retry.activationEpoch == activationEpoch) &&
                    retry.nextRetryAtMs <= nowMs
            }

        data class DueCandidate(
            val entry: SafTreeMetadataEntry,
            val dueAtMs: Long,
            val retryItems: List<LibraryRetryItem>,
        )

        val selectedDue = mutableListOf<DueCandidate>()
        var unknownDueCount = 0
        val dueComparator = compareBy<DueCandidate>(
            DueCandidate::dueAtMs,
            { it.entry.stableObjectKey },
        )

        // Selection is globally ordered by DueCandidate comparator below, so pre-sorting all
        // UNKNOWN entries only creates an unnecessary 10k-sized list before bounded DB batches.
        unknownCandidates
            .asSequence()
            .distinctBy(SafTreeMetadataEntry::stableObjectKey)
            .chunked(LibraryRetryPaging.STABLE_KEY_LOOKUP_BATCH_SIZE)
            .forEach { entries ->
                val rows = loadByStableObjectKeys(
                    entries.map(SafTreeMetadataEntry::stableObjectKey),
                ).filter { retry ->
                    retry.sourceIdentity == sourceIdentity &&
                        (retry.activationEpoch == null || retry.activationEpoch == activationEpoch)
                }
                lookupBatchCount += 1
                retryRowsRead += rows.size
                val rowsByStableKey = rows.groupBy(LibraryRetryItem::stableObjectKey)

                entries.forEach { entry ->
                    val scoped = rowsByStableKey[entry.stableObjectKey].orEmpty()
                    val unknownDebt = scoped.asSequence()
                        .filter { it.retryKind == LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY }
                        .maxByOrNull(LibraryRetryItem::nextRetryAtMs)
                    val objectRetry = scoped.asSequence()
                        .filter { it.retryKind == LibraryRetryKind.OBJECT_PROBE }
                        .maxByOrNull(LibraryRetryItem::nextRetryAtMs)
                    val currentObservedFingerprint = safObservedFingerprint(entry)
                    val debtDueAtMs = when {
                        unknownDebt == null -> Long.MIN_VALUE
                        unknownDebt.observedFingerprint != currentObservedFingerprint -> Long.MIN_VALUE
                        else -> unknownDebt.nextRetryAtMs
                    }
                    val objectRetryBlocksUntilMs =
                        objectRetry
                            ?.nextRetryAtMs
                            ?.takeIf { it > nowMs }
                            ?: Long.MIN_VALUE
                    val effectiveDueAtMs = maxOf(debtDueAtMs, objectRetryBlocksUntilMs)
                    if (effectiveDueAtMs > nowMs) return@forEach

                    unknownDueCount += 1
                    if (!allowUnknownFingerprintVerify || unknownVerifyBudget == 0) {
                        return@forEach
                    }
                    selectedDue += DueCandidate(
                        entry = entry,
                        dueAtMs = effectiveDueAtMs,
                        retryItems = scoped,
                    )
                    selectedDue.sortWith(dueComparator)
                    if (selectedDue.size > unknownVerifyBudget) {
                        selectedDue.removeAt(selectedDue.lastIndex)
                    }
                }
            }

        val selectedEntries = selectedDue
            .sortedWith(dueComparator)
            .map(DueCandidate::entry)
        val selectedRetryItems = selectedDue
            .asSequence()
            .flatMap { it.retryItems.asSequence() }
            .toList()

        val cleanupRetryItems = mutableListOf<LibraryRetryItem>()
        if (unknownCleanupBudget > 0) {
            val cleanupBatches = cleanupCandidateStableObjectKeys
                .asSequence()
                .distinct()
                .chunked(LibraryRetryPaging.STABLE_KEY_LOOKUP_BATCH_SIZE)
                .iterator()
            while (
                cleanupBatches.hasNext() &&
                cleanupRetryItems.size < unknownCleanupBudget
            ) {
                val keys = cleanupBatches.next()
                val rows = loadByStableObjectKeys(keys)
                lookupBatchCount += 1
                retryRowsRead += rows.size
                rows.asSequence()
                    .filter { retry ->
                        retry.sourceIdentity == sourceIdentity &&
                            retry.retryKind == LibraryRetryKind.UNKNOWN_FINGERPRINT_VERIFY &&
                            (retry.activationEpoch == null ||
                                retry.activationEpoch == activationEpoch)
                    }
                    .take(unknownCleanupBudget - cleanupRetryItems.size)
                    .forEach(cleanupRetryItems::add)
            }
        }

        val retryItems = linkedMapOf<String, LibraryRetryItem>()
        dueObjectRetries.forEach { retryItems[it.retryKey] = it }
        selectedRetryItems.forEach { retryItems[it.retryKey] = it }
        cleanupRetryItems.forEach { retryItems[it.retryKey] = it }

        return SafRetryPlanningWorkingSet(
            retryItems = retryItems.values.toList(),
            preselectedUnknownFingerprintVerify = selectedEntries,
            unknownFingerprintDueCount = unknownDueCount,
            lookupBatchCount = lookupBatchCount,
            retryRowsRead = retryRowsRead,
        )
    }

    private fun <T> Sequence<T>.chunked(size: Int): Sequence<List<T>> = sequence {
        require(size > 0)
        val iterator = this@chunked.iterator()
        while (iterator.hasNext()) {
            val chunk = ArrayList<T>(size)
            repeat(size) {
                if (iterator.hasNext()) chunk += iterator.next()
            }
            if (chunk.isNotEmpty()) yield(chunk)
        }
    }
}
