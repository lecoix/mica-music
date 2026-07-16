package com.mica.music.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.library.MusicLibraryBacking
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicLibrary internal constructor(
    context: Context,
    libraryScanner: LibraryScanner,
    libraryStore: LibraryStore,
    scanEnvironment: ScanEnvironment,
    mainDispatcher: CoroutineDispatcher,
    ioDispatcher: CoroutineDispatcher,
) {
    private val artistSplitRevisionState = mutableLongStateOf(0L)
    private val backing = MusicLibraryBacking(
        context = context,
        libraryScanner = libraryScanner,
        libraryStore = libraryStore,
        scanEnvironment = scanEnvironment,
        mainDispatcher = mainDispatcher,
        ioDispatcher = ioDispatcher,
    )
    private var artistGroupCacheRevision = -1L
    private var artistGroupCacheField: ArtistBrowseSortField? = null
    private var artistGroupCacheDirection: SortDirection? = null
    private var artistGroupCache: BrowseGroupPresentation? = null
    private var albumGroupCacheRevision = -1L
    private var albumGroupCacheField: AlbumBrowseSortField? = null
    private var albumGroupCacheDirection: SortDirection? = null
    private var albumGroupCache: BrowseGroupPresentation? = null

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

    val artistSplitRevision get() = artistSplitRevisionState.longValue

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
        ArtistNames.configure(LibraryBrowseSettings.artistSplitConfig(context))
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
        val previous = ArtistNames.currentConfig()
        ArtistNames.configure(config)
        if (ArtistNames.currentConfig() == previous) return
        artistSplitRevisionState.longValue++
        artistGroupCacheRevision = -1L
        artistGroupCache = null
        albumGroupCacheRevision = -1L
        albumGroupCache = null
        if (backing.sortField == SongSortField.ARTIST) {
            backing.catalog.applyCurrentSort()
            backing.catalog.persistPresentationAsync()
        }
    }

    fun onSongPlayed(songId: String) = backing.playStats.onSongPlayed(songId)

    fun onSongListened(songId: String, seconds: Long) =
        backing.playStats.onSongListened(songId, seconds)

    fun searchSongs(query: String): List<Song> = LibraryBrowse.search(songs, query)

    fun songById(id: String): Song? = backing.songById(id)

    suspend fun songWithLyrics(
        song: Song,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
        isPrefetch: Boolean = false,
    ): Song {
        if (song.lyricsLoaded) return song
        val priorityRevision = priority.joinToString(separator = ",", transform = LyricsSlot::name)
        val cacheRevision = "${song.lyricsCacheRevision}:$priorityRevision"
        SharedLyricsMemoryCache.get(song.id, cacheRevision, backing.lyricsDataVersion)?.let {
            DiagnosticLog.event(
                "LyricsCache",
                "hit song=${song.id.takeLast(12)} lines=${it.lines.size} " +
                    "sizeBytes=${SharedLyricsMemoryCache.sizeBytes()} " +
                    "entries=${SharedLyricsMemoryCache.entryCount()}",
            )
            return song.copy(lyricsDocument = it, lyricsLoaded = true)
        }
        val startedMs = SystemClock.elapsedRealtime()
        val lyrics = withContext(backing.ioDispatcher) {
            SharedLyricsMemoryCache.load(
                song.id,
                cacheRevision,
                backing.lyricsDataVersion,
                isPrefetch,
            ) {
                backing.libraryStore.loadLyrics(
                    song.id,
                    song.lyricsCacheRevision,
                    priority,
                )
            }.also {
                DiagnosticLog.event(
                    "LyricsCache",
                    "miss song=${song.id.takeLast(12)} lines=${it.lines.size} " +
                        "durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                        "sizeBytes=${SharedLyricsMemoryCache.sizeBytes()} " +
                        "entries=${SharedLyricsMemoryCache.entryCount()}",
                )
            }
        }
        return song.copy(lyricsDocument = lyrics, lyricsLoaded = true)
    }

    fun prefetchLyrics(
        song: Song?,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
    ) {
        if (song == null || song.lyricsLoaded) return
        backing.ioScope.launch { songWithLyrics(song, priority, isPrefetch = true) }
    }

    /** 从曲库移除（不删物理文件）；播放队列由调用方同步。 */
    fun removeSongFromLibrary(songId: String) = backing.catalog.removeSong(songId)

    fun recentSongs(): List<Song> =
        LibraryBrowse.recentSongs(songs, PlayHistoryStore.recentSongIds(backing.context))

    fun artistGroups(): List<BrowseGroup> = LibraryBrowse.groupByArtist(songs)

    fun albumGroups(): List<BrowseGroup> = LibraryBrowse.groupByAlbum(songs)

    fun artistGroupPresentation(
        field: ArtistBrowseSortField,
        direction: SortDirection,
    ): BrowseGroupPresentation {
        val source = songs
        val sourceRevision = backing.catalogRevision
        artistGroupCache?.takeIf {
            artistGroupCacheRevision == sourceRevision &&
                artistGroupCacheField == field &&
                artistGroupCacheDirection == direction
        }?.let { return it }
        return LibraryBrowse.artistGroupPresentation(source, field, direction).also {
            artistGroupCacheRevision = sourceRevision
            artistGroupCacheField = field
            artistGroupCacheDirection = direction
            artistGroupCache = it
        }
    }

    fun albumGroupPresentation(
        field: AlbumBrowseSortField,
        direction: SortDirection,
    ): BrowseGroupPresentation {
        val source = songs
        val sourceRevision = backing.catalogRevision
        albumGroupCache?.takeIf {
            albumGroupCacheRevision == sourceRevision &&
                albumGroupCacheField == field &&
                albumGroupCacheDirection == direction
        }?.let { return it }
        return LibraryBrowse.albumGroupPresentation(source, field, direction).also {
            albumGroupCacheRevision = sourceRevision
            albumGroupCacheField = field
            albumGroupCacheDirection = direction
            albumGroupCache = it
        }
    }

    suspend fun prewarmBrowseGroupCache() {
        val source = songs
        val sourceRevision = backing.catalogRevision
        val splitRevision = artistSplitRevision
        if (source.isEmpty()) return
        val artistField = LibraryBrowseSettings.artistBrowseSortField(backing.context)
        val artistDirection = LibraryBrowseSettings.artistBrowseSortDirection(backing.context)
        val albumField = LibraryBrowseSettings.albumBrowseSortField(backing.context)
        val albumDirection = LibraryBrowseSettings.albumBrowseSortDirection(backing.context)
        if (
            artistGroupCacheRevision == sourceRevision &&
            artistGroupCacheField == artistField &&
            artistGroupCacheDirection == artistDirection &&
            albumGroupCacheRevision == sourceRevision &&
            albumGroupCacheField == albumField &&
            albumGroupCacheDirection == albumDirection
        ) {
            return
        }

        val startedMs = SystemClock.elapsedRealtime()
        val prewarmed = withContext(backing.ioDispatcher) {
            LibraryBrowse.artistGroupPresentation(source, artistField, artistDirection) to
                LibraryBrowse.albumGroupPresentation(source, albumField, albumDirection)
        }
        if (sourceRevision != backing.catalogRevision || splitRevision != artistSplitRevision) return
        artistGroupCacheRevision = sourceRevision
        artistGroupCacheField = artistField
        artistGroupCacheDirection = artistDirection
        artistGroupCache = prewarmed.first
        albumGroupCacheRevision = sourceRevision
        albumGroupCacheField = albumField
        albumGroupCacheDirection = albumDirection
        albumGroupCache = prewarmed.second
        DiagnosticLog.event(
            "LibraryLoad",
            "prewarmBrowseGroups durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                "songs=${source.size} artists=${prewarmed.first.groups.size} albums=${prewarmed.second.groups.size} " +
                "artistSort=$artistField/$artistDirection albumSort=$albumField/$albumDirection",
        )
    }

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
