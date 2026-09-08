package com.mica.music.data.library

internal data class DeviceShadowRetryPlan(
    val retryUpserts: List<LibraryRetryItem>,
    val retryDeleteKeys: Set<String>,
    val ignoredIssueCount: Int,
) {
    val isNoOp: Boolean
        get() = retryUpserts.isEmpty() && retryDeleteKeys.isEmpty()
}

/**
 * Converts DEVICE object-probe failures into durable retry debt.
 *
 * Playback defer is intentionally excluded: that path retains the current generation window and is
 * woken by PLAYBACK_IO_RELEASE. Every issue accepted here must have a stable observation
 * fingerprint so advancing the generation checkpoint can never orphan an unresolved object.
 */
internal object DeviceShadowRetryPlanner {
    private const val BASE_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 30L * 60L * 1000L
    private const val RETRY_KEY_PREFIX = "device-object-probe:"
    internal const val RETRY_OBSERVATION_MISSING = "RETRY_OBSERVATION_MISSING"
    internal const val RETRY_OBSERVATION_UNAVAILABLE = "RETRY_OBSERVATION_UNAVAILABLE"

    fun plan(
        sourceIdentity: SourceIdentityKey,
        activationEpoch: Long,
        nowMs: Long,
        probePlan: DeviceAutoProbePlan,
        existingRetryItems: List<LibraryRetryItem>,
        execution: DeviceShadowProbeExecutionResult,
        authoritativeRemovedStableObjectKeys: Set<String> = emptySet(),
        retryObservationMissingStableObjectKeys: Set<String> = emptySet(),
        retryObservationUnavailableStableObjectKeys: Set<String> = emptySet(),
    ): DeviceShadowRetryPlan {
        val plansByKey = probePlan.objects.associateBy(DeviceAutoProbeObjectPlan::stableObjectKey)
        val existingByStableKey = existingRetryItems.asSequence()
            .filter { it.sourceIdentity == sourceIdentity }
            .filter { it.retryKind == LibraryRetryKind.OBJECT_PROBE }
            .filter { it.activationEpoch == null || it.activationEpoch == activationEpoch }
            .groupBy(LibraryRetryItem::stableObjectKey)

        val retryDeleteKeys = linkedSetOf<String>()
        val resolvedOrRemoved =
            execution.resolvedObjectsByStableObjectKey.keys +
                execution.resolvedSongsByStableObjectKey.keys +
                authoritativeRemovedStableObjectKeys
        resolvedOrRemoved.forEach { stableKey ->
            existingByStableKey[stableKey].orEmpty()
                .mapTo(retryDeleteKeys, LibraryRetryItem::retryKey)
        }

        val retryableIssuesByKey = linkedMapOf<String, DeviceShadowProbeIssue>()
        var ignoredIssueCount = 0
        execution.issues.forEach { issue ->
            if (issue.kind.isRetryLedgerEligible()) {
                retryableIssuesByKey[issue.stableObjectKey] = issue
            } else {
                ignoredIssueCount += 1
            }
        }

        val upserts = mutableListOf<LibraryRetryItem>()
        retryableIssuesByKey.forEach { (stableKey, issue) ->
            val objectPlan = plansByKey[stableKey]
            val fingerprint = objectPlan
                ?.observationStamp
                ?.fingerprint
                ?.takeIf(String::isNotBlank)
            if (fingerprint == null) {
                ignoredIssueCount += 1
                return@forEach
            }

            val previousItems = existingByStableKey[stableKey].orEmpty()
            previousItems.mapTo(retryDeleteKeys, LibraryRetryItem::retryKey)
            val previous = previousItems.maxByOrNull(LibraryRetryItem::attemptCount)
            val nextAttempt = if (previous?.observedFingerprint == fingerprint) {
                previous.attemptCount.coerceAtLeast(0) + 1
            } else {
                1
            }
            upserts += LibraryRetryItem(
                sourceIdentity = sourceIdentity,
                retryKey = retryKey(stableKey),
                activationEpoch = activationEpoch,
                stableObjectKey = stableKey,
                observedFingerprint = fingerprint,
                retryKind = LibraryRetryKind.OBJECT_PROBE,
                failureKind = issue.kind.name,
                attemptCount = nextAttempt,
                nextRetryAtMs = safeAdd(nowMs, retryDelayMs(nextAttempt)),
            )
        }

        val issueUpsertStableKeys = upserts.mapTo(hashSetOf(), LibraryRetryItem::stableObjectKey)
        val observationFailures = linkedMapOf<String, String>()
        retryObservationMissingStableObjectKeys.forEach { stableKey ->
            observationFailures[stableKey] = RETRY_OBSERVATION_MISSING
        }
        retryObservationUnavailableStableObjectKeys.forEach { stableKey ->
            observationFailures[stableKey] = RETRY_OBSERVATION_UNAVAILABLE
        }
        observationFailures.forEach { (stableKey, failureKind) ->
            if (stableKey in resolvedOrRemoved || stableKey in issueUpsertStableKeys) {
                return@forEach
            }
            val previousItems = existingByStableKey[stableKey].orEmpty()
            val previous = previousItems.maxByOrNull(LibraryRetryItem::attemptCount)
            if (previous == null) {
                ignoredIssueCount += 1
                return@forEach
            }
            previousItems.mapTo(retryDeleteKeys, LibraryRetryItem::retryKey)
            val nextAttempt = previous.attemptCount.coerceAtLeast(0) + 1
            upserts += previous.copy(
                retryKey = retryKey(stableKey),
                activationEpoch = activationEpoch,
                failureKind = failureKind,
                attemptCount = nextAttempt,
                nextRetryAtMs = safeAdd(nowMs, retryDelayMs(nextAttempt)),
            )
        }

        val upsertKeys = upserts.mapTo(hashSetOf(), LibraryRetryItem::retryKey)
        retryDeleteKeys.removeAll(upsertKeys)
        return DeviceShadowRetryPlan(
            retryUpserts = upserts.sortedBy(LibraryRetryItem::retryKey),
            retryDeleteKeys = retryDeleteKeys.toSortedSet(),
            ignoredIssueCount = ignoredIssueCount,
        )
    }

    internal fun retryKey(stableObjectKey: String): String =
        "$RETRY_KEY_PREFIX$stableObjectKey"

    private fun DeviceShadowProbeIssueKind.isRetryLedgerEligible(): Boolean = when (this) {
        DeviceShadowProbeIssueKind.DRAFT_MISSING,
        DeviceShadowProbeIssueKind.DRAFT_UNAVAILABLE,
        DeviceShadowProbeIssueKind.PRE_OBSERVATION_CHANGED,
        DeviceShadowProbeIssueKind.BECAME_INELIGIBLE,
        DeviceShadowProbeIssueKind.PROBE_FAILED,
        DeviceShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
        -> true

        DeviceShadowProbeIssueKind.PLAYBACK_DEFERRED -> false
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
