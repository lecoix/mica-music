package com.mica.music.data

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.mica.music.data.scanner.ScanResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MusicLibrary internal constructor(
    private val context: Context,
    private val libraryScanner: LibraryScanner,
    private val libraryStore: LibraryStore,
    private val scanEnvironment: ScanEnvironment,
    mainDispatcher: CoroutineDispatcher,
    ioDispatcher: CoroutineDispatcher,
) {
    constructor(context: Context) : this(
        context = context,
        libraryScanner = AndroidLibraryScanner(context),
        libraryStore = RoomLibraryStore(context),
        scanEnvironment = AndroidScanEnvironment(context),
        mainDispatcher = Dispatchers.Main.immediate,
        ioDispatcher = Dispatchers.IO,
    )

    private val ioDispatcher = ioDispatcher
    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val scanScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private var scanJob: Job? = null
    private var scanGeneration = 0
    private var released = false
    private val storeSyncMutex = Mutex()

    var songs by mutableStateOf<List<Song>>(emptyList())
        private set

    /** 可见曲库 id 序列；仅顺序/成员变化时更新，供播放队列同步 LaunchedEffect 使用。 */
    var songIds by mutableStateOf<List<String>>(emptyList())
        private set

    var sortField by mutableStateOf(AppPreferences.songSortField(context))
        private set

    var sortDirection by mutableStateOf(AppPreferences.songSortDirection(context))
        private set

    var isScanning by mutableStateOf(false)
        private set

    var hasScanned by mutableStateOf(false)
        private set

    var totalSizeMb by mutableIntStateOf(0)
        private set

    var lastScanAtMs by mutableStateOf<Long?>(null)
        private set

    var permissionGranted by mutableStateOf(false)
        private set

    var libraryFolderUri by mutableStateOf<String?>(null)
        private set

    var libraryFolderLabel by mutableStateOf<String?>(null)
        private set

    var lastScanSource by mutableStateOf(ScanSource.DEVICE)
        private set

    var lastScanError by mutableStateOf<String?>(null)
        private set

    var lastScanSyncSummary by mutableStateOf<String?>(null)
        private set

    var scanProgressLabel by mutableStateOf<String?>(null)
        private set

    private var scannedSongs: List<Song> = emptyList()

    init {
        reloadLibraryFolderFromPrefs()
        reloadSortFromPrefs()
        lastScanSource = AppPreferences.lastScanSource(context)
    }

    fun updateSort(field: SongSortField, direction: SortDirection) {
        if (field == SongSortField.CUSTOM) return
        sortField = field
        sortDirection = direction
        AppPreferences.setSongSort(context, field, direction)
        applyCurrentSort()
        persistSongsAsync()
    }

    private fun reloadSortFromPrefs() {
        sortField = AppPreferences.songSortField(context)
        sortDirection = AppPreferences.songSortDirection(context)
    }

    private fun publishVisibleSongs(list: List<Song>) {
        songs = list
        songIds = list.map { it.id }
    }

    private fun applyCurrentSort() {
        if (scannedSongs.isEmpty()) return
        publishVisibleSongs(SongSorter.sort(scannedSongs, sortField, sortDirection))
    }

    private fun persistSongsAsync() {
        if (scannedSongs.isEmpty() || lastScanAtMs == null) return
        val snapshot = songs
        val scanAt = lastScanAtMs!!
        val source = lastScanSource
        val sizeMb = totalSizeMb
        ioScope.launch {
            libraryStore.save(snapshot, scanAt, source, sizeMb)
        }
    }

    fun onSongPlayed(songId: String) {
        ioScope.launch {
            val stats = PlayHistoryStore.recordPlay(context, songId)
            withContext(Dispatchers.Main.immediate) {
                applyPlayStats(songId, stats)
            }
        }
    }

    fun onSongListened(songId: String, seconds: Long) {
        if (seconds <= 0L) return
        ioScope.launch {
            val stats = PlayHistoryStore.recordListenSeconds(context, songId, seconds)
            withContext(Dispatchers.Main.immediate) {
                applyPlayStats(songId, stats)
            }
        }
    }

    private fun applyPlayStats(songId: String, stats: PlayStats) {
        val scannedIndex = scannedSongs.indexOfFirst { it.id == songId }
        if (scannedIndex < 0) return
        val updatedScanned = scannedSongs[scannedIndex].copy(
            playCount = stats.count,
            totalListenSeconds = stats.totalListenSeconds,
            lastPlayedAtMs = stats.lastPlayedAtMs,
        )
        scannedSongs = scannedSongs.toMutableList().also { it[scannedIndex] = updatedScanned }
        when (sortField) {
            SongSortField.PLAY_COUNT,
            SongSortField.LAST_PLAYED,
            -> publishVisibleSongs(SongSorter.sort(scannedSongs, sortField, sortDirection))
            else -> {
                val visibleIndex = songs.indexOfFirst { it.id == songId }
                if (visibleIndex >= 0) {
                    songs = songs.toMutableList().also { it[visibleIndex] = updatedScanned }
                }
            }
        }
    }

    fun searchSongs(query: String): List<Song> = LibraryBrowse.search(songs, query)

    fun songById(id: String): Song? = songs.find { it.id == id }

    /** 从曲库移除（不删物理文件）；播放队列由调用方同步。 */
    fun removeSongFromLibrary(songId: String) {
        scannedSongs = scannedSongs.filterNot { it.id == songId }
        applyCurrentSort()
        if (lastScanAtMs != null) {
            persistSongsAsync()
        }
    }

    fun recentSongs(): List<Song> =
        LibraryBrowse.recentSongs(songs, PlayHistoryStore.recentSongIds(context))

    fun artistGroups(): List<BrowseGroup> = LibraryBrowse.groupByArtist(songs)

    fun albumGroups(): List<BrowseGroup> = LibraryBrowse.groupByAlbum(songs)

    fun songsForArtist(artist: String): List<Song> = LibraryBrowse.songsForArtist(songs, artist)

    fun songsForAlbum(album: String): List<Song> =
        LibraryBrowse.songsForAlbum(songs, album)

    fun reloadLibraryFolderFromPrefs() {
        val uri = AppPreferences.libraryTreeUri(context)
        libraryFolderUri = uri?.toString()
        libraryFolderLabel = AppPreferences.libraryFolderLabel(context)
    }

    fun hasLibraryFolder(): Boolean = !libraryFolderUri.isNullOrBlank()

    fun setLibraryFolder(treeUri: Uri) {
        LibraryFolderStore.persistTreeAccess(context, treeUri)
        val label = LibraryFolderStore.displayName(context, treeUri)
        AppPreferences.setLibraryFolder(context, treeUri, label)
        libraryFolderUri = treeUri.toString()
        libraryFolderLabel = label
    }

    fun clearLibraryFolder() {
        libraryFolderUri?.toUri()?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        AppPreferences.clearLibraryFolder(context)
        libraryFolderUri = null
        libraryFolderLabel = null
    }

    fun updatePermission(granted: Boolean) {
        permissionGranted = granted
        if (!granted && !hasLibraryFolder()) {
            clearLibrary()
        }
    }

    fun audioReadPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasAudioReadPermission(): Boolean =
        scanEnvironment.hasAudioReadPermission()

    fun clearLibrary() {
        publishVisibleSongs(emptyList())
        scannedSongs = emptyList()
        hasScanned = false
        totalSizeMb = 0
        lastScanAtMs = null
        lastScanError = null
        scanProgressLabel = null
        isScanning = false
        ioScope.launch { libraryStore.clear() }
    }

    /** 启动时从 Room 恢复上次扫描结果，避免每次冷启动都要重扫。 */
    suspend fun loadCachedLibrary() {
        if (released || hasScanned || isScanning) return
        val cached = withContext(ioDispatcher) { libraryStore.loadCached() } ?: return
        if (released) return
        reloadSortFromPrefs()
        scannedSongs = cached.songs.map { song -> song.withPlayStats() }
        applyCurrentSort()
        totalSizeMb = cached.totalSizeMb
        lastScanAtMs = cached.lastScanAtMs
        lastScanSource = cached.lastScanSource
        hasScanned = true
        lastScanError = null
    }

    suspend fun rescan() {
        when (lastScanSource) {
            ScanSource.FOLDER -> {
                if (hasLibraryFolder()) scanLibraryFolder()
                else if (hasAudioReadPermission()) scanDeviceWide()
            }
            ScanSource.DEVICE -> {
                if (hasAudioReadPermission()) scanDeviceWide()
                else if (hasLibraryFolder()) scanLibraryFolder()
            }
        }
    }

    suspend fun scan() = rescan()

    /**
     * 扫描器内部切到 IO；状态编排保留在主线程，避免跨线程写 Compose State。
     */
    fun launchRescan() {
        scanJob?.cancel()
        scanJob = scanScope.launch { rescan() }
    }

    fun launchScanDeviceWide() {
        scanJob?.cancel()
        scanJob = scanScope.launch { scanDeviceWide() }
    }

    fun launchScanLibraryFolder() {
        scanJob?.cancel()
        scanJob = scanScope.launch { scanLibraryFolder() }
    }

    suspend fun scanDeviceWide() {
        if (!hasAudioReadPermission()) return
        performScan(ScanSource.DEVICE) { onProgress, cachedSongs ->
            libraryScanner.scanDevice(cachedSongs, onProgress)
        }
    }

    suspend fun scanLibraryFolder() {
        val uriString = libraryFolderUri ?: return
        val treeUri = uriString.toUri()
        if (!scanEnvironment.canReadTree(treeUri)) {
            lastScanError = "无法访问所选文件夹，请重新选择"
            hasScanned = true
            return
        }
        performScan(ScanSource.FOLDER) { onProgress, cachedSongs ->
            libraryScanner.scanFolder(treeUri, cachedSongs, onProgress)
        }
    }

    private suspend fun performScan(
        source: ScanSource,
        block: suspend (
            onProgress: (Int, Int) -> Unit,
            cachedSongs: List<Song>,
        ) -> ScanResult,
    ) {
        if (released) return
        val generation = ++scanGeneration
        isScanning = true
        lastScanError = null
        scanProgressLabel = "正在读取歌曲列表…"
        scanEnvironment.clearTransientCache()
        try {
            val cachedSongs = if (scannedSongs.isNotEmpty()) {
                scannedSongs
            } else {
                withContext(ioDispatcher) {
                    libraryStore.loadCached()?.songs.orEmpty()
                }
            }
            val lyricsParserUpgrade =
                scanEnvironment.lyricsParserVersion() < CURRENT_LYRICS_PARSER_VERSION
            val scanCachedSongs = if (lyricsParserUpgrade) {
                cachedSongs.map { it.copy(lyrics = emptyList()) }
            } else {
                cachedSongs
            }
            val result = block(
                { done, total ->
                    if (isActiveGeneration(generation)) {
                        scanProgressLabel = "正在分析音质、封面与歌词 ($done/$total)"
                    }
                },
                scanCachedSongs,
            )
            if (!isActiveGeneration(generation)) return
            totalSizeMb = result.totalSizeMb
            hasScanned = true
            lastScanAtMs = scanEnvironment.currentTimeMillis()
            lastScanSource = source
            scanEnvironment.persistLastScanSource(source)
            publishSongs(result.songs, generation)
            if (lyricsParserUpgrade && isActiveGeneration(generation)) {
                scanEnvironment.persistLyricsParserVersion(CURRENT_LYRICS_PARSER_VERSION)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isActiveGeneration(generation)) return
            hasScanned = true
            lastScanError = e.message?.takeIf { it.isNotBlank() } ?: "未知错误"
        } finally {
            if (isActiveGeneration(generation)) {
                isScanning = false
                scanProgressLabel = null
            }
        }
    }

    private suspend fun publishSongs(raw: List<Song>, generation: Int) {
        if (!isActiveGeneration(generation)) return
        scannedSongs = raw.map { song -> song.withPlayStats() }
        applyCurrentSort()
        val scanAt = lastScanAtMs ?: return
        val sync = storeSyncMutex.withLock {
            if (!isActiveGeneration(generation)) return
            withContext(ioDispatcher) {
                libraryStore.syncIncremental(
                    songs = songs,
                    lastScanAtMs = scanAt,
                    lastScanSource = lastScanSource,
                    totalSizeMb = totalSizeMb,
                )
            }
        }
        if (isActiveGeneration(generation)) {
            lastScanSyncSummary = sync.toSummary()
        }
    }

    fun clearScanSyncSummary() {
        lastScanSyncSummary = null
    }

    fun release() {
        released = true
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        isScanning = false
        scanProgressLabel = null
        scanScope.cancel()
        ioScope.cancel()
    }

    private fun Song.withPlayStats(): Song {
        val stats = scanEnvironment.playStats(id)
        return copy(
            playCount = stats.count,
            totalListenSeconds = stats.totalListenSeconds,
            lastPlayedAtMs = stats.lastPlayedAtMs,
            artist = ArtistNames.normalizeDisplay(artist),
        )
    }

    private fun isActiveGeneration(generation: Int): Boolean =
        !released && generation == scanGeneration
}
