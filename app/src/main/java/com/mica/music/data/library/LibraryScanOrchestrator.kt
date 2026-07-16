package com.mica.music.data.library

import android.os.SystemClock
import androidx.core.net.toUri
import com.mica.music.data.AlbumArtRepairAction
import com.mica.music.data.AlbumArtRepairPlan
import com.mica.music.data.CURRENT_LYRICS_PARSER_VERSION
import com.mica.music.data.ScanSource
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.data.scanner.ScanResult
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class LibraryScanOrchestrator(
    private val backing: MusicLibraryBacking,
) {
    private val catalog get() = backing.catalog
    private val folder get() = backing.folder

    suspend fun rescan() {
        when (backing.lastScanSource) {
            ScanSource.FOLDER -> {
                if (folder.hasLibraryFolder()) scanLibraryFolder()
                else if (folder.hasAudioReadPermission()) scanDeviceWide()
            }
            ScanSource.DEVICE -> {
                if (folder.hasAudioReadPermission()) scanDeviceWide()
                else if (folder.hasLibraryFolder()) scanLibraryFolder()
            }
        }
    }

    suspend fun scan() = rescan()

    fun launchRescan() {
        backing.scanJob?.cancel()
        backing.scanJob = backing.scanScope.launch { rescan() }
    }

    fun launchScanDeviceWide() {
        backing.scanJob?.cancel()
        backing.scanJob = backing.scanScope.launch { scanDeviceWide() }
    }

    fun launchScanLibraryFolder() {
        backing.scanJob?.cancel()
        backing.scanJob = backing.scanScope.launch { scanLibraryFolder() }
    }

    fun launchArtworkCacheRepair(plan: AlbumArtRepairPlan) {
        backing.scanJob?.cancel()
        backing.scanJob = backing.scanScope.launch {
            repairArtworkCache(plan)
        }
    }

    suspend fun scanDeviceWide() {
        if (!folder.hasAudioReadPermission()) return
        performScan(ScanSource.DEVICE, requestedForceRefreshLyrics = true) {
                onProgress, cachedSongs, onLyricsBatch, forceRefreshLyrics ->
            backing.libraryScanner.scanDevice(
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                forceRefreshLyrics = forceRefreshLyrics,
                forceRefreshArtwork = true,
                onLyricsBatch = onLyricsBatch,
            )
        }
    }

    suspend fun scanLibraryFolder() {
        val uriString = backing.libraryFolderUri ?: return
        val treeUri = uriString.toUri()
        if (!backing.scanEnvironment.canReadTree(treeUri)) {
            backing.lastScanError = "无法访问所选文件夹，请重新选择"
            backing.hasScanned = true
            return
        }
        performScan(ScanSource.FOLDER, requestedForceRefreshLyrics = true) {
                onProgress, cachedSongs, onLyricsBatch, forceRefreshLyrics ->
            backing.libraryScanner.scanFolder(
                treeUri = treeUri,
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                forceRefreshLyrics = forceRefreshLyrics,
                forceRefreshArtwork = true,
                onLyricsBatch = onLyricsBatch,
            )
        }
    }

    private suspend fun repairArtworkCache(plan: AlbumArtRepairPlan) {
        DiagnosticLog.event("AlbumArtCache", "repair-start reason=${plan.reason} ${plan.health.toLogMessage()}")
        when (plan.action) {
            AlbumArtRepairAction.ScanDevice -> repairDeviceArtwork()
            AlbumArtRepairAction.ScanFolder -> repairLibraryFolderArtwork()
            AlbumArtRepairAction.NoReadableSource -> Unit
        }
    }

    private suspend fun repairDeviceArtwork() {
        performScan(ScanSource.DEVICE, requestedForceRefreshLyrics = false) {
                onProgress, cachedSongs, onLyricsBatch, forceRefreshLyrics ->
            backing.libraryScanner.scanDevice(
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                forceRefreshLyrics = forceRefreshLyrics,
                forceRefreshArtwork = true,
                onLyricsBatch = onLyricsBatch,
            )
        }
    }

    private suspend fun repairLibraryFolderArtwork() {
        val uriString = backing.libraryFolderUri ?: return
        val treeUri = uriString.toUri()
        if (!backing.scanEnvironment.canReadTree(treeUri)) {
            DiagnosticLog.event("AlbumArtCache", "repair-folder-skip cannot-read-tree uri=$treeUri")
            return
        }
        performScan(ScanSource.FOLDER, requestedForceRefreshLyrics = false) {
                onProgress, cachedSongs, onLyricsBatch, forceRefreshLyrics ->
            backing.libraryScanner.scanFolder(
                treeUri = treeUri,
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                forceRefreshLyrics = forceRefreshLyrics,
                forceRefreshArtwork = true,
                onLyricsBatch = onLyricsBatch,
            )
        }
    }

    private suspend fun performScan(
        source: ScanSource,
        requestedForceRefreshLyrics: Boolean,
        block: suspend (
            onProgress: (Int, Int) -> Unit,
            cachedSongs: List<com.mica.music.data.Song>,
            onLyricsBatch: suspend (com.mica.music.data.LyricsScanBatch) -> Unit,
            forceRefreshLyrics: Boolean,
        ) -> ScanResult,
    ) = backing.scanExecutionMutex.withLock {
        performScanLocked(source, requestedForceRefreshLyrics, block)
    }

    private suspend fun performScanLocked(
        source: ScanSource,
        requestedForceRefreshLyrics: Boolean,
        block: suspend (
            onProgress: (Int, Int) -> Unit,
            cachedSongs: List<com.mica.music.data.Song>,
            onLyricsBatch: suspend (com.mica.music.data.LyricsScanBatch) -> Unit,
            forceRefreshLyrics: Boolean,
        ) -> ScanResult,
    ) {
        if (backing.released) return
        val generation = ++backing.scanGeneration
        val scanStartedMs = SystemClock.elapsedRealtime()
        DiagnosticLog.event(
            "LibraryScan",
            "performScan start source=$source generation=$generation currentSongs=${backing.songs.size}",
        )
        backing.isScanning = true
        backing.lastScanError = null
        backing.scanProgressLabel = "正在读取歌曲列表…"
        backing.scanEnvironment.clearTransientCache()
        try {
            val cacheStartedMs = SystemClock.elapsedRealtime()
            val cachedSongs = if (catalog.hasScannedSongs()) {
                catalog.scannedSongsSnapshot()
            } else {
                withContext(backing.ioDispatcher) {
                    backing.libraryStore.loadCached()?.songs.orEmpty()
                }
            }
            DiagnosticLog.event(
                "LibraryScan",
                "performScan cachedSongs durMs=${SystemClock.elapsedRealtime() - cacheStartedMs} " +
                    "songs=${cachedSongs.size} generation=$generation",
            )
            val lyricsParserUpgrade =
                backing.scanEnvironment.lyricsParserVersion() < CURRENT_LYRICS_PARSER_VERSION
            val forceRefreshLyrics = requestedForceRefreshLyrics || lyricsParserUpgrade ||
                backing.scanEnvironment.lyricsRetryRequired()
            val result = block(
                { done, total ->
                    if (backing.isActiveGeneration(generation)) {
                        backing.scanProgressLabel = "正在分析音质、封面与歌词 ($done/$total)"
                    }
                },
                cachedSongs,
                { batch ->
                    if (backing.isActiveGeneration(generation)) {
                        if (batch.readFailedCount > 0) {
                            backing.scanEnvironment.persistLyricsRetryRequired(true)
                        }
                        withContext(backing.ioDispatcher) {
                            backing.libraryStore.applyLyricsBatch(batch.completed)
                        }
                        SharedLyricsMemoryCache.invalidateSongs(batch.completed.map { it.songId })
                    }
                },
                forceRefreshLyrics,
            )
            DiagnosticLog.event(
                "LibraryScan",
                "performScan scannerResult durMs=${SystemClock.elapsedRealtime() - scanStartedMs} " +
                    "songs=${result.songs.size} generation=$generation " +
                    "technicalFailed=${result.probeStats.technicalFailed}",
            )
            if (!backing.isActiveGeneration(generation)) return
            val lyricsReadFailed = result.probeStats.hasLyricsReadFailures()
            if (lyricsReadFailed) {
                backing.scanEnvironment.persistLyricsRetryRequired(true)
            }
            backing.totalSizeMb = result.totalSizeMb
            backing.hasScanned = true
            backing.lastScanAtMs = backing.scanEnvironment.currentTimeMillis()
            backing.lastScanSource = source
            backing.scanEnvironment.persistLastScanSource(source)
            if (publishSongs(result.songs, generation) == null) return
            if (!lyricsReadFailed && backing.isActiveGeneration(generation)) {
                if (lyricsParserUpgrade) {
                    backing.scanEnvironment.persistLyricsParserVersion(CURRENT_LYRICS_PARSER_VERSION)
                    backing.lyricsDataVersion = CURRENT_LYRICS_PARSER_VERSION
                }
                backing.scanEnvironment.persistLyricsRetryRequired(false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!backing.isActiveGeneration(generation)) return
            backing.hasScanned = true
            backing.lastScanError = e.message?.takeIf { it.isNotBlank() } ?: "未知错误"
            DiagnosticLog.event("LibraryScan", "performScan failed generation=$generation", e)
        } finally {
            if (backing.isActiveGeneration(generation)) {
                backing.isScanning = false
                backing.scanProgressLabel = null
                DiagnosticLog.event(
                    "LibraryScan",
                    "performScan end durMs=${SystemClock.elapsedRealtime() - scanStartedMs} " +
                        "generation=$generation songs=${backing.songs.size} error=${backing.lastScanError != null}",
                )
            }
        }
    }

    private suspend fun publishSongs(
        raw: List<com.mica.music.data.Song>,
        generation: Int,
    ): com.mica.music.data.local.LibrarySyncResult? {
        if (!backing.isActiveGeneration(generation)) return null
        val prepared = catalog.prepareLibrarySongs(
            raw = raw,
            field = backing.sortField,
            direction = backing.sortDirection,
            diagnosticTag = "LibraryScan",
            diagnosticReason = "scanPublish",
        )
        val scanAt = backing.lastScanAtMs ?: return null
        val storeRevision = backing.nextStoreRevision()
        val syncStartedMs = SystemClock.elapsedRealtime()
        val sync = backing.storeSyncMutex.withLock {
            if (!backing.isActiveGeneration(generation)) return null
            if (!backing.isLatestStoreRevision(storeRevision)) return null
            withContext(backing.ioDispatcher) {
                backing.libraryStore.commitScan(
                    songs = prepared.visible,
                    lastScanAtMs = scanAt,
                    lastScanSource = backing.lastScanSource,
                    totalSizeMb = backing.totalSizeMb,
                    sortField = backing.sortField,
                    sortDirection = backing.sortDirection,
                    fastScrollSectionTargets = prepared.fastScrollIndex?.sectionTargets,
                )
            }
        }
        DiagnosticLog.event(
            "LibraryScan",
            "publishSongs dbSync durMs=${SystemClock.elapsedRealtime() - syncStartedMs} " +
                "generation=$generation visible=${prepared.visible.size}",
        )
        if (backing.isActiveGeneration(generation)) {
            catalog.adoptPrepared(prepared)
            catalog.releaseLoadedLyrics()
            backing.lastScanSyncSummary = sync.toSummary()
        }
        return sync
    }
}
