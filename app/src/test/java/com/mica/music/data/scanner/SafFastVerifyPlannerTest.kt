package com.mica.music.data.scanner

import com.mica.music.data.Song
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafFastVerifyPlannerTest {

    @Test
    fun reliableMatchingMetadataIsNoOp() {
        val song = SongFixtures.song("doc-1").copy(
            mediaUri = "content://tree/doc-1",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
            externalLyricsSignature = "lyrics:v1",
        )

        val plan = SafFastVerifyPlanner.plan(
            snapshot = snapshot(entry(song)),
            cachedSongs = listOf(song),
        )

        assertTrue(plan.isNoOp)
        assertEquals(1, plan.unchangedCount)
        assertTrue(plan.added.isEmpty())
        assertTrue(plan.changed.isEmpty())
        assertTrue(plan.unknownFingerprint.isEmpty())
        assertTrue(plan.removedStableObjectKeys.isEmpty())
    }

    @Test
    fun suppliedCatalogLookupAvoidsFallbackMapWithoutChangingSemantics() {
        val song = SongFixtures.song("doc-indexed").copy(
            mediaUri = "content://tree/doc-indexed",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
        )
        var lookupCount = 0

        val plan = SafFastVerifyPlanner.plan(
            snapshot = snapshot(entry(song)),
            cachedSongs = listOf(song),
            cachedSongById = { id ->
                lookupCount += 1
                song.takeIf { it.id == id }
            },
        )

        assertTrue(plan.isNoOp)
        assertEquals(1, lookupCount)
        assertEquals(1, plan.unchangedCount)
    }

    @Test
    fun metadataAndSidecarChangesBecomeChangedWork() {
        val song = SongFixtures.song("doc-1").copy(
            mediaUri = "content://tree/doc-1",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
            externalLyricsSignature = "lyrics:v1",
        )
        val moved = entry(song).copy(
            folderPath = "Renamed",
            filePath = "Renamed/song.flac",
            externalLyricsSignature = "lyrics:v2",
        )

        val plan = SafFastVerifyPlanner.plan(
            snapshot = snapshot(moved),
            cachedSongs = listOf(song),
        )

        assertFalse(plan.isNoOp)
        assertEquals(listOf("doc-1"), plan.changed.map(SafTreeMetadataEntry::stableObjectKey))
        assertTrue(plan.unknownFingerprint.isEmpty())
    }

    @Test
    fun observableSidecarChangeIsImmediateWorkEvenWhenFingerprintIsUnknown() {
        val song = SongFixtures.song("doc-1").copy(
            mediaUri = "content://tree/doc-1",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
            externalLyricsSignature = "lyrics:v1",
        )
        val unknownButSidecarChanged = entry(song).copy(
            lastModifiedMs = 0L,
            externalLyricsSignature = "lyrics:v2",
        )

        val plan = SafFastVerifyPlanner.plan(
            snapshot = snapshot(unknownButSidecarChanged),
            cachedSongs = listOf(song),
        )

        assertEquals(
            listOf("doc-1"),
            plan.changed.map(SafTreeMetadataEntry::stableObjectKey),
        )
        assertTrue(plan.unknownFingerprint.isEmpty())
    }

    @Test
    fun unreliableFingerprintRequiresVerifyInsteadOfPretendingUnchanged() {
        val song = SongFixtures.song("doc-1").copy(
            mediaUri = "content://tree/doc-1",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
        )
        val unknown = entry(song).copy(lastModifiedMs = 0L)

        val plan = SafFastVerifyPlanner.plan(
            snapshot = snapshot(unknown),
            cachedSongs = listOf(song),
        )

        assertFalse(plan.isNoOp)
        assertEquals(listOf("doc-1"), plan.unknownFingerprint.map(SafTreeMetadataEntry::stableObjectKey))
        assertTrue(plan.changed.isEmpty())
        assertEquals(0, plan.unchangedCount)
    }

    @Test
    fun excludedProviderObjectIsObservedButNeverBecomesAddedWork() {
        val excluded = SongFixtures.song("doc-excluded").copy(
            mediaUri = "content://tree/doc-excluded",
            fileName = "excluded.flac",
            folderPath = "Album",
            filePath = "Album/excluded.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
        )

        val plan = SafFastVerifyPlanner.plan(
            snapshot = snapshot(entry(excluded)),
            cachedSongs = emptyList(),
            excludedStableObjectKeys = setOf(excluded.id),
        )

        assertTrue(plan.isNoOp)
        assertTrue(plan.added.isEmpty())
        assertTrue(plan.changed.isEmpty())
        assertTrue(plan.unknownFingerprint.isEmpty())
        assertTrue(plan.removedStableObjectKeys.isEmpty())
    }

    @Test
    fun removalRequiresCompleteSafTreeInventory() {
        val kept = SongFixtures.song("doc-kept").copy(
            mediaUri = "content://tree/doc-kept",
            fileName = "kept.flac",
            folderPath = "Album",
            filePath = "Album/kept.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
        )
        val missing = SongFixtures.song("doc-missing").copy(
            mediaUri = "content://tree/doc-missing",
        )

        val complete = SafFastVerifyPlanner.plan(
            snapshot = snapshot(entry(kept), completeness = DiscoveryCompleteness.COMPLETE),
            cachedSongs = listOf(kept, missing),
        )
        assertEquals(setOf("doc-missing"), complete.removedStableObjectKeys)
        assertEquals(0, complete.removalSuppressedCount)

        val partial = SafFastVerifyPlanner.plan(
            snapshot = snapshot(entry(kept), completeness = DiscoveryCompleteness.PARTIAL),
            cachedSongs = listOf(kept, missing),
        )
        assertTrue(partial.removedStableObjectKeys.isEmpty())
        assertEquals(1, partial.removalSuppressedCount)
    }

    private fun entry(song: Song): SafTreeMetadataEntry = SafTreeMetadataEntry(
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
        vararg entries: SafTreeMetadataEntry,
        completeness: DiscoveryCompleteness = DiscoveryCompleteness.COMPLETE,
    ): SafTreeMetadataSnapshot = SafTreeMetadataSnapshot(
        entries = entries.toList(),
        discoveryReport = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.SAF_TREE,
                completeness = completeness,
            ),
        ),
    )
}
