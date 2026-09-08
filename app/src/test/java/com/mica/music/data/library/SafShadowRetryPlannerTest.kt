package com.mica.music.data.library

import com.mica.music.data.scanner.SafTreeMetadataEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafShadowRetryPlannerTest {

    @Test
    fun retryableProbeFailureCreatesFirstBackoffItem() {
        val source = SourceIdentityKey.folder("content://provider/tree/music")
        val observed = entry("failed")
        val plan = SafShadowRetryPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            observedEntries = listOf(observed),
            existingRetryItems = emptyList(),
            validation = SafShadowPostValidationResult(
                resolvedSongsByStableObjectKey = emptyMap(),
                issues = listOf(
                    SafShadowProbeIssue(
                        stableObjectKey = observed.stableObjectKey,
                        kind = SafShadowProbeIssueKind.PROBE_FAILED,
                        detail = "boom",
                    ),
                ),
            ),
        )

        assertEquals(1, plan.retryUpserts.size)
        val retry = plan.retryUpserts.single()
        assertEquals(SafShadowRetryPlanner.retryKey(observed.stableObjectKey), retry.retryKey)
        assertEquals(1, retry.attemptCount)
        assertEquals(31_000L, retry.nextRetryAtMs)
        assertEquals(SafShadowProbeIssueKind.PROBE_FAILED.name, retry.failureKind)
        assertEquals(safObservedFingerprint(observed), retry.observedFingerprint)
        assertTrue(plan.retryDeleteKeys.isEmpty())
    }

    @Test
    fun sameObservedRevisionIncrementsAttemptAndBackoff() {
        val source = SourceIdentityKey.folder("content://provider/tree/music")
        val observed = entry("failed")
        val existing = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "legacy-key",
            activationEpoch = 7L,
            stableObjectKey = observed.stableObjectKey,
            observedFingerprint = safObservedFingerprint(observed),
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = "PROBE_FAILED",
            attemptCount = 2,
            nextRetryAtMs = 0L,
        )

        val plan = SafShadowRetryPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            observedEntries = listOf(observed),
            existingRetryItems = listOf(existing),
            validation = failedValidation(observed.stableObjectKey),
        )

        val retry = plan.retryUpserts.single()
        assertEquals(3, retry.attemptCount)
        assertEquals(121_000L, retry.nextRetryAtMs)
        assertEquals(setOf("legacy-key"), plan.retryDeleteKeys)
    }

    @Test
    fun changedObservedRevisionResetsAttemptCount() {
        val source = SourceIdentityKey.folder("content://provider/tree/music")
        val old = entry("failed")
        val changed = old.copy(lastModifiedMs = old.lastModifiedMs + 1L)
        val existing = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = SafShadowRetryPlanner.retryKey(old.stableObjectKey),
            activationEpoch = 7L,
            stableObjectKey = old.stableObjectKey,
            observedFingerprint = safObservedFingerprint(old),
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = "PROBE_FAILED",
            attemptCount = 5,
            nextRetryAtMs = 0L,
        )

        val plan = SafShadowRetryPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            observedEntries = listOf(changed),
            existingRetryItems = listOf(existing),
            validation = failedValidation(changed.stableObjectKey),
        )

        assertEquals(1, plan.retryUpserts.single().attemptCount)
        assertEquals(31_000L, plan.retryUpserts.single().nextRetryAtMs)
    }

    @Test
    fun successfulValidationDeletesExistingRetry() {
        val source = SourceIdentityKey.folder("content://provider/tree/music")
        val observed = entry("ok")
        val existing = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "old-retry",
            activationEpoch = 7L,
            stableObjectKey = observed.stableObjectKey,
            observedFingerprint = safObservedFingerprint(observed),
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = "PROBE_FAILED",
            attemptCount = 2,
            nextRetryAtMs = 0L,
        )

        val plan = SafShadowRetryPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            observedEntries = listOf(observed),
            existingRetryItems = listOf(existing),
            validation = SafShadowPostValidationResult(
                resolvedSongsByStableObjectKey = mapOf(
                    observed.stableObjectKey to com.mica.music.testutil.SongFixtures.song("ok"),
                ),
                issues = emptyList(),
            ),
        )

        assertTrue(plan.retryUpserts.isEmpty())
        assertEquals(setOf("old-retry"), plan.retryDeleteKeys)
    }

    @Test
    fun authoritativeRemovalDeletesAllExistingProbeRetries() {
        val source = SourceIdentityKey.folder("content://provider/tree/music")
        val stableKey = "removed"
        val retries = listOf(
            LibraryRetryItem(
                sourceIdentity = source,
                retryKey = "legacy-a",
                activationEpoch = 7L,
                stableObjectKey = stableKey,
                observedFingerprint = "old-a",
                retryKind = LibraryRetryKind.OBJECT_PROBE,
                failureKind = "PROBE_FAILED",
                attemptCount = 1,
                nextRetryAtMs = 0L,
            ),
            LibraryRetryItem(
                sourceIdentity = source,
                retryKey = "legacy-b",
                activationEpoch = 7L,
                stableObjectKey = stableKey,
                observedFingerprint = "old-b",
                retryKind = LibraryRetryKind.OBJECT_PROBE,
                failureKind = "PROBE_FAILED",
                attemptCount = 2,
                nextRetryAtMs = 0L,
            ),
        )

        val plan = SafShadowRetryPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            observedEntries = emptyList(),
            existingRetryItems = retries,
            validation = SafShadowPostValidationResult(
                resolvedSongsByStableObjectKey = emptyMap(),
                issues = emptyList(),
            ),
            authoritativeRemovedStableObjectKeys = setOf(stableKey),
        )

        assertTrue(plan.retryUpserts.isEmpty())
        assertEquals(setOf("legacy-a", "legacy-b"), plan.retryDeleteKeys)
    }

    @Test
    fun playbackAndUnknownFingerprintDoNotCreateRetryLedgerStorm() {
        val source = SourceIdentityKey.folder("content://provider/tree/music")
        val playback = entry("playback")
        val unknown = entry("unknown")
        val plan = SafShadowRetryPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            nowMs = 1_000L,
            observedEntries = listOf(playback, unknown),
            existingRetryItems = emptyList(),
            validation = SafShadowPostValidationResult(
                resolvedSongsByStableObjectKey = emptyMap(),
                issues = listOf(
                    SafShadowProbeIssue(
                        stableObjectKey = playback.stableObjectKey,
                        kind = SafShadowProbeIssueKind.PLAYBACK_DEFERRED,
                    ),
                    SafShadowProbeIssue(
                        stableObjectKey = unknown.stableObjectKey,
                        kind = SafShadowProbeIssueKind.UNVERIFIABLE_FINGERPRINT,
                    ),
                ),
            ),
        )

        assertTrue(plan.isNoOp)
        assertEquals(2, plan.ignoredIssueCount)
    }

    private fun failedValidation(stableKey: String) = SafShadowPostValidationResult(
        resolvedSongsByStableObjectKey = emptyMap(),
        issues = listOf(
            SafShadowProbeIssue(
                stableObjectKey = stableKey,
                kind = SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
            ),
        ),
    )

    private fun entry(key: String) = SafTreeMetadataEntry(
        stableObjectKey = key,
        mediaUri = "content://provider/document/$key",
        fileName = "$key.flac",
        folderPath = "Album",
        filePath = "Album/$key.flac",
        mimeType = "audio/flac",
        sizeBytes = 1_234L,
        lastModifiedMs = 5_678L,
        externalLyricsSignature = "lyrics:v1",
    )
}
