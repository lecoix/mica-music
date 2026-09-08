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
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.data.scanner.mediaStoreDeltaRowDuplicateKey
import com.mica.music.data.scanner.mediaStoreDeltaRowLyricsKey
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceShadowCanonicalProjectionTrackerTest {

    private val context = DeviceShadowCanonicalContext(
        sourceIdentityStorageKey = SourceIdentityKey.device().storageKey(),
        activationEpoch = 7L,
        configFingerprint = "cfg",
        providerIdentityDomain = "mediastore:external_primary:v1",
    )

    @Test
    fun sidecarOnlyDeltaMatchesNextFullOracle() {
        val before = song("ms_1").copy(externalLyricsSignature = "old")
        val after = before.copy(externalLyricsSignature = "new")
        val tracker = DeviceShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(before))

        tracker.recordDelta(
            candidates = emptyCandidates(),
            lyricsDiff = DeviceLyricsSidecarDiff(
                changes = listOf(
                    DeviceLyricsSignatureChange(
                        songId = before.id,
                        lyricsKey = "music\u0001track",
                        previousSignature = "old",
                        observedSignature = "new",
                    ),
                ),
                unverifiableSongIds = emptySet(),
            ),
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
        )

        val result = tracker.acceptFullSnapshot(snapshot(after))
            as DeviceShadowCanonicalProjectionGateResult.Compared

        assertTrue(result.equivalence.fullyEquivalent)
    }

    @Test
    fun pendingKeepNormalizesFullOracleThatTemporarilyHidesPendingRow() {
        val before = song("ms_10")
        val tracker = DeviceShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(before))
        val audit = DeviceShadowMembershipAudit(
            outcomesByStableObjectKey = mapOf(
                before.id to DeviceShadowFilterOutcome.PENDING_KEEP,
            ),
            expectedRemovalChanges = emptyMap(),
            plannedRemovalChanges = emptyMap(),
            contradictions = emptyList(),
            pendingKeepKeys = setOf(before.id),
            quarantined = false,
        )

        tracker.recordDelta(
            candidates = emptyCandidates(),
            lyricsDiff = emptyLyrics(),
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
            membershipAudit = audit,
        )

        val result = tracker.acceptFullSnapshot(snapshot())
            as DeviceShadowCanonicalProjectionGateResult.Compared

        assertTrue(result.equivalence.fullyEquivalent)
        assertEquals(setOf(before.id), result.normalizedPendingKeepKeys)

        val next = tracker.acceptFullSnapshot(snapshot())
            as DeviceShadowCanonicalProjectionGateResult.Compared
        assertTrue(next.equivalence.fullyEquivalent)
        assertTrue(next.normalizedPendingKeepKeys.isEmpty())
    }

    @Test
    fun unresolvedAudioProbePersistsAcrossLaterUnrelatedDelta() {
        val first = song("ms_1")
        val second = song("ms_2").copy(externalLyricsSignature = "old")
        val tracker = DeviceShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(first, second))

        val audioCandidate = candidate(first, modifiedMs = 2_000L)
        tracker.recordDelta(
            candidates = plan(audioCandidate),
            lyricsDiff = emptyLyrics(),
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
        )
        tracker.recordDelta(
            candidates = emptyCandidates(),
            lyricsDiff = DeviceLyricsSidecarDiff(
                changes = listOf(
                    DeviceLyricsSignatureChange(
                        songId = second.id,
                        lyricsKey = "music\u0001track",
                        previousSignature = "old",
                        observedSignature = "new",
                    ),
                ),
                unverifiableSongIds = emptySet(),
            ),
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
        )

        val expected = snapshot(
            first.copy(dateModifiedMs = 2_000L, title = "changed"),
            second.copy(externalLyricsSignature = "new"),
        )
        val result = tracker.acceptFullSnapshot(expected)
            as DeviceShadowCanonicalProjectionGateResult.Compared

        assertFalse(result.equivalence.fullyEquivalent)
        assertTrue(
            first.id in result.equivalence.projection.unresolvedAspectsByStableObjectKey,
        )
    }

    @Test
    fun laterResolvedObjectClearsEarlierUnresolvedState() {
        val before = song("ms_3")
        val after = before.copy(
            dateModifiedMs = 3_000L,
            title = "resolved",
        )
        val tracker = DeviceShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(before))
        val candidate = candidate(before, modifiedMs = 3_000L)

        tracker.recordDelta(
            candidates = plan(candidate),
            lyricsDiff = emptyLyrics(),
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
        )

        val resolvedCanonical = snapshot(after)
            .songsByStableObjectKey
            .getValue(before.id)
        tracker.recordDelta(
            candidates = plan(candidate),
            lyricsDiff = emptyLyrics(),
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
            resolvedObjectsByStableObjectKey = mapOf(
                before.id to DeviceShadowResolvedCanonicalObject(
                    song = resolvedCanonical,
                    resolvedAspects = DeviceShadowCanonicalAspect.entries.toSet(),
                ),
            ),
        )

        val result = tracker.acceptFullSnapshot(snapshot(after))
            as DeviceShadowCanonicalProjectionGateResult.Compared

        assertTrue(result.equivalence.fullyEquivalent)
    }

    private fun song(id: String): Song = SongFixtures.song(id).copy(
        mediaUri = "content://media/external_primary/audio/${id.removePrefix("ms_")}",
        fileName = "track.flac",
        folderPath = "Music",
        filePath = "Music/track.flac",
        sizeBytes = 100L,
        dateModifiedMs = 1_000L,
    )

    private fun candidate(
        song: Song,
        modifiedMs: Long,
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
            existingSongId = song.id,
            sidecarChanged = false,
        )
    }

    private fun plan(candidate: DeviceAudioDeltaCandidate) = DeviceDeltaCandidatePlan(
        audioCandidates = listOf(candidate),
        sidecarCandidates = emptyList(),
        contradictions = emptyList(),
    )

    private fun emptyCandidates() = DeviceDeltaCandidatePlan(
        audioCandidates = emptyList(),
        sidecarCandidates = emptyList(),
        contradictions = emptyList(),
    )

    private fun emptyLyrics() = DeviceLyricsSidecarDiff(
        changes = emptyList(),
        unverifiableSongIds = emptySet(),
    )

    private fun snapshot(vararg songs: Song) =
        DeviceShadowCanonicalCatalog.snapshot(songs.toList(), context)
}
