package com.mica.music.data.library

import com.mica.music.data.ScanSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceShadowRetryPlannerTest {
    private val source = SourceIdentityKey(ScanSource.DEVICE, "mediastore:external")

    @Test
    fun sameFingerprintFailureAdvancesBackoffAndReplacesPreviousDebt() {
        val previous = retry(
            stableKey = "song-1",
            fingerprint = "fp-1",
            attempt = 1,
            nextRetryAtMs = 30_000L,
        )
        val plan = DeviceShadowRetryPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 100_000L,
            probePlan = probePlan("song-1", "fp-1"),
            existingRetryItems = listOf(previous),
            execution = execution(
                DeviceShadowProbeIssue("song-1", DeviceShadowProbeIssueKind.PROBE_FAILED, "boom"),
            ),
        )

        val next = plan.retryUpserts.single()
        assertEquals(2, next.attemptCount)
        assertEquals(160_000L, next.nextRetryAtMs)
        assertEquals(DeviceShadowProbeIssueKind.PROBE_FAILED.name, next.failureKind)
        assertTrue(plan.retryDeleteKeys.isEmpty())
    }

    @Test
    fun changedFingerprintResetsAttemptToOne() {
        val previous = retry(
            stableKey = "song-1",
            fingerprint = "old-fp",
            attempt = 5,
            nextRetryAtMs = 999_999L,
        )
        val plan = DeviceShadowRetryPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            probePlan = probePlan("song-1", "new-fp"),
            existingRetryItems = listOf(previous),
            execution = execution(
                DeviceShadowProbeIssue(
                    "song-1",
                    DeviceShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
                ),
            ),
        )

        val next = plan.retryUpserts.single()
        assertEquals(1, next.attemptCount)
        assertEquals("new-fp", next.observedFingerprint)
        assertEquals(31_000L, next.nextRetryAtMs)
    }

    @Test
    fun playbackDeferredIsNotConvertedToTimerDebt() {
        val plan = DeviceShadowRetryPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            probePlan = probePlan("song-1", "fp"),
            existingRetryItems = emptyList(),
            execution = execution(
                DeviceShadowProbeIssue(
                    "song-1",
                    DeviceShadowProbeIssueKind.PLAYBACK_DEFERRED,
                ),
            ),
        )

        assertTrue(plan.retryUpserts.isEmpty())
        assertTrue(plan.retryDeleteKeys.isEmpty())
        assertEquals(1, plan.ignoredIssueCount)
    }

    @Test
    fun authoritativeRemovalClearsExistingRetry() {
        val previous = retry(
            stableKey = "song-1",
            fingerprint = "fp",
            attempt = 3,
            nextRetryAtMs = 5_000L,
        )
        val plan = DeviceShadowRetryPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            probePlan = DeviceAutoProbePlan(emptyList(), heavyProbeParallelism = 1),
            existingRetryItems = listOf(previous),
            execution = execution(),
            authoritativeRemovedStableObjectKeys = setOf("song-1"),
        )

        assertTrue(plan.retryUpserts.isEmpty())
        assertEquals(setOf(previous.retryKey), plan.retryDeleteKeys)
        assertFalse(plan.isNoOp)
    }

    @Test
    fun missingRetryObservationAdvancesBackoffInsteadOfLeavingDebtDue() {
        val previous = retry(
            stableKey = "song-1",
            fingerprint = "fp",
            attempt = 1,
            nextRetryAtMs = 1_000L,
        )
        val plan = DeviceShadowRetryPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 2_000L,
            probePlan = DeviceAutoProbePlan(emptyList(), heavyProbeParallelism = 1),
            existingRetryItems = listOf(previous),
            execution = execution(),
            retryObservationMissingStableObjectKeys = setOf("song-1"),
        )

        val next = plan.retryUpserts.single()
        assertEquals(2, next.attemptCount)
        assertEquals(62_000L, next.nextRetryAtMs)
        assertEquals(DeviceShadowRetryPlanner.RETRY_OBSERVATION_MISSING, next.failureKind)
        assertEquals("fp", next.observedFingerprint)
        assertTrue(plan.retryDeleteKeys.isEmpty())
    }

    @Test
    fun unavailableRetryObservationUsesSameBoundedBackoffLedger() {
        val previous = retry(
            stableKey = "song-1",
            fingerprint = "fp",
            attempt = 5,
            nextRetryAtMs = 1_000L,
        )
        val plan = DeviceShadowRetryPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 2_000L,
            probePlan = DeviceAutoProbePlan(emptyList(), heavyProbeParallelism = 1),
            existingRetryItems = listOf(previous),
            execution = execution(),
            retryObservationUnavailableStableObjectKeys = setOf("song-1"),
        )

        val next = plan.retryUpserts.single()
        assertEquals(6, next.attemptCount)
        assertEquals(962_000L, next.nextRetryAtMs)
        assertEquals(DeviceShadowRetryPlanner.RETRY_OBSERVATION_UNAVAILABLE, next.failureKind)
    }

    private fun probePlan(stableKey: String, fingerprint: String) = DeviceAutoProbePlan(
        objects = listOf(
            DeviceAutoProbeObjectPlan(
                stableObjectKey = stableKey,
                mediaUri = "content://media/$stableKey",
                reasons = setOf(DeviceAutoProbeReason.MEDIASTORE_REVISION_CHANGED),
                work = setOf(DeviceAutoProbeWork.AUDIO_METADATA),
                disposition = DeviceAutoProbeDisposition.READY,
                observationStamp = ObjectObservationStamp(
                    sourceIdentity = source,
                    activationEpoch = 7L,
                    stableObjectKey = stableKey,
                    fingerprint = fingerprint,
                ),
                objectRef = null,
            ),
        ),
        heavyProbeParallelism = 1,
    )

    private fun execution(
        vararg issues: DeviceShadowProbeIssue,
    ) = DeviceShadowProbeExecutionResult(
        resolvedObjectsByStableObjectKey = emptyMap(),
        issues = issues.toList(),
    )

    private fun retry(
        stableKey: String,
        fingerprint: String,
        attempt: Int,
        nextRetryAtMs: Long,
    ) = LibraryRetryItem(
        sourceIdentity = source,
        retryKey = DeviceShadowRetryPlanner.retryKey(stableKey),
        activationEpoch = 7L,
        stableObjectKey = stableKey,
        observedFingerprint = fingerprint,
        retryKind = LibraryRetryKind.OBJECT_PROBE,
        failureKind = DeviceShadowProbeIssueKind.PROBE_FAILED.name,
        attemptCount = attempt,
        nextRetryAtMs = nextRetryAtMs,
    )
}
