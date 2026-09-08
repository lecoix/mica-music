package com.mica.music.data.library

import com.mica.music.data.scanner.SafTreeMetadataEntry

internal data class SafShadowRetryPlan(
    val retryUpserts: List<LibraryRetryItem>,
    val retryDeleteKeys: Set<String>,
    val ignoredIssueCount: Int,
) {
    val isNoOp: Boolean
        get() = retryUpserts.isEmpty() && retryDeleteKeys.isEmpty()
}

internal object SafShadowRetryPlanner {
    private const val BASE_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 30L * 60L * 1000L
    private const val RETRY_KEY_PREFIX = "saf-object-probe:"

    fun plan(
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
        nowMs: Long,
        observedEntries: Collection<SafTreeMetadataEntry>,
        existingRetryItems: List<LibraryRetryItem>,
        validation: SafShadowPostValidationResult,
        authoritativeRemovedStableObjectKeys: Set<String> = emptySet(),
    ): SafShadowRetryPlan {
        val observedByKey = observedEntries.associateBy(SafTreeMetadataEntry::stableObjectKey)
        val existingByStableKey = existingRetryItems.asSequence()
            .filter { it.sourceIdentity == sourceIdentity }
            .filter { it.retryKind == LibraryRetryKind.OBJECT_PROBE }
            .filter { it.activationEpoch == null || it.activationEpoch == activationEpoch }
            .groupBy(LibraryRetryItem::stableObjectKey)

        val resolvedOrRemovedKeys =
            validation.resolvedSongsByStableObjectKey.keys + authoritativeRemovedStableObjectKeys
        val retryDeleteKeys = linkedSetOf<String>()
        resolvedOrRemovedKeys.forEach { stableKey ->
            existingByStableKey[stableKey].orEmpty()
                .mapTo(retryDeleteKeys, LibraryRetryItem::retryKey)
        }

        val retryableIssuesByKey = linkedMapOf<String, SafShadowProbeIssue>()
        var ignoredIssueCount = 0
        validation.issues.forEach { issue ->
            if (issue.kind.isRetryLedgerEligible()) {
                retryableIssuesByKey[issue.stableObjectKey] = issue
            } else {
                ignoredIssueCount += 1
            }
        }

        val upserts = mutableListOf<LibraryRetryItem>()
        retryableIssuesByKey.forEach { (stableKey, issue) ->
            val observed = observedByKey[stableKey]
            if (observed == null) {
                ignoredIssueCount += 1
                return@forEach
            }
            val fingerprint = safObservedFingerprint(observed)
            val previousItems = existingByStableKey[stableKey].orEmpty()
            previousItems.mapTo(retryDeleteKeys, LibraryRetryItem::retryKey)
            val previous = previousItems.maxByOrNull(LibraryRetryItem::attemptCount)
            val nextAttempt = if (previous?.observedFingerprint == fingerprint) {
                previous.attemptCount.coerceAtLeast(0) + 1
            } else {
                1
            }
            val delayMs = retryDelayMs(nextAttempt)
            upserts += LibraryRetryItem(
                sourceIdentity = sourceIdentity,
                retryKey = retryKey(stableKey),
                activationEpoch = activationEpoch,
                stableObjectKey = stableKey,
                observedFingerprint = fingerprint,
                retryKind = LibraryRetryKind.OBJECT_PROBE,
                failureKind = issue.kind.name,
                attemptCount = nextAttempt,
                nextRetryAtMs = safeAdd(nowMs, delayMs),
            )
        }

        val upsertKeys = upserts.mapTo(hashSetOf(), LibraryRetryItem::retryKey)
        retryDeleteKeys.removeAll(upsertKeys)

        return SafShadowRetryPlan(
            retryUpserts = upserts.sortedBy(LibraryRetryItem::retryKey),
            retryDeleteKeys = retryDeleteKeys.toSortedSet(),
            ignoredIssueCount = ignoredIssueCount,
        )
    }

    internal fun retryKey(stableObjectKey: String): String =
        "$RETRY_KEY_PREFIX$stableObjectKey"

    private fun SafShadowProbeIssueKind.isRetryLedgerEligible(): Boolean =
        when (this) {
            SafShadowProbeIssueKind.DRAFT_UNAVAILABLE,
            SafShadowProbeIssueKind.PROBE_FAILED,
            SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
            -> true
            SafShadowProbeIssueKind.PLAYBACK_DEFERRED,
            SafShadowProbeIssueKind.UNKNOWN_VERIFY_BUDGET_DEFERRED,
            SafShadowProbeIssueKind.UNVERIFIABLE_FINGERPRINT,
            -> false
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

internal fun safObservedFingerprint(entry: SafTreeMetadataEntry): String =
    buildString {
        append("uri=").append(entry.mediaUri)
        append("|file=").append(entry.filePath)
        append("|mime=").append(entry.mimeType)
        append("|size=").append(entry.sizeBytes)
        append("|modified=").append(entry.lastModifiedMs)
        append("|lyrics=").append(entry.externalLyricsSignature)
    }
