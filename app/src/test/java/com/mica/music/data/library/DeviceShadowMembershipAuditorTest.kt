package com.mica.music.data.library

import com.mica.music.data.scanner.DeviceAudioDeltaCandidate
import com.mica.music.data.scanner.DeviceDeltaCandidatePlan
import com.mica.music.data.scanner.DeviceDeltaChannel
import com.mica.music.data.scanner.DeviceDeltaRow
import com.mica.music.data.scanner.DiscoveryCompleteness
import com.mica.music.data.scanner.DiscoveryPartitionStatus
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.DiscoveryReport
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.data.scanner.PresenceEntry
import com.mica.music.data.scanner.PresenceInventory
import com.mica.music.data.scanner.mediaStoreDeltaRowDuplicateKey
import com.mica.music.data.scanner.mediaStoreDeltaRowLyricsKey
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceShadowMembershipAuditorTest {
    private val source = SourceIdentityKey.device()

    @Test
    fun distinctRemovalReasonsAndEvidenceRemainAuditable() {
        val filtered = SongFixtures.song("ms_1")
        val trashed = SongFixtures.song("ms_2")
        val presence = completePresence(
            PresenceEntry(
                stableObjectKey = filtered.id,
                partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
                eligibility = LibraryEligibility.FILTERED_OUT,
                evidenceRevision = "filter-row",
            ),
            PresenceEntry(
                stableObjectKey = trashed.id,
                partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
                eligibility = LibraryEligibility.TRASHED,
                evidenceRevision = "trash-row",
            ),
        )
        val evidenceRevision = "device-generation:external_primary:v1:12"
        val plan = AutoSyncMembershipPlanner.planDevice(
            previousSongs = listOf(filtered, trashed),
            sourceIdentity = source,
            presence = presence,
            absenceEvidenceRevision = evidenceRevision,
        )

        val audit = DeviceShadowMembershipAuditor.audit(
            previousSongs = listOf(filtered, trashed),
            candidates = emptyCandidates(),
            presence = presence,
            membershipPlan = plan,
            sourceIdentity = source,
            evidenceRevision = evidenceRevision,
        )

        assertTrue(audit.fullyConsistent)
        assertEquals(DeviceShadowFilterOutcome.FILTERED_OUT, audit.outcomesByStableObjectKey[filtered.id])
        assertEquals(DeviceShadowFilterOutcome.TRASHED, audit.outcomesByStableObjectKey[trashed.id])
        assertEquals(1, audit.removalReasonCounts[MembershipRemovalReason.FILTERED_OUT])
        assertEquals(1, audit.removalReasonCounts[MembershipRemovalReason.TRASHED])
        assertTrue(audit.expectedRemovalChanges.values.all { it.evidenceRevision == evidenceRevision })
        assertTrue(audit.expectedRemovalChanges.values.all { it.sourceIdentity == source })
    }

    @Test
    fun pendingExistingIsKeptWhilePendingNewObjectIsSuppressed() {
        val existing = SongFixtures.song("ms_3")
        val pendingExisting = PresenceEntry(
            stableObjectKey = existing.id,
            partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
            eligibility = LibraryEligibility.PENDING,
            evidenceRevision = "pending-existing",
        )
        val pendingNewRow = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = 4L,
            mediaUri = "content://media/external_primary/audio/4",
            displayName = "copying.flac",
            mimeType = "audio/flac",
            relativePath = "Music/",
            sizeBytes = 50L,
            dateModifiedMs = 2_000L,
            generationAdded = 12L,
            generationModified = 12L,
            eligibility = LibraryEligibility.PENDING,
        )
        val pendingNew = DeviceAudioDeltaCandidate(
            canonicalStableObjectKey = "ms_4",
            observedStableObjectKeys = setOf(pendingNewRow.stableObjectKey),
            primaryRow = pendingNewRow,
            duplicateKey = mediaStoreDeltaRowDuplicateKey(pendingNewRow),
            lyricsKey = mediaStoreDeltaRowLyricsKey(pendingNewRow),
            existingSongId = null,
            sidecarChanged = false,
        )
        val presence = completePresence(pendingExisting)
        val plan = AutoSyncMembershipPlanner.planDevice(
            previousSongs = listOf(existing),
            sourceIdentity = source,
            presence = presence,
            absenceEvidenceRevision = "device-generation:external_primary:v1:12",
        )

        val audit = DeviceShadowMembershipAuditor.audit(
            previousSongs = listOf(existing),
            candidates = DeviceDeltaCandidatePlan(
                audioCandidates = listOf(pendingNew),
                sidecarCandidates = emptyList(),
                contradictions = emptyList(),
            ),
            presence = presence,
            membershipPlan = plan,
            sourceIdentity = source,
            evidenceRevision = "device-generation:external_primary:v1:12",
        )

        assertTrue(audit.fullyConsistent)
        assertEquals(setOf(existing.id), audit.pendingKeepKeys)
        assertEquals(DeviceShadowFilterOutcome.PENDING_KEEP, audit.outcomesByStableObjectKey[existing.id])
        assertEquals(DeviceShadowFilterOutcome.PENDING_NEW_SUPPRESSED, audit.outcomesByStableObjectKey["ms_4"])
        assertTrue(audit.expectedRemovalChanges.isEmpty())
    }

    @Test
    fun wrongPlannedRemovalReasonIsAContradiction() {
        val song = SongFixtures.song("ms_5")
        val presence = completePresence(
            PresenceEntry(
                stableObjectKey = song.id,
                partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
                eligibility = LibraryEligibility.FILTERED_OUT,
                evidenceRevision = "filter-row",
            ),
        )
        val evidenceRevision = "device-generation:external_primary:v1:12"
        val wrong = MembershipChange(
            stableObjectKey = song.id,
            songId = song.id,
            reason = MembershipRemovalReason.TRASHED,
            evidenceRevision = evidenceRevision,
            sourceIdentity = source,
        )

        val audit = DeviceShadowMembershipAuditor.audit(
            previousSongs = listOf(song),
            candidates = emptyCandidates(),
            presence = presence,
            membershipPlan = AutoSyncMembershipPlan.Apply(listOf(wrong)),
            sourceIdentity = source,
            evidenceRevision = evidenceRevision,
        )

        assertFalse(audit.fullyConsistent)
        assertEquals(MembershipRemovalReason.FILTERED_OUT, audit.contradictions.single().expectedReason)
        assertEquals(MembershipRemovalReason.TRASHED, audit.contradictions.single().plannedReason)
    }

    private fun emptyCandidates() = DeviceDeltaCandidatePlan(
        audioCandidates = emptyList(),
        sidecarCandidates = emptyList(),
        contradictions = emptyList(),
    )

    private fun completePresence(vararg entries: PresenceEntry) =
        PresenceInventory.fromEntries(
            entries = entries.toList(),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    DiscoveryPartitions.MEDIASTORE_AUDIO,
                    DiscoveryCompleteness.COMPLETE,
                ),
                DiscoveryPartitionStatus(
                    DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                    DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
}
