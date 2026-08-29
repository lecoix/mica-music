package com.mica.music.data.scanner

import com.mica.music.data.DsdSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreScannerCompatibilityTest {

    @Test
    fun filesFallbackIncludesApeAlongsideDsdFormats() {
        assertTrue("ape" in MediaStoreScanner.FILE_EXTENSION_FALLBACKS)
        assertTrue(MediaStoreScanner.FILE_EXTENSION_FALLBACKS.containsAll(DsdSupport.extensions))
    }

    @Test
    fun mediaStoreDurationClauseKeepsUnknownDurationForAppProbe() {
        val clause = mediaStoreDurationClause(60_000L)

        assertTrue(clause.contains("duration IS NULL"))
        assertTrue(clause.contains("duration <= 0"))
        assertTrue(clause.contains("duration >= 60000"))
    }

    @Test
    fun zeroMinimumDoesNotAddDurationFilter() {
        assertEquals("", mediaStoreDurationClause(0L))
    }

    @Test
    fun postProbeDurationFilterKeepsUnknownButRejectsKnownShortTracks() {
        assertTrue(shouldKeepScannedDuration(durationSec = 0, minDurationMs = 60_000L))
        assertFalse(shouldKeepScannedDuration(durationSec = 59, minDurationMs = 60_000L))
        assertTrue(shouldKeepScannedDuration(durationSec = 60, minDurationMs = 60_000L))
    }

    @Test
    fun fullDiscoveryScanRunsFolderCasingReconciliation() {
        assertTrue(shouldReconcileMediaStoreFolderCasing(emptySet()))
    }

    @Test
    fun targetedMetadataRefreshSkipsFolderCasingReconciliation() {
        assertFalse(shouldReconcileMediaStoreFolderCasing(setOf("ms_42")))
    }

    @Test
    fun folderCasingReconciliationMergesOnlyWhenPhysicalIdentityMatches() {
        val drafts = listOf(
            draft(1L, "QQmusic/song", "a.flac"),
            draft(2L, "qqmusic/song", "b.flac"),
            draft(3L, "QQmusic/song", "c.flac"),
        )
        val sameDirectory = MediaStoreDirectoryIdentity(device = 7L, inode = 99L)

        val reconciled = reconcileMediaStoreFolderCasing(drafts) { sameDirectory }

        assertEquals(listOf("QQmusic/song", "QQmusic/song", "QQmusic/song"), reconciled.map { it.folderPath })
        assertEquals("QQmusic/song/b.flac", reconciled[1].filePath)
    }

    @Test
    fun folderCasingReconciliationPreservesCaseVariantsForDifferentDirectories() {
        val drafts = listOf(
            draft(1L, "QQmusic/song", "a.flac"),
            draft(2L, "qqmusic/song", "b.flac"),
        )

        val reconciled = reconcileMediaStoreFolderCasing(drafts) { draft ->
            if (draft.folderPath.startsWith("QQ")) {
                MediaStoreDirectoryIdentity(device = 7L, inode = 99L)
            } else {
                MediaStoreDirectoryIdentity(device = 7L, inode = 100L)
            }
        }

        assertEquals(listOf("QQmusic/song", "qqmusic/song"), reconciled.map { it.folderPath })
    }

    @Test
    fun folderCasingReconciliationFailsClosedWhenPhysicalIdentityIsUnavailable() {
        val drafts = listOf(
            draft(1L, "QQmusic/song", "a.flac"),
            draft(2L, "qqmusic/song", "b.flac"),
        )

        val reconciled = reconcileMediaStoreFolderCasing(drafts) { draft ->
            if (draft.folderPath.startsWith("QQ")) {
                MediaStoreDirectoryIdentity(device = 7L, inode = 99L)
            } else {
                null
            }
        }

        assertEquals(listOf("QQmusic/song", "qqmusic/song"), reconciled.map { it.folderPath })
    }

    private fun draft(id: Long, folderPath: String, fileName: String): TrackDraft = TrackDraft(
        mediaStoreId = id,
        title = fileName.substringBeforeLast('.'),
        artist = "artist",
        album = "album",
        albumId = 1L,
        durationSec = 60,
        mimeType = "audio/flac",
        displayName = fileName,
        sizeBytes = 1_000L,
        bitrateBpsFromStore = 0,
        mediaUri = "content://media/external/audio/media/$id",
        coverColorArgb = 0,
        folderPath = folderPath,
        filePath = "$folderPath/$fileName",
    )
}
