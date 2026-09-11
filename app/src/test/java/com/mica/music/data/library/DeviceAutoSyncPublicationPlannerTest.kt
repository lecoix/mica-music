package com.mica.music.data.library

import com.mica.music.data.LyricsSlots
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.ScanSource
import com.mica.music.data.scanner.DeviceGenerationSnapshot
import com.mica.music.data.scanner.DeviceVolumeGeneration
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAutoSyncPublicationPlannerTest {
    private val source = SourceIdentityKey.device()
    private val advanceTo = DeviceGenerationSnapshot.Available(
        mapOf(
            "external_primary" to DeviceVolumeGeneration(
                volumeName = "external_primary",
                providerVersion = "v1",
                generation = 12L,
            ),
        ),
    )

    @Test
    fun shortProbeResultCannotPublishSongOrLyrics() {
        val short = SongFixtures.song("ms_66").copy(durationSec = 4)
        val result = plan(
            currentSongs = listOf(short.copy(durationSec = 90)),
            execution = DeviceShadowProbeExecutionResult(
                resolvedObjectsByStableObjectKey = emptyMap(),
                resolvedSongsByStableObjectKey = mapOf(short.id to short),
                resolvedLyricsByStableObjectKey = mapOf(short.id to ScannedSongLyrics(short.id, "r", LyricsSlots())),
                fullReplaceLyricsStableObjectKeys = setOf(short.id),
                issues = emptyList(),
            ),
        )
        assertTrue(result.nextSnapshot.isEmpty())
        assertTrue(result.fullLyricsToStage.isEmpty())
        assertEquals(setOf(short.id), result.visibleDelta.removedStableObjectKeys)
        assertEquals(MembershipRemovalReason.FILTERED_OUT, result.membershipChanges.single().reason)
    }

    @Test
    fun validatedHeavyAndSidecarResultsSplitLyricsAndCheckpointTogether() {
        val heavyBefore = SongFixtures.song("ms_1").copy(externalLyricsSignature = "old")
        val sidecarBefore = SongFixtures.song("ms_2").copy(externalLyricsSignature = "old")
        val heavyAfter = heavyBefore.copy(title = "After heavy", dateModifiedMs = 9_000L)
        val sidecarAfter = sidecarBefore.copy(externalLyricsSignature = "new")
        val heavyLyrics = ScannedSongLyrics(
            heavyAfter.id,
            heavyAfter.lyricsCacheRevision,
            LyricsSlots(),
        )
        val sidecarLyrics = ScannedSongLyrics(
            sidecarAfter.id,
            sidecarAfter.lyricsCacheRevision,
            LyricsSlots(),
        )
        val probePlan = DeviceAutoProbePlan(
            objects = listOf(
                ready(heavyAfter.id, heavy = true),
                ready(sidecarAfter.id, heavy = false),
            ),
            heavyProbeParallelism = 1,
        )
        val execution = DeviceShadowProbeExecutionResult(
            resolvedObjectsByStableObjectKey = emptyMap(),
            resolvedSongsByStableObjectKey = mapOf(
                heavyAfter.id to heavyAfter,
                sidecarAfter.id to sidecarAfter,
            ),
            resolvedLyricsByStableObjectKey = mapOf(
                heavyAfter.id to heavyLyrics,
                sidecarAfter.id to sidecarLyrics,
            ),
            fullReplaceLyricsStableObjectKeys = setOf(heavyAfter.id),
            issues = emptyList(),
        )

        val plan = plan(
            currentSongs = listOf(heavyBefore, sidecarBefore),
            probePlan = probePlan,
            execution = execution,
        )

        assertEquals(setOf(heavyAfter.id, sidecarAfter.id), plan.visibleDelta.updatedIds)
        assertEquals(listOf(heavyLyrics), plan.fullLyricsToStage)
        assertEquals(listOf(sidecarLyrics), plan.externalLyricsToStage)
        assertTrue(plan.checkpointIncluded)
        assertEquals(12L, plan.autoSyncStateMutation.checkpoints.single().generation)
    }

    @Test
    fun retryableProbeFailureCanAdvanceWhenDebtIsPersistedAtomically() {
        val current = SongFixtures.song("ms_3")
        val retry = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = LibraryRetryKey.deviceObject(current.id),
            activationEpoch = 7L,
            stableObjectKey = current.id,
            observedFingerprint = "rev",
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = DeviceShadowProbeIssueKind.PROBE_FAILED.name,
            attemptCount = 1,
            nextRetryAtMs = 31_000L,
        )
        val plan = plan(
            currentSongs = listOf(current),
            probePlan = DeviceAutoProbePlan(
                objects = listOf(ready(current.id, heavy = true)),
                heavyProbeParallelism = 1,
            ),
            execution = DeviceShadowProbeExecutionResult(
                resolvedObjectsByStableObjectKey = emptyMap(),
                issues = listOf(
                    DeviceShadowProbeIssue(
                        stableObjectKey = current.id,
                        kind = DeviceShadowProbeIssueKind.PROBE_FAILED,
                    ),
                ),
            ),
            retryPlan = DeviceShadowRetryPlan(
                retryUpserts = listOf(retry),
                retryDeleteKeys = emptySet(),
                ignoredIssueCount = 0,
            ),
        )

        assertTrue(plan.checkpointIncluded)
        assertEquals(listOf(retry), plan.autoSyncStateMutation.retryUpserts)
        assertFalse(plan.visibleDelta.hasVisibleChanges)
    }

    @Test
    fun nonLedgerSafeFailureAndPlaybackDeferHoldCheckpoint() {
        val current = SongFixtures.song("ms_4")
        val ignoredFailure = plan(
            currentSongs = listOf(current),
            probePlan = DeviceAutoProbePlan(
                objects = listOf(ready(current.id, heavy = true)),
                heavyProbeParallelism = 1,
            ),
            execution = DeviceShadowProbeExecutionResult(
                resolvedObjectsByStableObjectKey = emptyMap(),
                issues = listOf(
                    DeviceShadowProbeIssue(
                        stableObjectKey = current.id,
                        kind = DeviceShadowProbeIssueKind.PLAYBACK_DEFERRED,
                    ),
                ),
            ),
            retryPlan = DeviceShadowRetryPlan(
                retryUpserts = emptyList(),
                retryDeleteKeys = emptySet(),
                ignoredIssueCount = 1,
            ),
        )
        val deferred = plan(
            currentSongs = listOf(current),
            probePlan = DeviceAutoProbePlan(
                objects = listOf(
                    ready(current.id, heavy = true).copy(
                        disposition = DeviceAutoProbeDisposition.DEFER_UNTIL_PLAYBACK_RELEASE,
                    ),
                ),
                heavyProbeParallelism = 1,
            ),
        )

        assertFalse(ignoredFailure.checkpointIncluded)
        assertFalse(deferred.checkpointIncluded)
        assertTrue(ignoredFailure.autoSyncStateMutation.checkpoints.isEmpty())
        assertTrue(deferred.autoSyncStateMutation.checkpoints.isEmpty())
    }

    @Test
    fun quarantinePreservesMembershipAndCheckpoint() {
        val removed = SongFixtures.song("ms_5")
        val change = MembershipChange(
            stableObjectKey = removed.id,
            songId = removed.id,
            reason = MembershipRemovalReason.CONFIRMED_MISSING,
            evidenceRevision = "gen-12",
            sourceIdentity = source,
        )

        val plan = plan(
            currentSongs = listOf(removed),
            membershipPlan = AutoSyncMembershipPlan.Quarantine(
                membershipChanges = listOf(change),
                reason = MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH,
            ),
        )

        assertEquals(listOf(removed.id), plan.nextSnapshot.map { it.id })
        assertTrue(plan.membershipChanges.isEmpty())
        assertTrue(plan.visibleDelta.removedStableObjectKeys.isEmpty())
        assertFalse(plan.checkpointIncluded)
    }

    @Test
    fun excludedNewObjectCannotEnterCatalogOrLyrics() {
        val added = SongFixtures.song("ms_6")
        val lyrics = ScannedSongLyrics(added.id, added.lyricsCacheRevision, LyricsSlots())
        val plan = plan(
            currentSongs = emptyList(),
            probePlan = DeviceAutoProbePlan(
                objects = listOf(ready(added.id, heavy = true)),
                heavyProbeParallelism = 1,
            ),
            execution = DeviceShadowProbeExecutionResult(
                resolvedObjectsByStableObjectKey = emptyMap(),
                resolvedSongsByStableObjectKey = mapOf(added.id to added),
                resolvedLyricsByStableObjectKey = mapOf(added.id to lyrics),
                fullReplaceLyricsStableObjectKeys = setOf(added.id),
                issues = emptyList(),
            ),
            excludedStableObjectKeys = setOf(added.id),
        )

        assertTrue(plan.nextSnapshot.isEmpty())
        assertFalse(plan.visibleDelta.hasVisibleChanges)
        assertTrue(plan.fullLyricsToStage.isEmpty())
        assertTrue(plan.externalLyricsToStage.isEmpty())
    }

    private fun plan(
        currentSongs: List<com.mica.music.data.Song>,
        membershipPlan: AutoSyncMembershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
        probePlan: DeviceAutoProbePlan = DeviceAutoProbePlan(emptyList(), 1),
        execution: DeviceShadowProbeExecutionResult = DeviceShadowProbeExecutionResult(
            resolvedObjectsByStableObjectKey = emptyMap(),
            issues = emptyList(),
        ),
        retryPlan: DeviceShadowRetryPlan = DeviceShadowRetryPlan(
            retryUpserts = emptyList(),
            retryDeleteKeys = emptySet(),
            ignoredIssueCount = 0,
        ),
        excludedStableObjectKeys: Set<String> = emptySet(),
    ) = DeviceAutoSyncPublicationPlanner.plan(
        sourceIdentity = source,
        configFingerprint = "cfg",
        nowMs = 1_000L,
        currentSongs = currentSongs,
        advanceTo = advanceTo,
        existingCheckpoints = emptyList(),
        membershipPlan = membershipPlan,
        probePlan = probePlan,
        execution = execution,
        retryPlan = retryPlan,
        excludedStableObjectKeys = excludedStableObjectKeys,
    )

    private fun ready(
        stableObjectKey: String,
        heavy: Boolean,
    ) = DeviceAutoProbeObjectPlan(
        stableObjectKey = stableObjectKey,
        mediaUri = "content://media/external_primary/audio/media/1",
        reasons = setOf(DeviceAutoProbeReason.MEDIASTORE_REVISION_CHANGED),
        work = if (heavy) {
            setOf(DeviceAutoProbeWork.AUDIO_METADATA)
        } else {
            setOf(DeviceAutoProbeWork.EXTERNAL_LYRICS)
        },
        disposition = DeviceAutoProbeDisposition.READY,
        observationStamp = if (heavy) {
            ObjectObservationStamp(
                sourceIdentity = source,
                activationEpoch = 7L,
                stableObjectKey = stableObjectKey,
                fingerprint = "rev",
                providerGeneration = 12L,
            )
        } else {
            null
        },
        objectRef = null,
    )
}
