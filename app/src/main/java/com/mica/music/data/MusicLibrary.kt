package com.mica.music.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.scanner.FolderScanner
import com.mica.music.data.scanner.MediaStoreScanner
import com.mica.music.data.scanner.ScanCacheManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicLibrary(private val context: Context) {

    private val libraryRepository = LibraryRepository(context)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    var songs by mutableStateOf<List<Song>>(emptyList())
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

    private fun applyCurrentSort() {
        if (scannedSongs.isEmpty()) return
        songs = SongSorter.sort(scannedSongs, sortField, sortDirection)
    }

    private fun persistSongsAsync() {
        if (scannedSongs.isEmpty() || lastScanAtMs == null) return
        val snapshot = songs
        val scanAt = lastScanAtMs!!
        val source = lastScanSource
        val sizeMb = totalSizeMb
        ioScope.launch {
            libraryRepository.save(snapshot, scanAt, source, sizeMb)
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

    private fun applyPlayStats(songId: String, stats: PlayStats) {
        val scannedIndex = scannedSongs.indexOfFirst { it.id == songId }
        if (scannedIndex < 0) return
        val updatedScanned = scannedSongs[scannedIndex].copy(
            playCount = stats.count,
            lastPlayedAtMs = stats.lastPlayedAtMs,
        )
        scannedSongs = scannedSongs.toMutableList().also { it[scannedIndex] = updatedScanned }
        songs = when (sortField) {
            SongSortField.PLAY_COUNT,
            SongSortField.LAST_PLAYED,
            -> SongSorter.sort(scannedSongs, sortField, sortDirection)
            else -> {
                val visibleIndex = songs.indexOfFirst { it.id == songId }
                if (visibleIndex < 0) {
                    songs
                } else {
                    songs.toMutableList().also { it[visibleIndex] = updatedScanned }
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
        ContextCompat.checkSelfPermission(context, audioReadPermission()) ==
            PackageManager.PERMISSION_GRANTED

    fun clearLibrary() {
        songs = emptyList()
        scannedSongs = emptyList()
        hasScanned = false
        totalSizeMb = 0
        lastScanAtMs = null
        lastScanError = null
        scanProgressLabel = null
        isScanning = false
        ioScope.launch { libraryRepository.clear() }
    }

    /** 启动时从 Room 恢复上次扫描结果，避免每次冷启动都要重扫。 */
    suspend fun loadCachedLibrary() {
        if (hasScanned || isScanning) return
        val cached = withContext(Dispatchers.IO) { libraryRepository.loadCached() } ?: return
        reloadSortFromPrefs()
        scannedSongs = cached.songs.map { song -> song.withPlayStats(context) }
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
     * 在 [ioScope] 中扫描，不随界面切换（Compose scope 销毁）而取消。
     */
    fun launchRescan() {
        if (isScanning) return
        scanJob?.cancel()
        scanJob = ioScope.launch { rescan() }
    }

    fun launchScanDeviceWide() {
        if (isScanning) return
        scanJob?.cancel()
        scanJob = ioScope.launch { scanDeviceWide() }
    }

    fun launchScanLibraryFolder() {
        if (isScanning) return
        scanJob?.cancel()
        scanJob = ioScope.launch { scanLibraryFolder() }
    }

    suspend fun scanDeviceWide() {
        if (!hasAudioReadPermission()) return
        if (isScanning) return
        performScan(ScanSource.DEVICE) { onProgress, cachedSongs ->
            val options = AppPreferences.scanOptions(context)
            MediaStoreScanner.scan(context, options, cachedSongs, onProgress)
        }
    }

    suspend fun scanLibraryFolder() {
        val uriString = libraryFolderUri ?: return
        val treeUri = uriString.toUri()
        if (!LibraryFolderStore.canReadTree(context, treeUri)) {
            lastScanError = "无法访问所选文件夹，请重新选择"
            hasScanned = true
            return
        }
        if (isScanning) return
        performScan(ScanSource.FOLDER) { onProgress, cachedSongs ->
            val options = AppPreferences.scanOptions(context)
            FolderScanner.scan(context, treeUri, options, cachedSongs, onProgress)
        }
    }

    private suspend fun performScan(
        source: ScanSource,
        block: suspend (
            onProgress: (Int, Int) -> Unit,
            cachedSongs: List<Song>,
        ) -> com.mica.music.data.scanner.ScanResult,
    ) {
        isScanning = true
        lastScanError = null
        scanProgressLabel = "正在读取歌曲列表…"
        ScanCacheManager.clearTransientScanCache(context)
        try {
            val cachedSongs = if (scannedSongs.isNotEmpty()) {
                scannedSongs
            } else {
                withContext(Dispatchers.IO) {
                    libraryRepository.loadCached()?.songs.orEmpty()
                }
            }
            val result = block(
                { done, total ->
                    scanProgressLabel = "正在分析音质、封面与歌词 ($done/$total)"
                },
                cachedSongs,
            )
            totalSizeMb = result.totalSizeMb
            hasScanned = true
            lastScanAtMs = System.currentTimeMillis()
            lastScanSource = source
            AppPreferences.setLastScanSource(context, source)
            publishSongs(result.songs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hasScanned = true
            lastScanError = e.message?.takeIf { it.isNotBlank() } ?: "未知错误"
        } finally {
            isScanning = false
            scanProgressLabel = null
        }
    }

    private suspend fun publishSongs(raw: List<Song>) {
        scannedSongs = raw.map { song -> song.withPlayStats(context) }
        applyCurrentSort()
        val scanAt = lastScanAtMs ?: return
        val sync = withContext(Dispatchers.IO) {
            libraryRepository.syncIncremental(
                songs = songs,
                lastScanAtMs = scanAt,
                lastScanSource = lastScanSource,
                totalSizeMb = totalSizeMb,
            )
        }
        lastScanSyncSummary = sync.toSummary()
    }

    fun clearScanSyncSummary() {
        lastScanSyncSummary = null
    }

    private fun Song.withPlayStats(ctx: Context): Song {
        val stats = PlayHistoryStore.getStats(ctx, id)
        return copy(
            playCount = stats.count,
            lastPlayedAtMs = stats.lastPlayedAtMs,
            artist = ArtistNames.normalizeDisplay(artist),
        )
    }
}
