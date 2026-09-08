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

class DeviceShadowCanonicalProjectorTest {

    private val context = DeviceShadowCanonicalContext(
        sourceIdentityStorageKey = SourceIdentityKey.device().storageKey(),
        activationEpoch = 7L,
        configFingerprint = "cfg",
        providerIdentityDomain = "mediastore:external_primary:v1",
    )

    @Test
    fun sidecarOnlyDeltaBuildsExactCanonicalCandidateWithoutHeavyProbe() {
        val beforeSong = song("ms_1").copy(externalLyricsSignature = "old")
        val expectedSong = beforeSong.copy(externalLyricsSignature = "new")
        val baseline = snapshot(beforeSong)
        val lyrics = DeviceLyricsSidecarDiff(
            changes = listOf(
                DeviceLyricsSignatureChange(
                    songId = beforeSong.id,
                    lyricsKey = "music\u0001track",
                    previousSignature = "old",
                    observedSignature = "new",
                ),
            ),
            unverifiableSongIds = emptySet(),
        )

        val projection = DeviceShadowCanonicalProjector.project(
            baseline = baseline,
            candidates = emptyCandidates(),
            lyricsDiff = lyrics,
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
        )
        val comparison = DeviceShadowCanonicalProjector.compare(
            projection,
            snapshot(expectedSong),
        )

        assertTrue(projection.fullyResolved)
        assertTrue(comparison.fullyEquivalent)
        assertTrue(comparison.diff.changes.isEmpty())
    }

    @Test
    fun existingAudioDeltaWithoutResolvedProbeRemainsExplicitlyUnresolved() {
        val beforeSong = song("ms_2")
        val row = row(beforeSong, modifiedMs = 2_000L)
        val candidate = candidate(beforeSong.id, beforeSong.id, row)
        val expectedSong = beforeSong.copy(
            dateModifiedMs = 2_000L,
            title = "new title",
        )

        val projection = DeviceShadowCanonicalProjector.project(
            baseline = snapshot(beforeSong),
            candidates = plan(candidate),
            lyricsDiff = emptyLyrics(),
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
        )
        val comparison = DeviceShadowCanonicalProjector.compare(
            projection,
            snapshot(expectedSong),
        )

        assertFalse(projection.fullyResolved)
        assertTrue(
            DeviceShadowCanonicalAspect.TAG_METADATA in
                projection.unresolvedAspectsByStableObjectKey.getValue(beforeSong.id),
        )
        assertEquals(
            2_000L,
            projection.snapshot.songsByStableObjectKey.getValue(beforeSong.id).dateModifiedMs,
        )
        assertFalse(comparison.fullyEquivalent)
        assertTrue(
            comparison.diff.changes.single().aspects.contains(
                DeviceShadowCanonicalAspect.TAG_METADATA,
            ),
        )
    }

    @Test
    fun resolvedAudioObjectCanMatchIndependentFullOracleExactly() {
        val beforeSong = song("ms_3")
        val row = row(
            beforeSong,
            modifiedMs = 3_000L,
            relativePath = "QQMusic/Song/",
        )
        val candidate = candidate(beforeSong.id, beforeSong.id, row)
        val expectedSong = beforeSong.copy(
            title = "resolved title",
            dateModifiedMs = 3_000L,
            folderPath = "QQMusic/Song",
            filePath = "QQMusic/Song/track.flac",
        )
        val resolvedCanonical = snapshot(expectedSong)
            .songsByStableObjectKey
            .getValue(beforeSong.id)

        val projection = DeviceShadowCanonicalProjector.project(
            baseline = snapshot(beforeSong),
            candidates = plan(candidate),
            lyricsDiff = emptyLyrics(),
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
            folderCasingPlan = DeviceDeltaFolderCasingPlan(
                decisions = listOf(
                    com.mica.music.data.scanner.DeviceFolderCasingDecision(
                        stableObjectKey = beforeSong.id,
                        observedFolderPath = "QQMusic/Song",
                        effectiveFolderPath = "QQMusic/Song",
                        reconciled = false,
                        identityVerified = true,
                    ),
                ),
                unresolvedCaseCollisions = 0,
                distinctPhysicalCaseCollisions = 0,
            ),
            resolvedObjectsByStableObjectKey = mapOf(
                beforeSong.id to DeviceShadowResolvedCanonicalObject(
                    song = resolvedCanonical,
                    resolvedAspects = DeviceShadowCanonicalAspect.entries.toSet(),
                ),
            ),
        )
        val comparison = DeviceShadowCanonicalProjector.compare(
            projection,
            snapshot(expectedSong),
        )

        assertTrue(projection.fullyResolved)
        assertTrue(comparison.fullyEquivalent)
    }

