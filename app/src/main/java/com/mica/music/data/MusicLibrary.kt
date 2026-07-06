package com.mica.music.data

import android.content.Context
import android.net.Uri
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.library.MusicLibraryBacking
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

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

    val sortField get() = backing.sortField

    val sortDirection get() = backing.sortDirection

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

    fun onSongPlayed(songId: String) = backing.playStats.onSongPlayed(songId)

    fun onSongListened(songId: String, seconds: Long) =
        backing.playStats.onSongListened(songId, seconds)

    fun searchSongs(query: String): List<Song> = LibraryBrowse.search(songs, query)

    fun songById(id: String): Song? = songs.find { it.id == id }

    /** 从曲库移除（不删物理文件）；播放队列由调用方同步。 */
    fun removeSongFromLibrary(songId: String) = backing.catalog.removeSong(songId)

    fun recentSongs(): List<Song> =
        LibraryBrowse.recentSongs(songs, PlayHistoryStore.recentSongIds(backing.context))

    fun artistGroups(): List<BrowseGroup> = LibraryBrowse.groupByArtist(songs)

    fun albumGroups(): List<BrowseGroup> = LibraryBrowse.groupByAlbum(songs)

    fun folderGroups(pathSegments: List<String> = emptyList()): List<FolderBrowseGroup> =
        LibraryBrowse.folderGroups(songs, pathSegments)

    fun folderGroupsAtDepth(depth: Int, scopePathSegments: List<String> = emptyList()): List<FolderBrowseGroup> =
        LibraryBrowse.folderGroupsAtDepth(songs, depth, scopePathSegments)

    fun maxFolderDepth(): Int = LibraryBrowse.maxFolderDepth(songs)

    fun songsForArtist(artist: String): List<Song> = LibraryBrowse.songsForArtist(songs, artist)

    fun songsForAlbum(album: String): List<Song> =
        LibraryBrowse.songsForAlbum(songs, album)

    fun songsForFolder(pathSegments: List<String>): List<Song> =
        LibraryBrowse.songsForFolder(songs, pathSegments)

    fun songsInFolder(pathSegments: List<String>): List<Song> =
        LibraryBrowse.songsInFolder(songs, pathSegments)

    fun reloadLibraryFolderFromPrefs() = backing.folder.reloadLibraryFolderFromPrefs()

    fun hasLibraryFolder(): Boolean = backing.folder.hasLibraryFolder()

    fun setLibraryFolder(treeUri: Uri) = backing.folder.setLibraryFolder(treeUri)

    fun clearLibraryFolder() = backing.folder.clearLibraryFolder()

    fun updatePermission(granted: Boolean) = backing.folder.updatePermission(granted)

    fun audioReadPermission(): String = backing.folder.audioReadPermission()

    fun hasAudioReadPermission(): Boolean = backing.folder.hasAudioReadPermission()

    fun clearLibrary() = backing.folder.clearLibrary()

    /** 启动时从 Room 恢复上次扫描结果，避免每次冷启动都要重扫。 */
    suspend fun loadCachedLibrary() = backing.cacheLoader.loadCachedLibrary()

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

    fun clearScanSyncSummary() {
        backing.lastScanSyncSummary = null
    }

    fun release() = backing.release()
}
