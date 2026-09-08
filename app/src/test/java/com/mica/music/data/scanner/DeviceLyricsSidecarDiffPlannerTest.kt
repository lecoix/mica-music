package com.mica.music.data.scanner

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLyricsSidecarDiffPlannerTest {

    @Test
    fun identicalInventorySignatureIsNoChange() {
        val ref = ref("content://media/external/file/10", 100L, 2_000L, "lrc")
        val song = song("ms_1", "track.flac", listOf(ref).externalLyricsSignature())
        val inventory = completeInventory(
            mediaStoreLyricsKey("Music", "track") to listOf(ref),
        )

        val diff = DeviceLyricsSidecarDiffPlanner.plan(listOf(song), inventory)

        assertTrue(diff.changes.isEmpty())
        assertTrue(diff.safeToAdvance)
    }

    @Test
    fun deletedSidecarIsDetectedFromFullInventoryEvenWithoutDeltaRow() {
        val oldRef = ref("content://media/external/file/11", 120L, 3_000L, "lrc")
        val song = song("ms_2", "deleted.flac", listOf(oldRef).externalLyricsSignature())

        val diff = DeviceLyricsSidecarDiffPlanner.plan(
            currentSongs = listOf(song),
            inventory = completeInventory(),
        )

        assertEquals(setOf("ms_2"), diff.affectedSongIds)
        assertEquals("", diff.changes.single().observedSignature)
    }

    @Test
    fun addedOrModifiedSidecarChangesSignature() {
        val oldRef = ref("content://media/external/file/12", 100L, 4_000L, "ttml")
        val modified = oldRef.copy(sizeBytes = 101L, dateModifiedMs = 5_000L)
        val existing = song("ms_3", "modified.flac", listOf(oldRef).externalLyricsSignature())
        val newlyAdded = song("ms_4", "added.flac", "")
        val addedRef = ref("content://media/external/file/13", 20L, 6_000L, "lrc")
        val inventory = completeInventory(
            mediaStoreLyricsKey("Music", "modified") to listOf(modified),
            mediaStoreLyricsKey("Music", "added") to listOf(addedRef),
        )

        val diff = DeviceLyricsSidecarDiffPlanner.plan(listOf(existing, newlyAdded), inventory)

        assertEquals(setOf("ms_3", "ms_4"), diff.affectedSongIds)
        assertTrue(diff.changes.single { it.songId == "ms_3" }.observedSignature.isNotEmpty())
        assertTrue(diff.changes.single { it.songId == "ms_4" }.observedSignature.isNotEmpty())
    }

    @Test
    fun incompleteInventoryCannotConvertMissingRowsIntoDeletion() {
        val ref = ref("content://media/external/file/14", 30L, 7_000L, "lrc")
        val song = song("ms_5", "unsafe.flac", listOf(ref).externalLyricsSignature())
        val inventory = MediaStoreLyricsSidecarInventoryResult(
            refsByLyricsKey = emptyMap(),
            status = DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.MEDIASTORE_LYRICS_SIDECARS,
                completeness = DiscoveryCompleteness.PARTIAL,
                detail = "provider failed",
            ),
        )

        val diff = DeviceLyricsSidecarDiffPlanner.plan(listOf(song), inventory)

        assertTrue(diff.changes.isEmpty())
        assertEquals(setOf("ms_5"), diff.unverifiableSongIds)
        assertFalse(diff.safeToAdvance)
    }

    @Test
    fun existingExternalLyricsWithoutFileIdentityIsUnverifiable() {
        val ref = ref("content://media/external/file/15", 40L, 8_000L, "lrc")
        val song = SongFixtures.song("ms_6").copy(
            fileName = "",
            filePath = "",
            folderPath = "Music",
            externalLyricsSignature = listOf(ref).externalLyricsSignature(),
        )

        val diff = DeviceLyricsSidecarDiffPlanner.plan(
            currentSongs = listOf(song),
            inventory = completeInventory(),
        )

        assertEquals(setOf("ms_6"), diff.unverifiableSongIds)
        assertFalse(diff.safeToAdvance)
    }

    private fun song(
        id: String,
        fileName: String,
        externalLyricsSignature: String,
    ) = SongFixtures.song(id).copy(
        fileName = fileName,
        filePath = mediaStoreReadableFilePath("Music", fileName),
        folderPath = "Music",
        externalLyricsSignature = externalLyricsSignature,
    )

    private fun ref(
        uri: String,
        size: Long,
        modified: Long,
        extension: String,
    ) = ExternalLyricsRef(
        uri = uri,
        sizeBytes = size,
        dateModifiedMs = modified,
        extension = extension,
    )

    private fun completeInventory(
        vararg entries: Pair<String, List<ExternalLyricsRef>>,
    ) = MediaStoreLyricsSidecarInventoryResult(
        refsByLyricsKey = mapOf(*entries),
        status = DiscoveryPartitionStatus(
            partitionKey = DiscoveryPartitions.MEDIASTORE_LYRICS_SIDECARS,
            completeness = DiscoveryCompleteness.COMPLETE,
        ),
    )
}
