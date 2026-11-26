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
        artworkOnly: Boolean = false,
    ) {
        val folders = folder.scanFolders()
        if (folders.isEmpty()) return
        val treeUris = folders.map { android.net.Uri.parse(it.treeUri) }
        val unreadable = folders.zip(treeUris).firstOrNull { (_, uri) ->
            !backing.scanEnvironment.canReadTree(uri)
        }
        if (unreadable != null) {
            folder.discardPendingFolderSelection()
            if (userVisible) {
                backing.lastScanError = if (folders.size == 1) {
                    "无法访问所选文件夹，请重新选择"
                } else {
                    "无法访问文件夹「${unreadable.first.label}」，请重新授权或移除"
                }
            }
            return
        }
        val activeTreeUris = folder.activeTreeUris()
        val excludedDirectoriesOverride = if (
            activeTreeUris.isNotEmpty() &&
            activeTreeUris.map { it.toString() } != treeUris.map { it.toString() }
        ) {
            com.mica.music.data.LibraryFolderSet.migrateExcludedDirectories(
                context = backing.context,
                excludedDirectories = com.mica.music.data.preferences.LibraryScanSettings.excludedScanDirectories(backing.context),
                previousTreeUris = activeTreeUris,
                nextTreeUris = treeUris,
            )
        } else {
            null
        }
        scanEngine.performScan(
            source = ScanSource.FOLDER,
            requestedForceRefreshLyrics = false,
            forceRefreshSongIds = forceRefreshSongIds,
            userVisible = userVisible,
            operation = operation,
        ) {
                onProgress, cachedSongs, onLyricsBatch, policy ->
            scanFolders(
                treeUris = treeUris,
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                onLyricsBatch = onLyricsBatch,
                policy = policy,
                artworkOnly = artworkOnly,
                excludedDirectoriesOverride = excludedDirectoriesOverride,
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

    private suspend fun scanFolders(
        treeUris: List<android.net.Uri>,
        cachedSongs: List<com.mica.music.data.Song>,
        onProgress: (Int, Int) -> Unit,
        onLyricsBatch: suspend (com.mica.music.data.LyricsScanBatch) -> Unit,
        policy: ScanProbePolicy,
        artworkOnly: Boolean,
        excludedDirectoriesOverride: List<String>? = null,
    ): ScanResult = if (policy.forceRefreshSongIds.isEmpty()) {
        backing.libraryScanner.scanFoldersWithExcludedDirectories(
            treeUris = treeUris,
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = policy.forceRefreshLyrics,
            forceRefreshArtwork = false,
            onLyricsBatch = onLyricsBatch,
            excludedDirectoriesOverride = excludedDirectoriesOverride,
        )
    } else if (artworkOnly) {
        backing.libraryScanner.scanFoldersArtworkForSongs(
            treeUris = treeUris,
            songIds = policy.forceRefreshSongIds,
            cachedSongs = cachedSongs,
            onProgress = onProgress,
        )
    } else {
        backing.libraryScanner.scanFoldersForSongs(
            treeUris = treeUris,
            songIds = policy.forceRefreshSongIds,
            cachedSongs = cachedSongs,
            onProgress = onProgress,
            forceRefreshLyrics = policy.forceRefreshLyrics,
            forceRefreshArtwork = false,
            onLyricsBatch = onLyricsBatch,
        )
    }

}
