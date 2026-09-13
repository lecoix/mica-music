package com.mica.music.data

import android.net.Uri
import com.mica.music.data.scanner.DiscoveryCompleteness
import com.mica.music.data.scanner.DiscoveryPartitionStatus
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.DiscoveryReport
import com.mica.music.data.scanner.SafIndependentMissingVerificationResult
import com.mica.music.data.scanner.SafMissingVerificationBudget
import com.mica.music.data.scanner.SafTargetedMetadataSnapshot
import com.mica.music.data.scanner.SafTreeMetadataSnapshot
import com.mica.music.data.scanner.ScanResult

internal interface LibraryScanner {
    suspend fun scanDevice(
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult

    suspend fun scanDeviceForSongs(
        songIds: Set<String>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult = scanDevice(
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        forceRefreshLyrics = forceRefreshLyrics,
        forceRefreshArtwork = forceRefreshArtwork,
        onLyricsBatch = onLyricsBatch,
    )

    suspend fun observeFolderMetadata(
        treeUri: Uri,
    ): SafTreeMetadataSnapshot = SafTreeMetadataSnapshot(
        entries = emptyList(),
        discoveryReport = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.SAF_TREE,
                completeness = DiscoveryCompleteness.UNAVAILABLE,
                detail = "folder-metadata-observer-not-implemented",
            ),
        ),
    )

    suspend fun resolveFolderPathsForMediaStoreUris(
        treeUri: Uri,
        mediaStoreUris: Set<String>,
    ): Set<String>? = null

    suspend fun observeFolderMetadataTargets(
        treeUri: Uri,
        folderPaths: Set<String>,
    ): SafTargetedMetadataSnapshot {
        val requested = folderPaths.mapTo(linkedSetOf()) { it.trim('/') }
        val full = observeFolderMetadata(treeUri)
        val complete = full.discoveryReport.isComplete(DiscoveryPartitions.SAF_TREE)
        return SafTargetedMetadataSnapshot(
            entries = full.entries.filter { it.folderPath in requested },
            videoCovers = full.videoCovers.filter { it.folderPath in requested },
            requestedFolderPaths = requested,
            completeFolderPaths = if (complete) requested else emptySet(),
            failedFolderPaths = if (complete) emptySet() else requested,
            observationStats = full.observationStats,
        )
    }

    suspend fun observeFolderMetadataTargetsForInitialSync(
        treeUri: Uri,
        folderPaths: Set<String>,
    ): SafTargetedMetadataSnapshot = observeFolderMetadataTargets(treeUri, folderPaths)

    /** Independent object-level recheck used only after mass-deletion quarantine. */
    suspend fun verifyFolderObjectsMissing(
        treeUri: Uri,
        songs: Collection<Song>,
        startCursor: Int = 0,
        budget: SafMissingVerificationBudget = SafMissingVerificationBudget.Default,
    ): SafIndependentMissingVerificationResult = SafIndependentMissingVerificationResult(
        verifiedMissingStableObjectKeys = emptySet(),
        presentStableObjectKeys = emptySet(),
        indeterminateStableObjectKeys = songs
            .distinctBy(Song::id)
            .sortedBy(Song::id)
            .drop(startCursor.coerceAtLeast(0))
            .take(budget.maxObjects)
            .mapTo(linkedSetOf(), Song::id),
        nextCursor = (startCursor + minOf(songs.size, budget.maxObjects)).coerceAtMost(songs.size),
        hasMore = startCursor + budget.maxObjects < songs.size,
    )

    suspend fun scanFolder(
        treeUri: Uri,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult

    suspend fun scanFolderForSongs(
        treeUri: Uri,
        songIds: Set<String>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult = scanFolder(
        treeUri = treeUri,
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        forceRefreshLyrics = forceRefreshLyrics,
        forceRefreshArtwork = forceRefreshArtwork,
        onLyricsBatch = onLyricsBatch,
    )

    /**
     * Artwork-only targeted refresh for already-published FOLDER songs. Production overrides this
     * with direct object probes; the default preserves compatibility for test/fake scanners.
     */
    suspend fun scanFolderArtworkForSongs(
        treeUri: Uri,
        songIds: Set<String>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
    ): ScanResult = scanFolderForSongs(
        treeUri = treeUri,
        songIds = songIds,
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        forceRefreshLyrics = false,
        forceRefreshArtwork = true,
        onLyricsBatch = null,
    )
}
