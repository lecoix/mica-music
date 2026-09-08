package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.scanner.DiscoveryCompleteness
import com.mica.music.data.scanner.DiscoveryPartitionStatus
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.DiscoveryReport
import com.mica.music.data.scanner.SafFastVerifyPlan
import com.mica.music.data.scanner.SafTreeMetadataEntry
import com.mica.music.data.scanner.SafTreeMetadataSnapshot
import com.mica.music.data.scanner.VideoCoverFile
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafShadowRelationRematcherTest {

    @Test
    fun changedAudioWithoutAnyVideoRelationDoesNotCreateRelationWork() {
        val song = song("plain")
        val changed = entry(song).copy(
            sizeBytes = song.sizeBytes + 1L,
            lastModifiedMs = song.dateModifiedMs + 1L,
        )

        val affected = SafShadowRelationRematcher.potentialAffectedFolders(
            currentSongs = listOf(song),
            audioWorkEntries = listOf(changed),
            removedStableObjectKeys = emptySet(),
            changedVideoFolderPaths = emptySet(),
            observedVideoFolderPaths = emptySet(),
        )

        assertTrue(affected.isEmpty())
    }

    @Test
    fun resolvedChangedAudioRematchesVideoCoverAndMusicVideoInsideAffectedFolder() {
        val before = song("track").copy(album = "track")
        val changed = entry(before).copy(
            sizeBytes = before.sizeBytes + 1L,
            lastModifiedMs = before.dateModifiedMs + 1L,
        )
        val resolvedAudio = before.copy(
            title = "Updated",
            sizeBytes = changed.sizeBytes,
            dateModifiedMs = changed.lastModifiedMs,
        )
        val video = VideoCoverFile(
            uri = "content://provider/document/track.mp4",
            folderPath = before.folderPath,
            baseName = "track",
            sizeBytes = 9_000L,
            lastModifiedMs = 10_000L,
        )
        val snapshot = snapshot(
            entries = listOf(changed),
            videos = listOf(video),
        )
        val affected = SafShadowRelationRematcher.potentialAffectedFolders(
            currentSongs = listOf(before),
            audioWorkEntries = listOf(changed),
            removedStableObjectKeys = emptySet(),
            changedVideoFolderPaths = setOf(before.folderPath),
            observedVideoFolderPaths = setOf(before.folderPath),
        )

        val result = SafShadowRelationRematcher.rematch(
            snapshot = snapshot,
            currentSongs = listOf(before),
            audioWorkEntries = listOf(changed),
            removedStableObjectKeys = emptySet(),
            resolvedAudioSongsByStableObjectKey = mapOf(before.id to resolvedAudio),
            affectedFolderPaths = affected,
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(setOf(before.folderPath), result.resolvedFolderPaths)
        assertTrue(result.unresolvedStableObjectKeys.isEmpty())
        val rematched = result.resolvedSongsByStableObjectKey.getValue(before.id)
        assertEquals(video.uri, rematched.videoCoverUri)
        assertEquals(video.revision, rematched.videoCoverRevision)
        assertEquals(video.uri, rematched.musicVideoUri)
        assertEquals(video.revision, rematched.musicVideoRevision)
    }

    @Test
    fun matchedWeakVideoResourceKeepsRelationAspectsUnresolved() {
        val before = song("track").copy(album = "track")
        val changed = entry(before).copy(
            sizeBytes = before.sizeBytes + 1L,
            lastModifiedMs = before.dateModifiedMs + 1L,
        )
        val resolvedAudio = before.copy(
            sizeBytes = changed.sizeBytes,
            dateModifiedMs = changed.lastModifiedMs,
        )
        val weakVideo = VideoCoverFile(
            uri = "content://provider/document/track.mp4",
            folderPath = before.folderPath,
            baseName = "track",
            sizeBytes = 9_000L,
            lastModifiedMs = 0L,
        )

        val result = SafShadowRelationRematcher.rematch(
            snapshot = snapshot(listOf(changed), listOf(weakVideo)),
            currentSongs = listOf(before),
            audioWorkEntries = listOf(changed),
            removedStableObjectKeys = emptySet(),
            resolvedAudioSongsByStableObjectKey = mapOf(before.id to resolvedAudio),
            affectedFolderPaths = setOf(before.folderPath),
        )

        assertTrue(result.issues.isEmpty())
        assertEquals(setOf(before.folderPath), result.resolvedFolderPaths)
        assertEquals(setOf(before.id), result.unresolvedStableObjectKeys)
        val rematched = result.resolvedSongsByStableObjectKey.getValue(before.id)
        assertEquals(weakVideo.uri, rematched.videoCoverUri)
        assertEquals(weakVideo.uri, rematched.musicVideoUri)
    }

    @Test
    fun unresolvedChangedAudioQuarantinesWholeAffectedFolderRelations() {
        val before = song("track").copy(
            album = "track",
            videoCoverUri = "content://provider/document/old.mp4",
            videoCoverRevision = "old-cover",
            musicVideoUri = "content://provider/document/old.mp4",
            musicVideoRevision = "old-mv",
        )
        val changed = entry(before).copy(
            sizeBytes = before.sizeBytes + 1L,
            lastModifiedMs = before.dateModifiedMs + 1L,
        )
        val video = VideoCoverFile(
            uri = "content://provider/document/track.mp4",
            folderPath = before.folderPath,
            baseName = "track",
        )

        val result = SafShadowRelationRematcher.rematch(
            snapshot = snapshot(listOf(changed), listOf(video)),
            currentSongs = listOf(before),
            audioWorkEntries = listOf(changed),
            removedStableObjectKeys = emptySet(),
            resolvedAudioSongsByStableObjectKey = emptyMap(),
            affectedFolderPaths = setOf(before.folderPath),
        )

        assertTrue(result.resolvedSongsByStableObjectKey.isEmpty())
        assertEquals(setOf(before.id), result.unresolvedStableObjectKeys)
        assertEquals(
            SafShadowRelationIssueKind.AUDIO_WORK_UNRESOLVED,
            result.issues.single().kind,
        )
    }

    @Test
    fun postWalkVideoRevisionChangeDiscardsProvisionalRelations() {
        val song = song("track").copy(album = "track")
        val entry = entry(song)
        val initialVideo = VideoCoverFile(
            uri = "content://provider/document/track.mp4",
            folderPath = song.folderPath,
            baseName = "track",
            sizeBytes = 100L,
            lastModifiedMs = 200L,
        )
        val provisional = SafShadowRelationRematcher.rematch(
            snapshot = snapshot(listOf(entry), listOf(initialVideo)),
            currentSongs = listOf(song),
            audioWorkEntries = emptyList(),
            removedStableObjectKeys = emptySet(),
            resolvedAudioSongsByStableObjectKey = emptyMap(),
            affectedFolderPaths = setOf(song.folderPath),
        )
        val changedVideo = initialVideo.copy(lastModifiedMs = 201L)

        val validated = SafShadowRelationPostValidator.validate(
            initialSnapshot = snapshot(listOf(entry), listOf(initialVideo)),
            postSnapshot = snapshot(listOf(entry), listOf(changedVideo)),
            provisional = provisional,
        )

        assertTrue(validated.resolvedSongsByStableObjectKey.isEmpty())
        assertEquals(setOf(song.id), validated.unresolvedStableObjectKeys)
        assertEquals(
            SafShadowRelationIssueKind.FOLDER_OBSERVATION_CHANGED,
            validated.issues.single().kind,
        )
    }

    private fun song(key: String): Song = SongFixtures.song(key).copy(
        mediaUri = "content://provider/document/$key.flac",
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
        entries: List<SafTreeMetadataEntry>,
        videos: List<VideoCoverFile>,
    ) = SafTreeMetadataSnapshot(
        entries = entries,
        discoveryReport = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.SAF_TREE,
                completeness = DiscoveryCompleteness.COMPLETE,
            ),
        ),
        videoCovers = videos,
    )

    private fun verifyPlan(
        added: List<SafTreeMetadataEntry> = emptyList(),
        changed: List<SafTreeMetadataEntry> = emptyList(),
        unknown: List<SafTreeMetadataEntry> = emptyList(),
        removed: Set<String> = emptySet(),
    ) = SafFastVerifyPlan(
        added = added,
        changed = changed,
        unknownFingerprint = unknown,
        removedStableObjectKeys = removed,
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
