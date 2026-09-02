package com.mica.music.data

import android.content.Context
import android.net.Uri
import com.mica.music.data.library.MusicLibraryBacking
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.scanner.canPersistCoverColor
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class StartupBrowseTarget {
    NONE,
    ARTISTS,
    ALBUMS,
}

class MusicLibrary internal constructor(
    context: Context,
    libraryScanner: LibraryScanner,
    libraryStore: LibraryStore,
    scanEnvironment: ScanEnvironment,
    mainDispatcher: CoroutineDispatcher,
    ioDispatcher: CoroutineDispatcher,
) {
    private val backing = MusicLibraryBacking(
        context = context,
        libraryScanner = libraryScanner,
        libraryStore = libraryStore,
        scanEnvironment = scanEnvironment,
        mainDispatcher = mainDispatcher,
        ioDispatcher = ioDispatcher,
    )

    constructor(context: Context) : this(
        context = context,
        libraryScanner = AndroidLibraryScanner(context),
        libraryStore = RoomLibraryStore(context),
        scanEnvironment = AndroidScanEnvironment(context),
        mainDispatcher = Dispatchers.Main.immediate,
        ioDispatcher = Dispatchers.IO,
    )

    val songs get() = backing.songs

    /** 可见曲库 id 序列；仅顺序/成员变化时更新，供播放队列同步 LaunchedEffect 使用。 */
    val songIds get() = backing.songIds

    val queueMetadataRevision get() = backing.queueMetadataRevision

    val lyricsDataVersion get() = backing.lyricsDataVersion

    val artistSplitRevision get() = backing.browse.artistSplitRevision

    val sortField get() = backing.sortField

    val sortDirection get() = backing.sortDirection

    val customSongOrderLocked get() = backing.customSongOrderLocked

    val isLoadingCachedLibrary get() = backing.isLoadingCachedLibrary

    val isScanning get() = backing.isScanning

    val hasScanned get() = backing.hasScanned

    val totalSizeMb get() = backing.totalSizeMb

    val lastScanAtMs get() = backing.lastScanAtMs

    val permissionGranted get() = backing.permissionGranted

    val libraryFolderUri get() = backing.libraryFolderUri

    val libraryFolderLabel get() = backing.libraryFolderLabel

    val lastScanSource get() = backing.lastScanSource

    val lastScanError get() = backing.lastScanError

    val lastScanSyncSummary get() = backing.lastScanSyncSummary

    val scanProgressLabel get() = backing.scanProgressLabel

    val songFastScrollLabels get() = backing.songFastScrollLabels

    val songFastScrollSectionTargets get() = backing.songFastScrollSectionTargets

    init {
        backing.folder.reloadLibraryFolderFromPrefs()
        backing.catalog.reloadSortFromPrefs()
        backing.lastScanSource = LibraryScanSettings.lastScanSource(context)
    }

    fun updateSort(field: SongSortField, direction: SortDirection) =
        backing.catalog.updateSort(field, direction)

    fun moveSongInLibrary(fromIndex: Int, toIndex: Int): Boolean =
        backing.catalog.moveVisibleSong(fromIndex, toIndex)

    fun updateCustomSongOrderLocked(locked: Boolean) =
        backing.catalog.updateCustomSongOrderLocked(locked)

    fun updateArtistSplitConfig(config: ArtistSplitConfig) {
        backing.browse.updateArtistSplitConfig(config)
    }

    /** Refreshes in-memory song presentation after process-lifetime stats persistence. */
    fun applyPlayStats(songId: String, stats: PlayStats) {
        if (backing.released) return
        backing.catalog.applyPlayStats(songId, stats)
    }

    fun applyLoudnessAnalysis(
        songId: String,
        analysis: LoudnessAnalysis,
        notifyQueueMetadata: Boolean = true,
    ) {
        if (backing.released) return
        backing.catalog.applyLoudnessAnalysis(songId, analysis, notifyQueueMetadata)
    }

    fun applyCoverColorArgb(songId: String, albumArtUri: String?, argb: Int) {
        if (backing.released) return
        backing.catalog.applyCoverColorArgb(songId, albumArtUri, argb)
        persistCoverColorAsync(songId, albumArtUri, argb)
    }

    private fun persistCoverColorAsync(songId: String, albumArtUri: String?, argb: Int) {
        val generation = backing.scanGeneration
        backing.ioScope.launch {
            backing.storeWriteIfCurrentGeneration(
                expectedGeneration = generation,
                isCurrent = {
                    canPersistCoverColor(backing.songById(songId), songId, albumArtUri, argb)
                },
            ) {
                backing.libraryStore.updateCoverColorArgb(songId, argb)
            }
        }
    }

    fun notifyLoudnessScanCompleted() {
        if (backing.released) return
        backing.catalog.notifyQueueMetadataChanged()
    }

    fun searchSongs(query: String): List<Song> = backing.browse.searchSongs(query)

    fun songById(id: String): Song? = backing.songById(id)

    suspend fun songWithLyrics(
        song: Song,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
        isPrefetch: Boolean = false,
    ): Song = backing.lyricsHydrator.hydrate(song, priority, isPrefetch)

    fun prefetchLyrics(
        song: Song?,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
    ) = backing.lyricsHydrator.prefetch(song, priority)

    /** 从曲库移除（不删物理文件）；播放队列由调用方同步。 */
    fun removeSongFromLibrary(songId: String) = backing.catalog.removeSong(songId)

    fun recentSongs(): List<Song> = backing.browse.recentSongs()

    fun artistGroups(): List<BrowseGroup> = backing.browse.artistGroups()

    fun albumGroups(): List<BrowseGroup> = backing.browse.albumGroups()

    fun artistGroupPresentation(
        field: ArtistBrowseSortField,
        direction: SortDirection,
    ): BrowseGroupPresentation = backing.browse.artistGroupPresentation(field, direction)

    fun albumGroupPresentation(
        field: AlbumBrowseSortField,
        direction: SortDirection,
    ): BrowseGroupPresentation = backing.browse.albumGroupPresentation(field, direction)

    suspend fun prewarmBrowseGroupCache() = backing.browse.prewarmBrowseGroupCache()

    fun folderGroups(pathSegments: List<String> = emptyList()): List<FolderBrowseGroup> =
        backing.browse.folderGroups(pathSegments)

    fun folderGroupsAtDepth(depth: Int, scopePathSegments: List<String> = emptyList()): List<FolderBrowseGroup> =
        backing.browse.folderGroupsAtDepth(depth, scopePathSegments)

    fun musicFolderGroups(): List<FolderBrowseGroup> = backing.browse.musicFolderGroups()

    fun maxFolderDepth(): Int = backing.browse.maxFolderDepth()

    fun songsForArtist(artist: String): List<Song> = backing.browse.songsForArtist(artist)

    fun songsForAlbum(albumKey: AlbumBrowseKey): List<Song> =
        backing.browse.songsForAlbum(albumKey)

    fun songsForFolder(pathSegments: List<String>): List<Song> =
        backing.browse.songsForFolder(pathSegments)

    fun songsInFolder(pathSegments: List<String>): List<Song> =
        backing.browse.songsInFolder(pathSegments)

    fun reloadLibraryFolderFromPrefs() = backing.folder.reloadLibraryFolderFromPrefs()

    fun hasLibraryFolder(): Boolean = backing.folder.hasLibraryFolder()

    fun setLibraryFolder(treeUri: Uri) = backing.folder.setLibraryFolder(treeUri)

    fun clearLibraryFolder() = backing.folder.clearLibraryFolder()

    fun updatePermission(granted: Boolean) = backing.folder.updatePermission(granted)

    fun audioReadPermission(): String = backing.folder.audioReadPermission()

    fun hasAudioReadPermission(): Boolean = backing.folder.hasAudioReadPermission()

    fun clearLibrary() = backing.folder.clearLibrary()

    /** 启动时从 Room 恢复上次扫描结果，避免每次冷启动都要重扫。 */
    suspend fun loadCachedLibrary(target: StartupBrowseTarget = StartupBrowseTarget.NONE) {
        val cachedBrowse = backing.cacheLoader.loadCachedLibrary(target) ?: return
        backing.browse.adoptCachedBrowse(cachedBrowse)
    }

    /** Schedules non-blocking album-art maintenance against the committed library snapshot. */
    fun launchAlbumArtCacheMaintenance(reason: String = "background") {
        if (backing.released) return
        DiagnosticLog.event(
            "AlbumArtCache",
            "maintenance scheduled reason=$reason songs=${backing.songs.size}",
        )
        backing.launchAlbumArtCacheMaintenance()
    }

    suspend fun rescan() = backing.scanOrchestrator.rescan()

    suspend fun scan() = backing.scanOrchestrator.scan()

    /**
     * 扫描器内部切到 IO；状态编排保留在主线程，避免跨线程写 Compose State。
     */
    fun launchRescan() = backing.scanOrchestrator.launchRescan()

    fun launchScanDeviceWide() = backing.scanOrchestrator.launchScanDeviceWide()

    fun launchScanLibraryFolder() = backing.scanOrchestrator.launchScanLibraryFolder()

    /** 封面修复：协调器决策计划，扫描编排器执行。 */
    fun launchArtworkCacheRepairIfNeeded(reason: String = "startup") {
        if (backing.released || backing.isScanning || backing.songs.isEmpty()) return
        val plan = AlbumArtRepairCoordinator.plan(
            context = backing.context,
            songs = backing.songs,
            lastScanSource = backing.lastScanSource,
            hasLibraryFolder = backing.folder.hasLibraryFolder(),
            hasAudioReadPermission = backing.folder.hasAudioReadPermission(),
            reason = reason,
        ) ?: return
        if (plan.action == AlbumArtRepairAction.NoReadableSource) return
        backing.scanOrchestrator.launchArtworkCacheRepair(plan)
    }

    suspend fun scanDeviceWide() = backing.scanOrchestrator.scanDeviceWide()

    suspend fun scanLibraryFolder() = backing.scanOrchestrator.scanLibraryFolder()

    fun launchRefreshSongMetadata(songId: String) =
        backing.scanOrchestrator.launchRefreshSongMetadata(songId)

    fun clearScanSyncSummary() {
        backing.lastScanSyncSummary = null
    }

    fun release() = backing.release()
}
