package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.scanner.DeviceAudioDeltaCandidate
import com.mica.music.data.scanner.DeviceDeltaCandidatePlan
import com.mica.music.data.scanner.DeviceDeltaChannel
import com.mica.music.data.scanner.DeviceDeltaFolderCasingPlan
import com.mica.music.data.scanner.DeviceDeltaRow
import com.mica.music.data.scanner.DeviceLyricsSidecarDiff
import com.mica.music.data.scanner.DeviceLyricsSignatureChange
import com.mica.music.data.scanner.DeviceShadowCanonicalAspect
import com.mica.music.data.scanner.DeviceShadowCanonicalCatalog
import com.mica.music.data.scanner.DeviceShadowCanonicalContext
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
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceShadowCanonicalMixedGateTest {
    private val source = SourceIdentityKey.device()
    private val context = DeviceShadowCanonicalContext(
        sourceIdentityStorageKey = source.storageKey(),
        activationEpoch = 7L,
        configFingerprint = "cfg",
        providerIdentityDomain = "mediastore:external_primary:v1",
    )

    @Test
    fun mixedDeviceDeltaMatchesCanonicalFullOracleWithPendingNormalization() {
        val changedBefore = song(1).copy(title = "old title")
        val lyricsBefore = song(2).copy(externalLyricsSignature = "lyrics-old")
        val filtered = song(3)
        val trashed = song(4)
        val pending = song(5)
        val missing = song(6)
        val unchanged = song(7)
        val added = song(8).copy(
            title = "new song",
            sizeBytes = 180L,
            dateModifiedMs = 3_000L,
        )
        val baselineSongs = listOf(
            changedBefore,
            lyricsBefore,
            filtered,
            trashed,
            pending,
            missing,
            unchanged,
        )
        val changedAfter = changedBefore.copy(
            title = "new title",
            dateModifiedMs = 2_000L,
        )
        val lyricsAfter = lyricsBefore.copy(externalLyricsSignature = "lyrics-new")

        val changedCandidate = candidate(
            song = changedBefore,
            modifiedMs = 2_000L,
            existingSongId = changedBefore.id,
        )
        val addedCandidate = candidate(
            song = added,
            modifiedMs = 3_000L,
            existingSongId = null,
        )
        val candidates = DeviceDeltaCandidatePlan(
            audioCandidates = listOf(changedCandidate, addedCandidate),
            sidecarCandidates = emptyList(),
            contradictions = emptyList(),
        )
        val lyricsDiff = DeviceLyricsSidecarDiff(
            changes = listOf(
                DeviceLyricsSignatureChange(
                    songId = lyricsBefore.id,
                    lyricsKey = "music\u0001track2",
                    previousSignature = "lyrics-old",
                    observedSignature = "lyrics-new",
                ),
            ),
            unverifiableSongIds = emptySet(),
        )
        val presence = completePresence(
            presence(changedBefore, LibraryEligibility.ELIGIBLE),
            presence(lyricsBefore, LibraryEligibility.ELIGIBLE),
            presence(filtered, LibraryEligibility.FILTERED_OUT),
            presence(trashed, LibraryEligibility.TRASHED),
            presence(pending, LibraryEligibility.PENDING),
            presence(unchanged, LibraryEligibility.ELIGIBLE),
            presence(added, LibraryEligibility.ELIGIBLE),
        )
        val evidenceRevision = "device-generation:external_primary:v1:12"
        val membershipPlan = AutoSyncMembershipPlanner.planDevice(
            previousSongs = baselineSongs,
            sourceIdentity = source,
            presence = presence,
            absenceEvidenceRevision = evidenceRevision,
        )
        val membershipAudit = DeviceShadowMembershipAuditor.audit(
            previousSongs = baselineSongs,
            candidates = candidates,
            presence = presence,
            membershipPlan = membershipPlan,
            sourceIdentity = source,
            evidenceRevision = evidenceRevision,
        )
        assertTrue(membershipAudit.fullyConsistent)
        assertEquals(
            setOf(
                MembershipRemovalReason.FILTERED_OUT,
                MembershipRemovalReason.TRASHED,
                MembershipRemovalReason.CONFIRMED_MISSING,
            ),
            membershipAudit.expectedRemovalChanges.values.mapTo(linkedSetOf()) { it.reason },
        )

        val resolved = mapOf(
            changedAfter.id to resolved(changedAfter),
            added.id to resolved(added),
        )
        val tracker = DeviceShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(baselineSongs))
        tracker.recordDelta(
            candidates = candidates,
            lyricsDiff = lyricsDiff,
            membershipPlan = membershipPlan,
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
            resolvedObjectsByStableObjectKey = resolved,
            membershipAudit = membershipAudit,
        )

        // Ordinary DEVICE Full hides the transient pending row. Canonical AUTO semantics retain
        // that pre-existing object until pending resolves, so the gate normalizes only that key.
        val rawFullOracle = snapshot(
            listOf(
                changedAfter,
                lyricsAfter,
                unchanged,
                added,
            ),
        )
        val result = tracker.acceptFullSnapshot(rawFullOracle)
            as DeviceShadowCanonicalProjectionGateResult.Compared

        assertTrue(result.equivalence.fullyEquivalent)
        assertTrue(result.equivalence.diff.changes.isEmpty())
        assertTrue(result.equivalence.projection.unresolvedAspectsByStableObjectKey.isEmpty())
        assertTrue(result.equivalence.projection.quarantinedMembershipKeys.isEmpty())
        assertEquals(setOf(pending.id), result.normalizedPendingKeepKeys)
    }

    private fun resolved(song: Song): DeviceShadowResolvedCanonicalObject =
        DeviceShadowResolvedCanonicalObject(
            song = snapshot(listOf(song)).songsByStableObjectKey.getValue(song.id),
            resolvedAspects = DeviceShadowCanonicalAspect.entries.toSet(),
        )

    private fun candidate(
        song: Song,
        modifiedMs: Long,
        existingSongId: String?,
    ): DeviceAudioDeltaCandidate {
        val row = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = song.id.removePrefix("ms_").toLong(),
            mediaUri = song.mediaUri,
            displayName = song.fileName,
            mimeType = "audio/flac",
            relativePath = "Music/",
            sizeBytes = song.sizeBytes,
            dateModifiedMs = modifiedMs,
            generationAdded = 12L,
            generationModified = 12L,
            eligibility = LibraryEligibility.ELIGIBLE,
        )
        return DeviceAudioDeltaCandidate(
            canonicalStableObjectKey = song.id,
            observedStableObjectKeys = setOf(row.stableObjectKey),
            primaryRow = row,
            duplicateKey = mediaStoreDeltaRowDuplicateKey(row),
            lyricsKey = mediaStoreDeltaRowLyricsKey(row),
            existingSongId = existingSongId,
            sidecarChanged = false,
        )
    }

    private fun song(index: Int): Song = SongFixtures.song("ms_$index").copy(
        mediaUri = "content://media/external_primary/audio/$index",
        fileName = "track$index.flac",
        folderPath = "Music",
        filePath = "Music/track$index.flac",
        sizeBytes = 100L + index,
        dateModifiedMs = 1_000L,
    )

    private fun presence(
        song: Song,
        eligibility: LibraryEligibility,
    ) = PresenceEntry(
        stableObjectKey = song.id,
        partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
        eligibility = eligibility,
        evidenceRevision = "presence:${song.id}:$eligibility",
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

    private fun snapshot(songs: List<Song>) =
        DeviceShadowCanonicalCatalog.snapshot(songs, context)
}
