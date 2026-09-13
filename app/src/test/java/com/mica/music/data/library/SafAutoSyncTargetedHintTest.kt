package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.data.scanner.SafFastVerifyPlanner
import com.mica.music.data.scanner.SafTargetedMetadataSnapshot
import com.mica.music.data.scanner.SafTreeMetadataEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafAutoSyncTargetedHintTest {
    @Test
    fun pureMediaStoreHintsAreEligible() {
        assertTrue(
            SafAutoSyncTargetedHintPolicy.shouldAttempt(
                LibraryOperationRequest.AutoSync(
                    cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                    coalescedCauses = setOf(
                        LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                        LibraryOperationCause.MEDIASTORE_FILES_DIRTY,
                    ),
                    mediaStoreUriHints = setOf("content://media/external/audio/media/1"),
                ),
            ),
        )
    }

    @Test
    fun mixedCauseIncompleteOrMissingHintsDisableTargetedPath() {
        val base = LibraryOperationRequest.AutoSync(
            cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            mediaStoreUriHints = setOf("content://media/external/audio/media/1"),
        )
        assertFalse(
            SafAutoSyncTargetedHintPolicy.shouldAttempt(
                base.copy(
                    coalescedCauses = setOf(
                        LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                        LibraryOperationCause.FOREGROUND_CATCH_UP,
                    ),
                ),
            ),
        )
        assertFalse(
            SafAutoSyncTargetedHintPolicy.shouldAttempt(
                base.copy(mediaStoreHintIncomplete = true),
            ),
        )
        assertFalse(
            SafAutoSyncTargetedHintPolicy.shouldAttempt(
                base.copy(mediaStoreUriHints = emptySet()),
            ),
        )
    }

    @Test
    fun targetFolderDeletionNeverDeletesFrozenOutsideFolder() {
        val target = "Album/A"
        val inside = song("inside", target)
        val outside = song("outside", "Album/B")
        val targeted = SafTargetedMetadataSnapshot(
            entries = emptyList(),
            requestedFolderPaths = setOf(target),
            completeFolderPaths = setOf(target),
        )

        val snapshot = SafTargetedInitialSnapshotComposer.compose(
            currentSongs = listOf(inside, outside),
            targeted = targeted,
            targetFolderPaths = setOf(target),
        )!!
        val plan = SafFastVerifyPlanner.plan(
            snapshot = snapshot,
            cachedSongs = listOf(inside, outside),
        )

        assertEquals(setOf("inside"), plan.removedStableObjectKeys)
        assertEquals(1, plan.unchangedCount)
        assertEquals(listOf("outside"), snapshot.entries.map { it.stableObjectKey })
    }

    @Test
    fun targetedReplacementCanAddAndRemoveOnlyInsideRequestedFolder() {
        val target = "Album/A"
        val oldInside = song("old", target)
        val outside = song("outside", "Album/B")
        val newInside = entry("new", target)
        val targeted = SafTargetedMetadataSnapshot(
            entries = listOf(newInside),
            requestedFolderPaths = setOf(target),
            completeFolderPaths = setOf(target),
        )

        val snapshot = SafTargetedInitialSnapshotComposer.compose(
            currentSongs = listOf(oldInside, outside),
            targeted = targeted,
            targetFolderPaths = setOf(target),
        )!!
        val plan = SafFastVerifyPlanner.plan(
            snapshot = snapshot,
            cachedSongs = listOf(oldInside, outside),
        )

        assertEquals(listOf("new"), plan.added.map { it.stableObjectKey })
        assertEquals(setOf("old"), plan.removedStableObjectKeys)
        assertEquals(1, plan.unchangedCount)
        assertTrue("outside" in snapshot.entries.map { it.stableObjectKey })
    }

    @Test
    fun incompleteTargetObservationCannotBecomeWholeTreeAuthority() {
        val target = "Album/A"
        val targeted = SafTargetedMetadataSnapshot(
            entries = emptyList(),
            requestedFolderPaths = setOf(target),
            completeFolderPaths = emptySet(),
            failedFolderPaths = setOf(target),
        )
        assertNull(
            SafTargetedInitialSnapshotComposer.compose(
                currentSongs = listOf(song("inside", target)),
                targeted = targeted,
                targetFolderPaths = setOf(target),
            ),
        )
    }

    private fun song(id: String, folder: String): Song = Song(
        id = id,
        title = id,
        artist = "artist",
        album = "album",
        durationSec = 60,
        metadata = metadata(),
        albumArtUri = null,
        coverColorArgb = 0,
        mediaUri = "content://com.android.externalstorage.documents/document/$id",
        fileName = "$id.mp3",
        sizeBytes = 1_000L,
        folderPath = folder,
        filePath = "/storage/emulated/0/Music/$folder/$id.mp3",
        dateModifiedMs = 2_000L,
    )

    private fun entry(id: String, folder: String): SafTreeMetadataEntry = SafTreeMetadataEntry(
        stableObjectKey = id,
        mediaUri = "content://com.android.externalstorage.documents/document/$id",
        fileName = "$id.mp3",
        folderPath = folder,
        filePath = "/storage/emulated/0/Music/$folder/$id.mp3",
        mimeType = "audio/mpeg",
        sizeBytes = 1_000L,
        lastModifiedMs = 2_000L,
        externalLyricsSignature = "",
    )

    private fun metadata() = TrackMetadata(
        containerName = "MP3",
        sampleRateHz = 44_100,
        bitsPerSample = 16,
        bitrateKbps = 320,
        channelCount = 2,
        playbackMimeType = "audio/mpeg",
    )
}