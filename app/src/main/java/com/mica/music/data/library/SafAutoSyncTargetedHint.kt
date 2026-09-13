package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.scanner.DiscoveryCompleteness
import com.mica.music.data.scanner.DiscoveryPartitionStatus
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.DiscoveryReport
import com.mica.music.data.scanner.SafTargetedMetadataSnapshot
import com.mica.music.data.scanner.SafTreeMetadataEntry
import com.mica.music.data.scanner.SafTreeMetadataSnapshot

internal object SafAutoSyncTargetedHintPolicy {
    fun shouldAttempt(request: LibraryOperationRequest.AutoSync): Boolean {
        val causes = request.coalescedCauses
        return !request.mediaStoreHintIncomplete &&
            request.mediaStoreUriHints.isNotEmpty() &&
            causes.isNotEmpty() &&
            causes.all { cause ->
                cause == LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY ||
                    cause == LibraryOperationCause.MEDIASTORE_FILES_DIRTY
            }
    }
}

/**
 * Converts an authoritative folder-scoped observation into the logical whole-library snapshot used
 * by the existing SAF planner. Untouched folders are frozen from the current catalog; requested
 * folders are replaced entirely by the fresh SAF observation, so deletion inside a targeted folder
 * remains authoritative while unrelated folders can never be deleted by the fast path.
 */
internal object SafTargetedInitialSnapshotComposer {
    fun compose(
        currentSongs: List<Song>,
        targeted: SafTargetedMetadataSnapshot,
        targetFolderPaths: Set<String>,
    ): SafTreeMetadataSnapshot? {
        if (targetFolderPaths.isEmpty() || !targeted.isComplete) return null
        if (!targeted.requestedFolderPaths.containsAll(targetFolderPaths)) return null
        val targetFolders = targetFolderPaths.mapTo(linkedSetOf()) { it.trim('/') }
        val observedKeys = targeted.entries.mapTo(hashSetOf(), SafTreeMetadataEntry::stableObjectKey)
        val frozen = currentSongs.asSequence()
            .filter { song ->
                song.folderPath.trim('/') !in targetFolders && song.id !in observedKeys
            }
            .map(::frozenEntry)
            .toList()
        return SafTreeMetadataSnapshot(
            entries = frozen + targeted.entries,
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                    detail = "targeted-mediastore-hint:${targetFolders.size}",
                ),
            ),
            // Only targeted folders are freshly authoritative for sidecar inventory. The pipeline
            // uses changedFoldersWithin() in this mode, so absence outside these folders is ignored.
            videoCovers = targeted.videoCovers,
            observationStats = targeted.observationStats,
        )
    }

    private fun frozenEntry(song: Song): SafTreeMetadataEntry = SafTreeMetadataEntry(
        stableObjectKey = song.id,
        mediaUri = song.mediaUri,
        fileName = song.fileName,
        folderPath = song.folderPath,
        filePath = song.filePath,
        mimeType = song.metadata.playbackMimeType,
        sizeBytes = song.sizeBytes,
        lastModifiedMs = song.dateModifiedMs,
        externalLyricsSignature = song.externalLyricsSignature,
        probeDraft = null,
    )
}
