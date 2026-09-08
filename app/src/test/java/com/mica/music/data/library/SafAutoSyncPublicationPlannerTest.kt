package com.mica.music.data.library

import com.mica.music.data.LyricsSlots
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.Song
import com.mica.music.data.scanner.DiscoveryCompleteness
import com.mica.music.data.scanner.DiscoveryPartitionStatus
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.DiscoveryReport
import com.mica.music.data.scanner.SafFastVerifyPlan
import com.mica.music.data.scanner.SafFastVerifyPlanner
import com.mica.music.data.scanner.SafTreeMetadataEntry
import com.mica.music.data.scanner.SafTreeMetadataSnapshot
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafAutoSyncPublicationPlannerTest {
    private val source = SourceIdentityKey.folder("content://provider/tree/music")

    @Test
    fun validatedChangedObjectPublishesSongLyricsAndCheckpointTogether() {
        val current = song("doc-1")
        val observed = entry(current).copy(
            sizeBytes = current.sizeBytes + 10L,
            lastModifiedMs = current.dateModifiedMs + 20L,
        )
        val snapshot = snapshot(listOf(observed))
        val verifyPlan = SafFastVerifyPlanner.plan(snapshot, listOf(current))
        val resolved = current.copy(
            title = "After",
            sizeBytes = observed.sizeBytes,
            dateModifiedMs = observed.lastModifiedMs,
        )
        val lyrics = ScannedSongLyrics(current.id, resolved.lyricsCacheRevision, LyricsSlots())

        val plan = SafAutoSyncPublicationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            configFingerprint = "cfg",
            nowMs = 1_000L,
            currentSongs = listOf(current),
            snapshot = snapshot,
            verifyPlan = verifyPlan,
            probePlan = readyProbePlan(observed),
            validation = SafShadowPostValidationResult(
                resolvedSongsByStableObjectKey = mapOf(current.id to resolved),
                resolvedLyricsByStableObjectKey = mapOf(current.id to lyrics),
                issues = emptyList(),
            ),
            relationValidation = emptyRelationValidation(),
            retryPlan = emptyRetryPlan(),
            unknownDebtPlan = emptyUnknownDebtPlan(),
        )

        assertEquals(setOf(current.id), plan.visibleDelta.updatedIds)
        assertEquals("After", plan.nextSnapshot.single().title)
        assertEquals(listOf(lyrics), plan.lyricsToStage)
        assertTrue(plan.checkpointIncluded)
        val checkpoint = plan.autoSyncStateMutation.checkpoints.single()
        assertEquals(DiscoveryPartitions.SAF_TREE, checkpoint.partitionKey)
        assertEquals(SafAutoSyncPublicationPlanner.SAF_CHECKPOINT_PROVIDER_VERSION, checkpoint.providerVersion)
        assertEquals(SafAutoSyncPublicationPlanner.SAF_CHECKPOINT_GENERATION, checkpoint.generation)
        assertEquals(1_000L, checkpoint.lastSuccessfulAutoSyncAtMs)
    }

    @Test
    fun postValidationFailureKeepsOldSongPersistsRetryAndDoesNotAdvanceCheckpoint() {
        val current = song("doc-1")
        val observed = entry(current).copy(sizeBytes = current.sizeBytes + 1L)
        val snapshot = snapshot(listOf(observed))
        val verifyPlan = SafFastVerifyPlanner.plan(snapshot, listOf(current))
        val retry = retryItem(current.id, SafShadowRetryPlanner.retryKey(current.id))

        val plan = SafAutoSyncPublicationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            configFingerprint = "cfg",
            nowMs = 2_000L,
            currentSongs = listOf(current),
            snapshot = snapshot,
            verifyPlan = verifyPlan,
            probePlan = readyProbePlan(observed),
            validation = SafShadowPostValidationResult(
                resolvedSongsByStableObjectKey = emptyMap(),
                issues = listOf(
                    SafShadowProbeIssue(
                        current.id,
                        SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
                    ),
                ),
            ),
            relationValidation = emptyRelationValidation(),
            retryPlan = SafShadowRetryPlan(
                retryUpserts = listOf(retry),
                retryDeleteKeys = emptySet(),
                ignoredIssueCount = 0,
            ),
            unknownDebtPlan = emptyUnknownDebtPlan(),
        )

        assertFalse(plan.visibleDelta.hasVisibleChanges)
        assertEquals(listOf(current), plan.nextSnapshot)
        assertEquals(listOf(retry), plan.autoSyncStateMutation.retryUpserts)
        assertTrue(plan.autoSyncStateMutation.checkpoints.isEmpty())
        assertFalse(plan.checkpointIncluded)
    }

    @Test
    fun unresolvedRelationHoldsWholeAudioUpdateAndLyrics() {
        val current = song("doc-1")
        val observed = entry(current).copy(lastModifiedMs = current.dateModifiedMs + 1L)
        val snapshot = snapshot(listOf(observed))
        val verifyPlan = SafFastVerifyPlanner.plan(snapshot, listOf(current))
        val resolved = current.copy(
            title = "After",
            dateModifiedMs = observed.lastModifiedMs,
        )
        val lyrics = ScannedSongLyrics(current.id, resolved.lyricsCacheRevision, LyricsSlots())

        val plan = SafAutoSyncPublicationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            configFingerprint = "cfg",
            nowMs = 3_000L,
            currentSongs = listOf(current),
            snapshot = snapshot,
            verifyPlan = verifyPlan,
            probePlan = readyProbePlan(observed),
            validation = SafShadowPostValidationResult(
                resolvedSongsByStableObjectKey = mapOf(current.id to resolved),
                resolvedLyricsByStableObjectKey = mapOf(current.id to lyrics),
                issues = emptyList(),
            ),
            relationValidation = SafShadowRelationRematchResult(
                resolvedSongsByStableObjectKey = emptyMap(),
                resolvedFolderPaths = emptySet(),
                unresolvedStableObjectKeys = setOf(current.id),
                issues = listOf(
                    SafShadowRelationIssue(
                        current.folderPath,
                        SafShadowRelationIssueKind.AUDIO_WORK_UNRESOLVED,
                    ),
                ),
            ),
            retryPlan = emptyRetryPlan(),
            unknownDebtPlan = emptyUnknownDebtPlan(),
        )

        assertEquals(listOf(current), plan.nextSnapshot)
        assertFalse(plan.visibleDelta.hasVisibleChanges)
        assertTrue(plan.lyricsToStage.isEmpty())
        assertFalse(plan.checkpointIncluded)
    }

    @Test
    fun partialDiscoveryCannotRemoveOrCheckpoint() {
        val current = song("doc-1")
        val snapshot = snapshot(emptyList(), complete = false)
        val verifyPlan = SafFastVerifyPlanner.plan(snapshot, listOf(current))

        val plan = basePlan(
            currentSongs = listOf(current),
            snapshot = snapshot,
            verifyPlan = verifyPlan,
        )

        assertEquals(listOf(current), plan.nextSnapshot)
        assertTrue(plan.membershipChanges.isEmpty())
        assertTrue(plan.visibleDelta.removedStableObjectKeys.isEmpty())
        assertFalse(plan.checkpointIncluded)
    }

    @Test
    fun completeSmallRemovalPublishesConfirmedMissingAndCheckpoint() {
        val removed = song("doc-1")
        val kept = song("doc-2")
        val snapshot = snapshot(listOf(entry(kept)))
        val verifyPlan = SafFastVerifyPlanner.plan(snapshot, listOf(removed, kept))

        val plan = basePlan(
            currentSongs = listOf(removed, kept),
            snapshot = snapshot,
            verifyPlan = verifyPlan,
        )

        assertEquals(setOf(removed.id), plan.visibleDelta.removedStableObjectKeys)
        assertEquals(listOf(kept.id), plan.nextSnapshot.map(Song::id))
        assertEquals(MembershipRemovalReason.CONFIRMED_MISSING, plan.membershipChanges.single().reason)
        assertTrue(plan.checkpointIncluded)
    }

    @Test
    fun massRemovalQuarantinePreservesRowsRetryDebtAndSuppressesCheckpoint() {
        val current = List(100) { index -> song("doc-${index + 1}") }
        val kept = current.last()
        val snapshot = snapshot(listOf(entry(kept)))
        val verifyPlan = SafFastVerifyPlanner.plan(snapshot, current)
        val quarantinedKey = current.first().id
        val objectRetryKey = SafShadowRetryPlanner.retryKey(quarantinedKey)
        val unknownRetryKey = SafUnknownFingerprintDebtPlanner.retryKey(quarantinedKey)

        val plan = SafAutoSyncPublicationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            configFingerprint = "cfg",
            nowMs = 5_000L,
            currentSongs = current,
            snapshot = snapshot,
            verifyPlan = verifyPlan,
            probePlan = emptyProbePlan(),
            validation = emptyValidation(),
            relationValidation = emptyRelationValidation(),
            retryPlan = SafShadowRetryPlan(
                retryUpserts = emptyList(),
                retryDeleteKeys = setOf(objectRetryKey),
                ignoredIssueCount = 0,
            ),
            unknownDebtPlan = SafUnknownFingerprintDebtPlan(
                retryUpserts = emptyList(),
                retryDeleteKeys = setOf(unknownRetryKey),
                observedUnknownCount = 0,
                selectedDeepVerifyCount = 0,
                strongVerifiedCount = 0,
            ),
        )

        assertEquals(MassDeletionQuarantineReason.LARGE_UNVERIFIED_BATCH, plan.quarantineReason)
        assertEquals(100, plan.nextSnapshot.size)
        assertTrue(plan.membershipChanges.isEmpty())
        assertTrue(plan.visibleDelta.removedStableObjectKeys.isEmpty())
        assertFalse(objectRetryKey in plan.autoSyncStateMutation.retryDeleteKeys)
        assertFalse(unknownRetryKey in plan.autoSyncStateMutation.retryDeleteKeys)
        assertFalse(plan.checkpointIncluded)
    }

    @Test
    fun excludedNewObjectCannotEnterAuthorityOrStageLyrics() {
        val candidate = song("doc-new")
        val observed = entry(candidate)
        val snapshot = snapshot(listOf(observed))
        val verifyPlan = SafFastVerifyPlanner.plan(snapshot, emptyList())
        val lyrics = ScannedSongLyrics(candidate.id, candidate.lyricsCacheRevision, LyricsSlots())

        val plan = SafAutoSyncPublicationPlanner.plan(
            sourceIdentity = source,
            activationEpoch = 7L,
            configFingerprint = "cfg",
            nowMs = 6_000L,
            currentSongs = emptyList(),
            snapshot = snapshot,
            verifyPlan = verifyPlan,
            probePlan = readyProbePlan(observed),
            validation = SafShadowPostValidationResult(
                resolvedSongsByStableObjectKey = mapOf(candidate.id to candidate),
                resolvedLyricsByStableObjectKey = mapOf(candidate.id to lyrics),
                issues = emptyList(),
            ),
            relationValidation = emptyRelationValidation(),
            retryPlan = emptyRetryPlan(),
            unknownDebtPlan = emptyUnknownDebtPlan(),
            excludedStableObjectKeys = setOf(candidate.id),
        )

        assertTrue(plan.nextSnapshot.isEmpty())
        assertFalse(plan.visibleDelta.hasVisibleChanges)
        assertTrue(plan.lyricsToStage.isEmpty())
    }

    @Test
    fun heavyBudgetDebtBlocksCheckpointEvenWithoutVisibleMutation() {
        val current = song("doc-1")
        val observed = entry(current)
        val snapshot = snapshot(listOf(observed))
        val verifyPlan = SafFastVerifyPlanner.plan(snapshot, listOf(current))
        val deferredPlan = SafAutoProbePlan(
            objects = listOf(
                SafAutoProbeObjectPlan(
                    entry = observed,
                    reasons = setOf(SafAutoProbeReason.RETRY_LEDGER),
                    disposition = SafAutoProbeDisposition.DEFER_BY_HEAVY_PROBE_BUDGET,
                ),
            ),
            heavyProbeParallelism = 1,
        )

        val plan = basePlan(
            currentSongs = listOf(current),
            snapshot = snapshot,
            verifyPlan = verifyPlan,
            probePlan = deferredPlan,
        )

        assertFalse(plan.checkpointIncluded)
        assertTrue(plan.autoSyncStateMutation.checkpoints.isEmpty())
    }

    private fun basePlan(
        currentSongs: List<Song>,
        snapshot: SafTreeMetadataSnapshot,
        verifyPlan: SafFastVerifyPlan,
        probePlan: SafAutoProbePlan = emptyProbePlan(),
    ) = SafAutoSyncPublicationPlanner.plan(
        sourceIdentity = source,
        activationEpoch = 7L,
        configFingerprint = "cfg",
        nowMs = 10_000L,
        currentSongs = currentSongs,
        snapshot = snapshot,
        verifyPlan = verifyPlan,
        probePlan = probePlan,
        validation = emptyValidation(),
        relationValidation = emptyRelationValidation(),
        retryPlan = emptyRetryPlan(),
        unknownDebtPlan = emptyUnknownDebtPlan(),
    )

    private fun emptyValidation() = SafShadowPostValidationResult(
        resolvedSongsByStableObjectKey = emptyMap(),
        issues = emptyList(),
    )

    private fun emptyRelationValidation() = SafShadowRelationRematchResult(
        resolvedSongsByStableObjectKey = emptyMap(),
        resolvedFolderPaths = emptySet(),
        unresolvedStableObjectKeys = emptySet(),
        issues = emptyList(),
    )

    private fun emptyRetryPlan() = SafShadowRetryPlan(
        retryUpserts = emptyList(),
        retryDeleteKeys = emptySet(),
        ignoredIssueCount = 0,
    )

    private fun emptyUnknownDebtPlan() = SafUnknownFingerprintDebtPlan(
        retryUpserts = emptyList(),
        retryDeleteKeys = emptySet(),
        observedUnknownCount = 0,
        selectedDeepVerifyCount = 0,
        strongVerifiedCount = 0,
    )

    private fun emptyProbePlan() = SafAutoProbePlan(
        objects = emptyList(),
        heavyProbeParallelism = 1,
    )

    private fun readyProbePlan(entry: SafTreeMetadataEntry) = SafAutoProbePlan(
        objects = listOf(
            SafAutoProbeObjectPlan(
                entry = entry,
                reasons = setOf(SafAutoProbeReason.METADATA_OR_FINGERPRINT_CHANGED),
                disposition = SafAutoProbeDisposition.READY,
            ),
        ),
        heavyProbeParallelism = 1,
    )

    private fun retryItem(stableKey: String, retryKey: String) = LibraryRetryItem(
        sourceIdentity = source,
        retryKey = retryKey,
        activationEpoch = 7L,
        stableObjectKey = stableKey,
        observedFingerprint = "fp",
        retryKind = LibraryRetryKind.OBJECT_PROBE,
        failureKind = "POST_OBSERVATION_CHANGED",
        attemptCount = 1,
        nextRetryAtMs = 30_000L,
    )

    private fun song(id: String): Song = SongFixtures.song(id).copy(
        mediaUri = "content://provider/document/$id",
        fileName = "$id.flac",
        folderPath = "Album",
        filePath = "Album/$id.flac",
        sizeBytes = 1_000L,
        dateModifiedMs = 2_000L,
    )

    private fun entry(song: Song) = SafTreeMetadataEntry(
        stableObjectKey = song.id,
        mediaUri = song.mediaUri,
        fileName = song.fileName,
        folderPath = song.folderPath,
        filePath = song.filePath,
        mimeType = song.metadata.playbackMimeType,
        sizeBytes = song.sizeBytes,
        lastModifiedMs = song.dateModifiedMs,
        externalLyricsSignature = song.externalLyricsSignature,
    )

    private fun snapshot(
        entries: List<SafTreeMetadataEntry>,
        complete: Boolean = true,
    ) = SafTreeMetadataSnapshot(
        entries = entries,
        discoveryReport = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.SAF_TREE,
                completeness = if (complete) {
                    DiscoveryCompleteness.COMPLETE
                } else {
                    DiscoveryCompleteness.PARTIAL
                },
            ),
        ),
    )
}
