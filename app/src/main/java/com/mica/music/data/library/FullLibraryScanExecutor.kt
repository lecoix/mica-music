package com.mica.music.data.library

import com.mica.music.data.ScanSource
import com.mica.music.data.scanner.ScanResult

/** Source-aware FULL scan entry point shared by user scans and targeted refresh. */
internal class FullLibraryScanExecutor(
    private val backing: MusicLibraryBacking,
    private val scanEngine: LibraryScanEngine,
) {
    private val folder get() = backing.folder

    suspend fun scanDeviceWide(
        forceRefreshSongIds: Set<String> = emptySet(),
        userVisible: Boolean = forceRefreshSongIds.isEmpty(),
        operation: ScheduledLibraryOperation? = null,
    ) {
        if (!folder.hasAudioReadPermission()) return
        scanEngine.performScan(
            source = ScanSource.DEVICE,
            requestedForceRefreshLyrics = false,
            forceRefreshSongIds = forceRefreshSongIds,
            userVisible = userVisible,
            operation = operation,
        ) {
                onProgress, cachedSongs, onLyricsBatch, policy ->
            scanDevice(
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                onLyricsBatch = onLyricsBatch,
                policy = policy,
            )
        }
    }

    suspend fun scanLibraryFolder(
        forceRefreshSongIds: Set<String> = emptySet(),
        userVisible: Boolean = forceRefreshSongIds.isEmpty(),
        operation: ScheduledLibraryOperation? = null,
    ) {
        val treeUri = folder.scanTreeUri() ?: return
        if (!backing.scanEnvironment.canReadTree(treeUri)) {
            folder.discardPendingFolderSelection()
            if (userVisible) {
                backing.lastScanError = "无法访问所选文件夹，请重新选择"
            }
            return
        }
        scanEngine.performScan(
            source = ScanSource.FOLDER,
            requestedForceRefreshLyrics = false,
            forceRefreshSongIds = forceRefreshSongIds,
            userVisible = userVisible,
            operation = operation,
        ) {
                onProgress, cachedSongs, onLyricsBatch, policy ->
            scanFolder(
                treeUri = treeUri,
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                onLyricsBatch = onLyricsBatch,
                policy = policy,
            )
        }
    }

    private suspend fun scanDevice(
        cachedSongs: List<com.mica.music.data.Song>,
        onProgress: (Int, Int) -> Unit,
        onLyricsBatch: suspend (com.mica.music.data.LyricsScanBatch) -> Unit,
        policy: ScanProbePolicy,
    ): ScanResult = if (policy.forceRefreshSongIds.isEmpty()) {
        backing.libraryScanner.scanDevice(
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = policy.forceRefreshLyrics,
            forceRefreshArtwork = false,
            onLyricsBatch = onLyricsBatch,
        )
    } else {
        backing.libraryScanner.scanDeviceForSongs(
            songIds = policy.forceRefreshSongIds,
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = policy.forceRefreshLyrics,
            forceRefreshArtwork = false,
            onLyricsBatch = onLyricsBatch,
        )
    }

    private suspend fun scanFolder(
        treeUri: android.net.Uri,
        cachedSongs: List<com.mica.music.data.Song>,
        onProgress: (Int, Int) -> Unit,
        onLyricsBatch: suspend (com.mica.music.data.LyricsScanBatch) -> Unit,
        policy: ScanProbePolicy,
    ): ScanResult = if (policy.forceRefreshSongIds.isEmpty()) {
        backing.libraryScanner.scanFolder(
            treeUri = treeUri,
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = policy.forceRefreshLyrics,
            forceRefreshArtwork = false,
            onLyricsBatch = onLyricsBatch,
        )
    } else {
        backing.libraryScanner.scanFolderForSongs(
            treeUri = treeUri,
            songIds = policy.forceRefreshSongIds,
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = policy.forceRefreshLyrics,
            forceRefreshArtwork = false,
            onLyricsBatch = onLyricsBatch,
        )
    }

}
