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

    suspend fun observeFoldersMetadata(
        treeUris: List<Uri>,
    ): SafTreeMetadataSnapshot = when (treeUris.size) {
        0 -> SafTreeMetadataSnapshot(
            entries = emptyList(),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.UNAVAILABLE,
                    detail = "no-saf-trees",
                ),
            ),
        )
        1 -> observeFolderMetadata(treeUris.first())
        else -> {
            val entriesByKey = LinkedHashMap<String, com.mica.music.data.scanner.SafTreeMetadataEntry>()
            val videos = mutableListOf<com.mica.music.data.scanner.VideoCoverFile>()
            val statuses = mutableListOf<DiscoveryPartitionStatus>()
            var providerQueries = 0
            var directQueries = 0
            var fallbackListings = 0
            var wallTimeMs = 0L
            treeUris.forEach { uri ->
                val sub = observeFolderMetadata(uri)
                sub.entries.forEach { entry ->
                    if (entry.stableObjectKey !in entriesByKey) entriesByKey[entry.stableObjectKey] = entry
                }
                videos += sub.videoCovers
                statuses += sub.discoveryReport.partitions[DiscoveryPartitions.SAF_TREE]
                    ?: DiscoveryPartitionStatus(
                        partitionKey = DiscoveryPartitions.SAF_TREE,
                        completeness = sub.discoveryReport.aggregate,
                    )
                providerQueries += sub.observationStats.providerQueryCount
                directQueries += sub.observationStats.directQueryCount
                fallbackListings += sub.observationStats.fallbackListingCount
                wallTimeMs += sub.observationStats.wallTimeMs
            }
            val completeness = when {
                statuses.all { it.completeness == DiscoveryCompleteness.COMPLETE } ->
                    DiscoveryCompleteness.COMPLETE
                statuses.all { it.completeness == DiscoveryCompleteness.UNAVAILABLE } ->
                    DiscoveryCompleteness.UNAVAILABLE
                else -> DiscoveryCompleteness.PARTIAL
            }
            SafTreeMetadataSnapshot(
                entries = entriesByKey.values.toList(),
                discoveryReport = DiscoveryReport.of(
                    DiscoveryPartitionStatus(
                        partitionKey = DiscoveryPartitions.SAF_TREE,
                        completeness = completeness,
                    ),
                ),
                videoCovers = videos,
                observationStats = com.mica.music.data.scanner.SafTreeMetadataObservationStats(
                    providerQueryCount = providerQueries,
                    directQueryCount = directQueries,
                    fallbackListingCount = fallbackListings,
                    wallTimeMs = wallTimeMs,
                ),
            )
        }
    }

    suspend fun resolveFolderPathsForMediaStoreUris(
        treeUri: Uri,
        mediaStoreUris: Set<String>,
    ): Set<String>? = null

    suspend fun resolveFoldersPathsForMediaStoreUris(
        treeUris: List<Uri>,
        mediaStoreUris: Set<String>,
    ): Set<String>? = when (treeUris.size) {
        1 -> resolveFolderPathsForMediaStoreUris(treeUris.first(), mediaStoreUris)
        else -> null
    }

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

    suspend fun observeFoldersMetadataTargets(
        treeUris: List<Uri>,
        folderPaths: Set<String>,
    ): SafTargetedMetadataSnapshot = when (treeUris.size) {
        1 -> observeFolderMetadataTargets(treeUris.first(), folderPaths)
        else -> {
            val requested = folderPaths.mapTo(linkedSetOf()) { it.trim('/') }
            val full = observeFoldersMetadata(treeUris)
            val complete = full.discoveryReport.isComplete(DiscoveryPartitions.SAF_TREE)
            SafTargetedMetadataSnapshot(
                entries = full.entries.filter { it.folderPath in requested },
                videoCovers = full.videoCovers.filter { it.folderPath in requested },
                requestedFolderPaths = requested,
                completeFolderPaths = if (complete) requested else emptySet(),
                failedFolderPaths = if (complete) emptySet() else requested,
                observationStats = full.observationStats,
            )
        }
    }

    suspend fun observeFolderMetadataTargetsForInitialSync(
        treeUri: Uri,
        folderPaths: Set<String>,
    ): SafTargetedMetadataSnapshot = observeFolderMetadataTargets(treeUri, folderPaths)

    suspend fun observeFoldersMetadataTargetsForInitialSync(
        treeUris: List<Uri>,
        folderPaths: Set<String>,
    ): SafTargetedMetadataSnapshot = observeFoldersMetadataTargets(treeUris, folderPaths)

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

    suspend fun verifyFoldersObjectsMissing(
        treeUris: List<Uri>,
        songs: Collection<Song>,
        startCursor: Int = 0,
        budget: SafMissingVerificationBudget = SafMissingVerificationBudget.Default,
    ): SafIndependentMissingVerificationResult = when (treeUris.size) {
        1 -> verifyFolderObjectsMissing(treeUris.first(), songs, startCursor, budget)
        else -> SafIndependentMissingVerificationResult(
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
    }

    suspend fun scanFolder(
        treeUri: Uri,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult

    suspend fun scanFolders(
        treeUris: List<Uri>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult = when (treeUris.size) {
        0 -> ScanResult(
            songs = emptyList(),
            totalSizeMb = 0,
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.UNAVAILABLE,
                    detail = "no-saf-trees",
                ),
            ),
        )
        1 -> scanFolder(
            treeUri = treeUris.first(),
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = forceRefreshLyrics,
            forceRefreshArtwork = forceRefreshArtwork,
            onLyricsBatch = onLyricsBatch,
        )
        else -> {
            val songsById = LinkedHashMap<String, Song>()
            val videos = mutableListOf<com.mica.music.data.scanner.VideoCoverFile>()
            val statuses = mutableListOf<DiscoveryPartitionStatus>()
            var totalSizeMb = 0
            treeUris.forEach { uri ->
                val result = scanFolder(
                    treeUri = uri,
                    cachedSongs = cachedSongs,
                    onProgress = onProgress,
                    forceRefreshLyrics = forceRefreshLyrics,
                    forceRefreshArtwork = forceRefreshArtwork,
                    onLyricsBatch = onLyricsBatch,
                )
                result.songs.forEach { song ->
                    if (song.id !in songsById) songsById[song.id] = song
                }
                videos += result.folderVideoFiles
                totalSizeMb += result.totalSizeMb
                statuses += result.discoveryReport.partitions[DiscoveryPartitions.SAF_TREE]
                    ?: DiscoveryPartitionStatus(
                        partitionKey = DiscoveryPartitions.SAF_TREE,
                        completeness = result.discoveryReport.aggregate,
                    )
            }
            val completeness = when {
                statuses.all { it.completeness == DiscoveryCompleteness.COMPLETE } ->
                    DiscoveryCompleteness.COMPLETE
                statuses.all { it.completeness == DiscoveryCompleteness.UNAVAILABLE } ->
                    DiscoveryCompleteness.UNAVAILABLE
                else -> DiscoveryCompleteness.PARTIAL
            }
            ScanResult(
                songs = songsById.values.toList(),
                totalSizeMb = totalSizeMb,
                discoveryReport = DiscoveryReport.of(
                    DiscoveryPartitionStatus(
                        partitionKey = DiscoveryPartitions.SAF_TREE,
                        completeness = completeness,
                    ),
                ),
                folderVideoFiles = videos,
            )
        }
    }

    suspend fun scanFoldersWithExcludedDirectories(
        treeUris: List<Uri>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
        excludedDirectoriesOverride: List<String>? = null,
    ): ScanResult = scanFolders(
        treeUris = treeUris,
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        forceRefreshLyrics = forceRefreshLyrics,
        forceRefreshArtwork = forceRefreshArtwork,
        onLyricsBatch = onLyricsBatch,
    )

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

    suspend fun scanFoldersForSongs(
        treeUris: List<Uri>,
        songIds: Set<String>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult = when (treeUris.size) {
        1 -> scanFolderForSongs(
            treeUri = treeUris.first(),
            songIds = songIds,
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = forceRefreshLyrics,
            forceRefreshArtwork = forceRefreshArtwork,
            onLyricsBatch = onLyricsBatch,
        )
        else -> scanFolders(
            treeUris = treeUris,
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = forceRefreshLyrics,
            forceRefreshArtwork = forceRefreshArtwork,
            onLyricsBatch = onLyricsBatch,
        )
    }

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

    suspend fun scanFoldersArtworkForSongs(
        treeUris: List<Uri>,
        songIds: Set<String>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
    ): ScanResult = when (treeUris.size) {
        1 -> scanFolderArtworkForSongs(
            treeUri = treeUris.first(),
            songIds = songIds,
            cachedSongs = cachedSongs,
            onProgress = onProgress,
        )
        else -> scanFoldersForSongs(
            treeUris = treeUris,
            songIds = songIds,
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = false,
            forceRefreshArtwork = true,
            onLyricsBatch = null,
        )
    }
}
