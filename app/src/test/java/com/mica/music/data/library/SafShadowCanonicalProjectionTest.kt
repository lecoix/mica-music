package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.scanner.DeviceShadowCanonicalAspect
import com.mica.music.data.scanner.DiscoveryCompleteness
import com.mica.music.data.scanner.DiscoveryPartitionStatus
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.DiscoveryReport
import com.mica.music.data.scanner.SafFastVerifyPlan
import com.mica.music.data.scanner.SafTreeMetadataEntry
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafShadowCanonicalProjectionTest {

    private val context = SafShadowCanonicalContext(
        sourceIdentityStorageKey = "folder|content://provider/tree/music",
        activationEpoch = 1L,
        configFingerprint = "config-v1",
    )

    @Test
    fun identicalFullAfterBaselineIsEquivalent() {
        val song = song("doc-1")
        val tracker = SafShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(song))

        val compared = tracker.acceptFullSnapshot(snapshot(song))
            as SafShadowCanonicalProjectionGateResult.Compared

        assertTrue(compared.equivalence.fullyEquivalent)
        assertTrue(compared.equivalence.diff.changes.isEmpty())
        assertTrue(compared.equivalence.projection.unresolvedAspectsByStableObjectKey.isEmpty())
    }

    @Test
    fun safDateAddedWallClockDriftDoesNotCountAsMembershipChange() {
        val before = song("doc-1").copy(dateAddedMs = 1_000L)
        val after = before.copy(dateAddedMs = 9_999L)
        val tracker = SafShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(before))

        val compared = tracker.acceptFullSnapshot(snapshot(after))
            as SafShadowCanonicalProjectionGateResult.Compared

        assertTrue(compared.equivalence.fullyEquivalent)
        assertTrue(compared.equivalence.diff.changes.isEmpty())
    }

    @Test
    fun completeRemovalPlanProjectsMembershipRemoval() {
        val kept = song("kept")
        val removed = song("removed")
        val tracker = SafShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(kept, removed))
        tracker.recordDelta(
            verifyPlan = verifyPlan(
                removedStableObjectKeys = setOf(removed.id),
            ),
            resolvedSongsByStableObjectKey = emptyMap(),
        )

        val compared = tracker.acceptFullSnapshot(snapshot(kept))
            as SafShadowCanonicalProjectionGateResult.Compared

        assertTrue(compared.equivalence.fullyEquivalent)
        assertTrue(compared.equivalence.diff.changes.isEmpty())
    }

    @Test
    fun successfulChangedProbeWithoutRelationWorkDoesNotInventVideoUnresolved() {
        val before = song("doc-1").copy(title = "Before")
        val observed = entry(before).copy(
            sizeBytes = before.sizeBytes + 1L,
            lastModifiedMs = before.dateModifiedMs + 1L,
        )
        val resolved = before.copy(
            title = "After",
            sizeBytes = observed.sizeBytes,
            dateModifiedMs = observed.lastModifiedMs,
        )
        val tracker = SafShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(before))
        tracker.recordDelta(
            verifyPlan = verifyPlan(changed = listOf(observed)),
            resolvedSongsByStableObjectKey = mapOf(before.id to resolved),
        )

        val compared = tracker.acceptFullSnapshot(snapshot(resolved))
            as SafShadowCanonicalProjectionGateResult.Compared

        assertTrue(compared.equivalence.fullyEquivalent)
        assertTrue(compared.equivalence.diff.changes.isEmpty())
        assertTrue(compared.equivalence.projection.unresolvedAspectsByStableObjectKey.isEmpty())
    }

    @Test
    fun resolvedAudioRevisionBecomesCrossPassProgressAnchorUntilObservationChanges() {
        val before = song("doc-1").copy(title = "Before")
        val observed = entry(before).copy(
            sizeBytes = before.sizeBytes + 1L,
            lastModifiedMs = before.dateModifiedMs + 1L,
        )
        val resolved = before.copy(
            title = "After",
            sizeBytes = observed.sizeBytes,
            dateModifiedMs = observed.lastModifiedMs,
        )
        val tracker = SafShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(before))

        assertTrue(tracker.resolvedAudioStableObjectKeys(listOf(observed)).isEmpty())

        tracker.recordDelta(
            verifyPlan = verifyPlan(changed = listOf(observed)),
            resolvedSongsByStableObjectKey = mapOf(before.id to resolved),
        )

        assertEquals(
            setOf(before.id),
            tracker.resolvedAudioStableObjectKeys(listOf(observed)),
        )

        val changedAgain = observed.copy(lastModifiedMs = observed.lastModifiedMs + 1L)
        assertTrue(tracker.resolvedAudioStableObjectKeys(listOf(changedAgain)).isEmpty())
    }

    @Test
    fun unresolvedAudioRevisionIsNeverUsedAsCrossPassProgressAnchor() {
        val before = song("doc-1")
        val observed = entry(before).copy(
            sizeBytes = before.sizeBytes + 1L,
            lastModifiedMs = before.dateModifiedMs + 1L,
        )
        val tracker = SafShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(before))
        tracker.recordDelta(
            verifyPlan = verifyPlan(changed = listOf(observed)),
            resolvedSongsByStableObjectKey = emptyMap(),
        )

        assertTrue(tracker.resolvedAudioStableObjectKeys(listOf(observed)).isEmpty())
    }

    @Test
    fun explicitUnresolvedRelationWorkKeepsVideoAspectsUnresolved() {
        val before = song("doc-1").copy(title = "Before")
        val observed = entry(before).copy(
            sizeBytes = before.sizeBytes + 1L,
            lastModifiedMs = before.dateModifiedMs + 1L,
        )
        val resolved = before.copy(
            title = "After",
            sizeBytes = observed.sizeBytes,
            dateModifiedMs = observed.lastModifiedMs,
        )
        val tracker = SafShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(before))
        tracker.recordDelta(
            verifyPlan = verifyPlan(changed = listOf(observed)),
            resolvedSongsByStableObjectKey = mapOf(before.id to resolved),
            unresolvedRelationStableObjectKeys = setOf(before.id),
        )

        val compared = tracker.acceptFullSnapshot(snapshot(resolved))
            as SafShadowCanonicalProjectionGateResult.Compared
        val unresolved =
            compared.equivalence.projection.unresolvedAspectsByStableObjectKey.getValue(before.id)

        assertFalse(compared.equivalence.fullyEquivalent)
        assertTrue(compared.equivalence.diff.changes.isEmpty())
        assertEquals(
            setOf(
                DeviceShadowCanonicalAspect.VIDEO_COVER,
                DeviceShadowCanonicalAspect.MUSIC_VIDEO,
            ),
            unresolved,
        )
    }

    @Test
    fun unknownStrongVerifiedAudioStillKeepsLyricsResourceAspectsUnresolved() {
        val before = song("doc-1").copy(title = "Before")
        val observed = entry(before).copy(lastModifiedMs = 0L)
        val resolved = before.copy(
            title = "After",
            dateModifiedMs = 0L,
        )
        val tracker = SafShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(before))
        tracker.recordDelta(
            verifyPlan = verifyPlan(unknown = listOf(observed)),
            resolvedSongsByStableObjectKey = mapOf(before.id to resolved),
            unresolvedAudioResourceStableObjectKeys = setOf(before.id),
        )

        val compared = tracker.acceptFullSnapshot(snapshot(resolved))
            as SafShadowCanonicalProjectionGateResult.Compared
        val unresolved =
            compared.equivalence.projection.unresolvedAspectsByStableObjectKey.getValue(before.id)

        assertFalse(compared.equivalence.fullyEquivalent)
        assertTrue(compared.equivalence.diff.changes.isEmpty())
        assertTrue(DeviceShadowCanonicalAspect.EXTERNAL_LYRICS in unresolved)
        assertTrue(DeviceShadowCanonicalAspect.EMBEDDED_LYRICS_PROBE in unresolved)
    }

    @Test
    fun validatedRelationRematchClosesVideoAspectsAndMatchesFullOracle() {
        val before = song("doc-1").copy(title = "Before")
        val observed = entry(before).copy(
            sizeBytes = before.sizeBytes + 1L,
            lastModifiedMs = before.dateModifiedMs + 1L,
        )
        val resolvedAudio = before.copy(
            title = "After",
            sizeBytes = observed.sizeBytes,
            dateModifiedMs = observed.lastModifiedMs,
        )
        val resolvedRelations = resolvedAudio.copy(
            videoCoverUri = "content://provider/document/cover.mp4",
            videoCoverRevision = "cover:v2",
            musicVideoUri = "content://provider/document/mv.mp4",
            musicVideoRevision = "mv:v2",
        )
        val tracker = SafShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(before))
        tracker.recordDelta(
            verifyPlan = verifyPlan(changed = listOf(observed)),
            resolvedSongsByStableObjectKey = mapOf(before.id to resolvedAudio),
            resolvedRelationSongsByStableObjectKey = mapOf(before.id to resolvedRelations),
        )

        val compared = tracker.acceptFullSnapshot(snapshot(resolvedRelations))
            as SafShadowCanonicalProjectionGateResult.Compared

        assertTrue(compared.equivalence.fullyEquivalent)
        assertTrue(compared.equivalence.diff.changes.isEmpty())
        assertTrue(compared.equivalence.projection.unresolvedAspectsByStableObjectKey.isEmpty())
    }

    @Test
    fun deferredNewObjectClearsHistoricalAllAspectDebtWhenLaterResolved() {
        val existing = song("existing")
        val added = song("added").copy(title = "Added")
        val observed = entry(added)
        val tracker = SafShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(existing))

        // First pass observes membership but playback/provider serialization defers heavy probe.
        tracker.recordDelta(
            verifyPlan = verifyPlan(added = listOf(observed)),
            resolvedSongsByStableObjectKey = emptyMap(),
        )

        // Release pass sees the same added row and successfully resolves it.
        tracker.recordDelta(
            verifyPlan = verifyPlan(added = listOf(observed)),
            resolvedSongsByStableObjectKey = mapOf(added.id to added),
        )

        val compared = tracker.acceptFullSnapshot(snapshot(existing, added))
            as SafShadowCanonicalProjectionGateResult.Compared

        assertTrue(compared.equivalence.fullyEquivalent)
        assertTrue(compared.equivalence.diff.changes.isEmpty())
        assertTrue(compared.equivalence.projection.unresolvedAspectsByStableObjectKey.isEmpty())
    }

    @Test
    fun missingProbeResultCannotMasqueradeAsCanonicalEquality() {
        val before = song("doc-1").copy(title = "Before")
        val observed = entry(before).copy(
            sizeBytes = before.sizeBytes + 1L,
            lastModifiedMs = before.dateModifiedMs + 1L,
        )
        val expected = before.copy(
            title = "After",
            sizeBytes = observed.sizeBytes,
            dateModifiedMs = observed.lastModifiedMs,
        )
        val tracker = SafShadowCanonicalProjectionTracker()
        tracker.acceptFullSnapshot(snapshot(before))
        tracker.recordDelta(
            verifyPlan = verifyPlan(changed = listOf(observed)),
            resolvedSongsByStableObjectKey = emptyMap(),
        )

        val compared = tracker.acceptFullSnapshot(snapshot(expected))
            as SafShadowCanonicalProjectionGateResult.Compared

        assertFalse(compared.equivalence.fullyEquivalent)
        assertTrue(
            DeviceShadowCanonicalAspect.TAG_METADATA in
                compared.equivalence.diff.changes.single().aspects,
        )
        val unresolved =
            compared.equivalence.projection.unresolvedAspectsByStableObjectKey
                .getValue(before.id)
        assertTrue(DeviceShadowCanonicalAspect.TAG_METADATA in unresolved)
        assertTrue(DeviceShadowCanonicalAspect.EXTERNAL_LYRICS in unresolved)
    }

    private fun song(key: String): Song = SongFixtures.song(key).copy(
        mediaUri = "content://provider/document/$key",
        fileName = "$key.flac",
        folderPath = "Album",
        filePath = "Album/$key.flac",
        sizeBytes = 1_234L,
        dateModifiedMs = 5_678L,
        externalLyricsSignature = "lyrics:v1",
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
        vararg songs: Song,
    ) = SafShadowCanonicalCatalog.snapshot(
        songs = songs.toList(),
        context = context,
    )

    private fun verifyPlan(
        added: List<SafTreeMetadataEntry> = emptyList(),
        changed: List<SafTreeMetadataEntry> = emptyList(),
        unknown: List<SafTreeMetadataEntry> = emptyList(),
        removedStableObjectKeys: Set<String> = emptySet(),
    ) = SafFastVerifyPlan(
        added = added,
        changed = changed,
        unknownFingerprint = unknown,
        removedStableObjectKeys = removedStableObjectKeys,
        unchangedCount = 0,
        removalSuppressedCount = 0,
        discoveryReport = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.SAF_TREE,
                completeness = DiscoveryCompleteness.COMPLETE,
            ),
        ),
    )
}
