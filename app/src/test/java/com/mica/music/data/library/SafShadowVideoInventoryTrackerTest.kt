package com.mica.music.data.library

import com.mica.music.data.scanner.VideoCoverFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafShadowVideoInventoryTrackerTest {

    @Test
    fun seededIdenticalInventoryIsNoOp() {
        val tracker = SafShadowVideoInventoryTracker()
        val files = listOf(video("Album", 100L, 200L))

        tracker.seed(files)

        assertTrue(tracker.changedFolders(files).isEmpty())
    }

    @Test
    fun revisionChangeAndRemovalExposeOnlyAffectedFolder() {
        val tracker = SafShadowVideoInventoryTracker()
        val album = video("Album", 100L, 200L)
        val other = video("Other", 300L, 400L)
        tracker.seed(listOf(album, other))

        assertEquals(
            setOf("Album"),
            tracker.changedFolders(listOf(album.copy(lastModifiedMs = 201L), other)),
        )
        assertEquals(
            setOf("Album"),
            tracker.changedFolders(listOf(other)),
        )
    }

    @Test
    fun unseededCurrentRelationFolderIsConservativelyChanged() {
        val tracker = SafShadowVideoInventoryTracker()

        assertEquals(
            setOf("Legacy"),
            tracker.changedFolders(
                files = emptyList(),
                conservativeFoldersWhenUnseeded = setOf("Legacy"),
            ),
        )
    }

    private fun video(
        folder: String,
        size: Long,
        modified: Long,
    ) = VideoCoverFile(
        uri = "content://provider/document/$folder.mp4",
        folderPath = folder,
        baseName = "track",
        sizeBytes = size,
        lastModifiedMs = modified,
    )
}
