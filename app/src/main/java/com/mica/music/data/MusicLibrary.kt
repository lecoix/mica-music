package com.mica.music.data

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.mica.music.data.scanner.AlbumArtCache
import com.mica.music.data.scanner.ScanResult
import com.mica.music.util.DiagnosticLog
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

private data class PreparedLibrarySongs(
    val scanned: List<Song>,
    val visible: List<Song>,
    val fastScrollIndex: FastScrollIndex?,
)

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

    var isLoadingCachedLibrary by mutableStateOf(false)
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

    var songFastScrollLabels by mutableStateOf<List<String>?>(null)
        private set

    var songFastScrollSectionTargets by mutableStateOf<Map<String, Int>?>(null)
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

    private fun publishVisibleSongs(list: List<Song>, fastScrollIndex: FastScrollIndex? = null) {
        songs = list
        songIds = list.map { it.id }
        songFastScrollLabels = fastScrollIndex?.labels
        songFastScrollSectionTargets = fastScrollIndex?.sectionTargets
    }

    private fun applyCurrentSort(diagnosticReason: String? = null) {
        if (scannedSongs.isEmpty()) return
        val startedMs = SystemClock.elapsedRealtime()
        val presentation = LibraryPresentationBuilder.prepare(scannedSongs, sortField, sortDirection)
        publishVisibleSongs(presentation.visible, presentation.fastScrollIndex)
        if (diagnosticReason != null) {
            DiagnosticLog.event(
                "LibraryLoad",
                "$diagnosticReason sort+publish durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                    "raw=${scannedSongs.size} visible=${songs.size} sort=$sortField/$sortDirection",
            )
        }
    }

    private fun persistSongsAsync() {
        if (scannedSongs.isEmpty() || lastScanAtMs == null) return
        val snapshot = songs
        val scanAt = lastScanAtMs!!
        val source = lastScanSource
        val sizeMb = totalSizeMb
        val field = sortField
        val direction = sortDirection
        val sectionTargets = songFastScrollSectionTargets
        ioScope.launch {
            libraryStore.save(snapshot, scanAt, source, sizeMb, field, direction, sectionTargets)
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
        val oldScanned = scannedSongs[scannedIndex]
        val updatedScanned = scannedSongs[scannedIndex].copy(
            playCount = stats.count,
            totalListenSeconds = stats.totalListenSeconds,
            lastPlayedAtMs = stats.lastPlayedAtMs,
        )
        DiagnosticLog.event(
            "LibraryMutation",
            "diag=play-stats-song-update song=${songId.takeLast(12)} " +
                "fields=${SongChangeDiagnostics.summarizeChangedFields(oldScanned, updatedScanned)} " +
                "count=${oldScanned.playCount}->${updatedScanned.playCount} " +
                "listen=${oldScanned.totalListenSeconds}->${updatedScanned.totalListenSeconds} " +
                "lastPlayed=${oldScanned.lastPlayedAtMs}->${updatedScanned.lastPlayedAtMs} " +
                "sort=$sortField/$sortDirection visibleIndex=${songs.indexOfFirst { it.id == songId }}",
        )
        scannedSongs = scannedSongs.toMutableList().also { it[scannedIndex] = updatedScanned }
        when (sortField) {
            SongSortField.PLAY_COUNT,
            SongSortField.LAST_PLAYED,
            -> {
                val presentation = LibraryPresentationBuilder.prepare(scannedSongs, sortField, sortDirection)
                publishVisibleSongs(presentation.visible, presentation.fastScrollIndex)
            }
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
            LibraryFolderStore.releaseTreeAccess(context, uri)
        }
        AppPreferences.clearLibraryFolder(context)
        libraryFolderUri = null
        libraryFolderLabel = null
    }

    fun updatePermission(granted: Boolean) {
        DiagnosticLog.event(
            "LibraryResume",
            "updatePermission granted=$granted previous=$permissionGranted hasFolder=${hasLibraryFolder()} " +
                "hasScanned=$hasScanned songs=${songs.size}",
        )
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
        DiagnosticLog.event(
            "LibraryResume",
            "clearLibrary start songs=${songs.size} scanned=${scannedSongs.size} " +
                "hasScanned=$hasScanned lastScanAtMs=$lastScanAtMs",
        )
        publishVisibleSongs(emptyList())
        scannedSongs = emptyList()
        hasScanned = false
        totalSizeMb = 0
        lastScanAtMs = null
        lastScanError = null
        scanProgressLabel = null
        isScanning = false
        isLoadingCachedLibrary = false
        ioScope.launch {
            val startedMs = SystemClock.elapsedRealtime()
            libraryStore.clear()
            DiagnosticLog.event(
                "LibraryResume",
                "clearLibrary storeClear end durMs=${SystemClock.elapsedRealtime() - startedMs}",
            )
        }
    }

    /** 启动时从 Room 恢复上次扫描结果，避免每次冷启动都要重扫。 */
    suspend fun loadCachedLibrary() {
        if (released || hasScanned || isScanning) {
            DiagnosticLog.event(
                "LibraryLoad",
                "loadCached skipped released=$released hasScanned=$hasScanned isScanning=$isScanning",
            )
            return
        }
        val startedMs = SystemClock.elapsedRealtime()
        DiagnosticLog.event("LibraryLoad", "loadCached begin")
        isLoadingCachedLibrary = true
        try {
            val dbStartedMs = SystemClock.elapsedRealtime()
            val cached = withContext(ioDispatcher) { libraryStore.loadCached() }
            DiagnosticLog.event(
                "LibraryLoad",
                "loadCached db durMs=${SystemClock.elapsedRealtime() - dbStartedMs} songs=${cached?.songs?.size ?: 0}",
            )
            if (cached == null) {
                DiagnosticLog.event("LibraryLoad", "loadCached empty durMs=${SystemClock.elapsedRealtime() - startedMs}")
                return
            }
            if (released) return
            reloadSortFromPrefs()
            val sortCanUseStoredOrder = cached.sortField == sortField && cached.sortDirection == sortDirection
            val prepared = prepareLibrarySongs(
                raw = cached.songs,
                field = sortField,
                direction = sortDirection,
                diagnosticTag = "LibraryLoad",
                diagnosticReason = "loadCached",
                useInputOrder = sortCanUseStoredOrder,
                cachedSectionTargets = cached.fastScrollSectionTargets,
            )
            scannedSongs = prepared.scanned
            publishVisibleSongs(prepared.visible, prepared.fastScrollIndex)
            totalSizeMb = cached.totalSizeMb
            lastScanAtMs = cached.lastScanAtMs
            lastScanSource = cached.lastScanSource
            hasScanned = true
            lastScanError = null
            if (!sortCanUseStoredOrder || cached.fastScrollSectionTargets == null) {
                persistSongsAsync()
            }
            DiagnosticLog.event(
                "LibraryLoad",
                "loadCached end durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                    "songs=${songs.size} sizeMb=$totalSizeMb source=$lastScanSource cachedOrder=$sortCanUseStoredOrder",
            )
        } finally {
            isLoadingCachedLibrary = false
        }
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

    fun launchArtworkCacheRepairIfNeeded(reason: String = "startup") {
        if (released || isScanning || songs.isEmpty()) return
        val health = AlbumArtCache.health(context, songs)
        DiagnosticLog.event("AlbumArtCache", "repair-check reason=$reason ${health.toLogMessage()}")
        if (!health.needsRepair) return
        scanJob?.cancel()
        scanJob = scanScope.launch {
            repairArtworkCache(reason, health)
        }
    }

    suspend fun scanDeviceWide() {
        if (!hasAudioReadPermission()) return
        performScan(ScanSource.DEVICE) { onProgress, cachedSongs ->
            libraryScanner.scanDevice(
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                forceRefreshLyrics = true,
                forceRefreshArtwork = true,
            )
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
            libraryScanner.scanFolder(
                treeUri = treeUri,
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                forceRefreshLyrics = true,
                forceRefreshArtwork = true,
            )
        }
    }

    private suspend fun repairArtworkCache(
        reason: String,
        health: AlbumArtCache.Health,
    ) {
        DiagnosticLog.event("AlbumArtCache", "repair-start reason=$reason ${health.toLogMessage()}")
        when (lastScanSource) {
            ScanSource.FOLDER -> {
                if (hasLibraryFolder()) {
                    repairLibraryFolderArtwork()
                } else if (hasAudioReadPermission()) {
                    repairDeviceArtwork()
                } else {
                    DiagnosticLog.event("AlbumArtCache", "repair-skip reason=$reason no-readable-source")
                }
            }
            ScanSource.DEVICE -> {
                if (hasAudioReadPermission()) {
                    repairDeviceArtwork()
                } else if (hasLibraryFolder()) {
                    repairLibraryFolderArtwork()
                } else {
                    DiagnosticLog.event("AlbumArtCache", "repair-skip reason=$reason no-readable-source")
                }
            }
        }
    }

    private suspend fun repairDeviceArtwork() {
        performScan(ScanSource.DEVICE) { onProgress, cachedSongs ->
            libraryScanner.scanDevice(
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                forceRefreshLyrics = false,
                forceRefreshArtwork = true,
            )
        }
    }

    private suspend fun repairLibraryFolderArtwork() {
        val uriString = libraryFolderUri ?: return
        val treeUri = uriString.toUri()
        if (!scanEnvironment.canReadTree(treeUri)) {
            DiagnosticLog.event("AlbumArtCache", "repair-folder-skip cannot-read-tree uri=$treeUri")
            return
        }
        performScan(ScanSource.FOLDER) { onProgress, cachedSongs ->
            libraryScanner.scanFolder(
                treeUri = treeUri,
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                forceRefreshLyrics = false,
                forceRefreshArtwork = true,
            )
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
        val scanStartedMs = SystemClock.elapsedRealtime()
        DiagnosticLog.event(
            "LibraryScan",
            "performScan start source=$source generation=$generation currentSongs=${songs.size}",
        )
        isScanning = true
        lastScanError = null
        scanProgressLabel = "正在读取歌曲列表…"
        scanEnvironment.clearTransientCache()
        try {
            val cacheStartedMs = SystemClock.elapsedRealtime()
            val cachedSongs = if (scannedSongs.isNotEmpty()) {
                scannedSongs
            } else {
                withContext(ioDispatcher) {
                    libraryStore.loadCached()?.songs.orEmpty()
                }
            }
            DiagnosticLog.event(
                "LibraryScan",
                "performScan cachedSongs durMs=${SystemClock.elapsedRealtime() - cacheStartedMs} " +
                    "songs=${cachedSongs.size} generation=$generation",
            )
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
            DiagnosticLog.event(
                "LibraryScan",
                "performScan scannerResult durMs=${SystemClock.elapsedRealtime() - scanStartedMs} " +
                    "songs=${result.songs.size} generation=$generation",
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
            DiagnosticLog.event("LibraryScan", "performScan failed generation=$generation", e)
        } finally {
            if (isActiveGeneration(generation)) {
                isScanning = false
                scanProgressLabel = null
                DiagnosticLog.event(
                    "LibraryScan",
                    "performScan end durMs=${SystemClock.elapsedRealtime() - scanStartedMs} " +
                        "generation=$generation songs=${songs.size} error=${lastScanError != null}",
                )
            }
        }
    }

    private suspend fun publishSongs(raw: List<Song>, generation: Int) {
        if (!isActiveGeneration(generation)) return
        val prepared = prepareLibrarySongs(
            raw = raw,
            field = sortField,
            direction = sortDirection,
            diagnosticTag = "LibraryScan",
            diagnosticReason = "scanPublish",
        )
        scannedSongs = prepared.scanned
        publishVisibleSongs(prepared.visible, prepared.fastScrollIndex)
        val scanAt = lastScanAtMs ?: return
        val syncStartedMs = SystemClock.elapsedRealtime()
        val sync = storeSyncMutex.withLock {
            if (!isActiveGeneration(generation)) return
            withContext(ioDispatcher) {
                libraryStore.syncIncremental(
                    songs = songs,
                    lastScanAtMs = scanAt,
                    lastScanSource = lastScanSource,
                    totalSizeMb = totalSizeMb,
                    sortField = sortField,
                    sortDirection = sortDirection,
                    fastScrollSectionTargets = prepared.fastScrollIndex?.sectionTargets,
                )
            }
        }
        DiagnosticLog.event(
            "LibraryScan",
            "publishSongs dbSync durMs=${SystemClock.elapsedRealtime() - syncStartedMs} " +
                "generation=$generation visible=${songs.size}",
        )
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
        isLoadingCachedLibrary = false
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

    private suspend fun prepareLibrarySongs(
        raw: List<Song>,
        field: SongSortField,
        direction: SortDirection,
        diagnosticTag: String,
        diagnosticReason: String,
        useInputOrder: Boolean = false,
        cachedSectionTargets: Map<String, Int>? = null,
    ): PreparedLibrarySongs = withContext(ioDispatcher) {
        val statsStartedMs = SystemClock.elapsedRealtime()
        val scanned = raw.map { song -> song.withPlayStats() }
        DiagnosticLog.event(
            diagnosticTag,
            "$diagnosticReason stats durMs=${SystemClock.elapsedRealtime() - statsStartedMs} songs=${scanned.size}",
        )

        val presentationStartedMs = SystemClock.elapsedRealtime()
        val presentation = LibraryPresentationBuilder.prepare(
            scannedSongs = scanned,
            field = field,
            direction = direction,
            useInputOrder = useInputOrder,
            cachedSectionTargets = cachedSectionTargets,
        )
        DiagnosticLog.event(
            diagnosticTag,
            "$diagnosticReason presentation durMs=${SystemClock.elapsedRealtime() - presentationStartedMs} " +
                "raw=${scanned.size} visible=${presentation.visible.size} sort=$field/$direction " +
                "cachedOrder=$useInputOrder labels=${presentation.fastScrollIndex?.labels?.size ?: 0} " +
                "sections=${presentation.fastScrollIndex?.sectionTargets?.size ?: 0} " +
                "cachedSections=${cachedSectionTargets != null}",
        )

        PreparedLibrarySongs(
            scanned = scanned,
            visible = presentation.visible,
            fastScrollIndex = presentation.fastScrollIndex,
        )
    }
}