    @Test
    fun newEligibleObjectWithoutResolvedProbeCannotPretendMembershipEquivalent() {
        val newSong = song("ms_4")
        val candidate = candidate(
            stableObjectKey = newSong.id,
            existingSongId = null,
            row = row(newSong, modifiedMs = 4_000L),
        )

        val projection = DeviceShadowCanonicalProjector.project(
            baseline = snapshot(),
            candidates = plan(candidate),
            lyricsDiff = emptyLyrics(),
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
        )
        val comparison = DeviceShadowCanonicalProjector.compare(
            projection,
            snapshot(newSong.copy(dateModifiedMs = 4_000L)),
        )

        assertFalse(projection.fullyResolved)
        assertTrue(
            DeviceShadowCanonicalAspect.MEMBERSHIP in
                projection.unresolvedAspectsByStableObjectKey.getValue(newSong.id),
        )
        assertFalse(comparison.fullyEquivalent)
        assertEquals(
            setOf(DeviceShadowCanonicalAspect.MEMBERSHIP),
            comparison.diff.changes.single().aspects,
        )
    }

    @Test
    fun confirmedMissingApplyRemovesObjectAndMatchesFullOracle() {
        val beforeSong = song("ms_5")
        val removal = MembershipChange(
            stableObjectKey = beforeSong.id,
            songId = beforeSong.id,
            reason = MembershipRemovalReason.CONFIRMED_MISSING,
            evidenceRevision = "generation:12",
            sourceIdentity = SourceIdentityKey.device(),
        )

        val projection = DeviceShadowCanonicalProjector.project(
            baseline = snapshot(beforeSong),
            candidates = emptyCandidates(),
            lyricsDiff = emptyLyrics(),
            membershipPlan = AutoSyncMembershipPlan.Apply(listOf(removal)),
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
        )
        val comparison = DeviceShadowCanonicalProjector.compare(projection, snapshot())

        assertTrue(projection.fullyResolved)
        assertTrue(comparison.fullyEquivalent)
    }

    @Test
    fun quarantinedRemovalNeverReportsCanonicalEquivalence() {
        val beforeSong = song("ms_6")
        val removal = MembershipChange(
            stableObjectKey = beforeSong.id,
            songId = beforeSong.id,
            reason = MembershipRemovalReason.CONFIRMED_MISSING,
            evidenceRevision = "generation:12",
            sourceIdentity = SourceIdentityKey.device(),
        )

        val projection = DeviceShadowCanonicalProjector.project(
            baseline = snapshot(beforeSong),
            candidates = emptyCandidates(),
            lyricsDiff = emptyLyrics(),
            membershipPlan = AutoSyncMembershipPlan.Quarantine(
                membershipChanges = listOf(removal),
                reason = MassDeletionQuarantineReason.INVENTORY_COLLAPSE,
            ),
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
        )
        val comparison = DeviceShadowCanonicalProjector.compare(projection, snapshot())

        assertFalse(projection.fullyResolved)
        assertEquals(setOf(beforeSong.id), projection.quarantinedMembershipKeys)
        assertFalse(comparison.fullyEquivalent)
    }

    private fun song(id: String): Song = SongFixtures.song(id).copy(
        mediaUri = "content://media/external_primary/audio/${id.removePrefix("ms_")}",
        fileName = "track.flac",
        folderPath = "Music",
        filePath = "Music/track.flac",
        sizeBytes = 100L,
        dateModifiedMs = 1_000L,
    )

    private fun row(
        song: Song,
        modifiedMs: Long,
        relativePath: String = "Music/",
    ) = DeviceDeltaRow(
        channel = DeviceDeltaChannel.AUDIO,
        volumeName = "external_primary",
        mediaStoreId = song.id.removePrefix("ms_").toLong(),
        mediaUri = song.mediaUri,
        displayName = song.fileName,
        mimeType = "audio/flac",
        relativePath = relativePath,
        sizeBytes = song.sizeBytes,
        dateModifiedMs = modifiedMs,
        generationAdded = 10L,
        generationModified = 12L,
        eligibility = LibraryEligibility.ELIGIBLE,
    )

    private fun candidate(
        stableObjectKey: String,
        existingSongId: String?,
        row: DeviceDeltaRow,
    ) = DeviceAudioDeltaCandidate(
        canonicalStableObjectKey = stableObjectKey,
        observedStableObjectKeys = setOf(row.stableObjectKey),
        primaryRow = row,
        duplicateKey = mediaStoreDeltaRowDuplicateKey(row),
        lyricsKey = mediaStoreDeltaRowLyricsKey(row),
        existingSongId = existingSongId,
        sidecarChanged = false,
    )

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
