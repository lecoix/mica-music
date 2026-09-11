package com.mica.music.data.library

import androidx.core.net.toUri
import com.mica.music.data.AlbumArtRepairAction
import com.mica.music.data.AlbumArtRepairPlan
import com.mica.music.data.ScanSource
import com.mica.music.util.DiagnosticLog

/** Executes artwork-cache repair scans without owning scheduling. */
internal class ArtworkRepairExecutor(
    private val backing: MusicLibraryBacking,
    private val scanEngine: LibraryScanEngine,
) {
    suspend fun repair(
        plan: AlbumArtRepairPlan,
        operation: ScheduledLibraryOperation? = null,
    ) {
        when (plan.action) {
            AlbumArtRepairAction.ScanDevice -> repairDevice(operation)
            AlbumArtRepairAction.ScanFolder -> repairFolder(operation)
            AlbumArtRepairAction.NoReadableSource -> Unit
        }
    }

    private suspend fun repairDevice(operation: ScheduledLibraryOperation?) {
        scanEngine.performScan(
            source = ScanSource.DEVICE,
            requestedForceRefreshLyrics = false,
            userVisible = false,
            operation = operation,
        ) { onProgress, cachedSongs, onLyricsBatch, policy ->
            backing.libraryScanner.scanDevice(
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                forceRefreshLyrics = policy.forceRefreshLyrics,
                forceRefreshArtwork = true,
                onLyricsBatch = onLyricsBatch,
            )
        }
    }

    private suspend fun repairFolder(operation: ScheduledLibraryOperation?) {
        val uriString = backing.libraryFolderUri ?: return
        val treeUri = uriString.toUri()
        if (!backing.scanEnvironment.canReadTree(treeUri)) {
            DiagnosticLog.important("AlbumArtCache", "repair-folder-skip cannot-read-tree uri=$treeUri")
            return
        }
        scanEngine.performScan(
            source = ScanSource.FOLDER,
            requestedForceRefreshLyrics = false,
            userVisible = false,
            operation = operation,
        ) { onProgress, cachedSongs, onLyricsBatch, policy ->
            backing.libraryScanner.scanFolder(
                treeUri = treeUri,
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                forceRefreshLyrics = policy.forceRefreshLyrics,
                forceRefreshArtwork = true,
                onLyricsBatch = onLyricsBatch,
            )
        }
    }
}