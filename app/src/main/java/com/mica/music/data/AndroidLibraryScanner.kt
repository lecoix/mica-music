package com.mica.music.data

import android.content.Context
import android.net.Uri
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.scanner.FolderScanner
import com.mica.music.data.scanner.MediaStoreScanner
import com.mica.music.data.scanner.SafIndependentMissingVerificationResult
import com.mica.music.data.scanner.SafMissingVerificationBudget
import com.mica.music.data.scanner.SafTargetedMetadataSnapshot
import com.mica.music.data.scanner.SafTreeMetadataSnapshot
import com.mica.music.data.scanner.ScanResult

internal class AndroidLibraryScanner(
    private val context: Context,
) : LibraryScanner {
    override suspend fun scanDevice(
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean,
        forceRefreshArtwork: Boolean,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
    ): ScanResult = MediaStoreScanner.scan(
        context = context,
        options = LibraryScanSettings.scanOptions(context).copy(
            forceRefreshLyrics = forceRefreshLyrics,
            forceRefreshArtwork = forceRefreshArtwork,
        ),
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        onLyricsBatch = onLyricsBatch,
    )

    override suspend fun scanDeviceForSongs(
        songIds: Set<String>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean,
        forceRefreshArtwork: Boolean,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
    ): ScanResult = MediaStoreScanner.scan(
        context = context,
        options = LibraryScanSettings.scanOptions(context).copy(
            forceRefreshLyrics = forceRefreshLyrics,
            forceRefreshArtwork = forceRefreshArtwork,
            forceRefreshSongIds = songIds,
            scanOnlySongIds = songIds,
        ),
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        onLyricsBatch = onLyricsBatch,
    )

    override suspend fun observeFolderMetadata(
        treeUri: Uri,
    ): SafTreeMetadataSnapshot = FolderScanner.observeMetadata(
        context = context,
        treeUri = treeUri,
        options = LibraryScanSettings.scanOptions(context),
    )

    override suspend fun resolveFolderPathsForMediaStoreUris(
        treeUri: Uri,
        mediaStoreUris: Set<String>,
    ): Set<String>? = FolderScanner.resolveMediaStoreFolderPaths(
        context = context,
        treeUri = treeUri,
        mediaStoreUris = mediaStoreUris,
    )

    override suspend fun observeFolderMetadataTargets(
        treeUri: Uri,
        folderPaths: Set<String>,
    ): SafTargetedMetadataSnapshot = FolderScanner.observeTargetedMetadata(
        context = context,
        treeUri = treeUri,
        folderPaths = folderPaths,
        options = LibraryScanSettings.scanOptions(context),
    )

    override suspend fun observeFolderMetadataTargetsForInitialSync(
        treeUri: Uri,
        folderPaths: Set<String>,
    ): SafTargetedMetadataSnapshot = FolderScanner.observeTargetedMetadata(
        context = context,
        treeUri = treeUri,
        folderPaths = folderPaths,
        options = LibraryScanSettings.scanOptions(context),
        retainProbeDrafts = true,
    )

    override suspend fun verifyFolderObjectsMissing(
        treeUri: Uri,
        songs: Collection<Song>,
        startCursor: Int,
        budget: SafMissingVerificationBudget,
    ): SafIndependentMissingVerificationResult = FolderScanner.verifyMissingObjects(
        context = context,
        treeUri = treeUri,
        songs = songs,
        startCursor = startCursor,
        budget = budget,
    )

    override suspend fun scanFolder(
        treeUri: Uri,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean,
        forceRefreshArtwork: Boolean,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
    ): ScanResult = FolderScanner.scan(
        context = context,
        treeUri = treeUri,
        options = LibraryScanSettings.scanOptions(context).copy(
            forceRefreshLyrics = forceRefreshLyrics,
            forceRefreshArtwork = forceRefreshArtwork,
        ),
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        onLyricsBatch = onLyricsBatch,
    )

    override suspend fun scanFolderForSongs(
        treeUri: Uri,
        songIds: Set<String>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean,
        forceRefreshArtwork: Boolean,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
    ): ScanResult = FolderScanner.scan(
        context = context,
        treeUri = treeUri,
        options = LibraryScanSettings.scanOptions(context).copy(
            forceRefreshLyrics = forceRefreshLyrics,
            forceRefreshArtwork = forceRefreshArtwork,
            forceRefreshSongIds = songIds,
            scanOnlySongIds = songIds,
        ),
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        onLyricsBatch = onLyricsBatch,
    )

    override suspend fun scanFolderArtworkForSongs(
        treeUri: Uri,
        songIds: Set<String>,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
    ): ScanResult = FolderScanner.scanArtworkForSongs(
        context = context,
        treeUri = treeUri,
        songIds = songIds,
        cachedSongs = cachedSongs,
        onProgress = onProgress,
    )
}
