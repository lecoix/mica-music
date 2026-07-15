package com.mica.music.data

import android.content.Context
import android.net.Uri
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.local.CachedLibrary
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.local.LibrarySyncResult
import com.mica.music.data.scanner.FolderScanner
import com.mica.music.data.scanner.MediaStoreScanner
import com.mica.music.data.scanner.ScanCacheManager
import com.mica.music.data.scanner.ScanResult

internal interface LibraryScanner {
    suspend fun scanDevice(
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult

    suspend fun scanFolder(
        treeUri: Uri,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean = false,
        forceRefreshArtwork: Boolean = false,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)? = null,
    ): ScanResult
}

internal interface LibraryStore {
    suspend fun loadCached(): CachedLibrary?

    suspend fun loadLyrics(
        songId: String,
        revision: String,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
    ): LyricsDocument = LyricsDocument()

    suspend fun applyLyricsBatch(batch: List<ScannedSongLyrics>) = Unit

    suspend fun save(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
    ): LibrarySyncResult

    suspend fun syncIncremental(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
    ): LibrarySyncResult

    suspend fun commitScan(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField? = null,
        sortDirection: SortDirection? = null,
        fastScrollSectionTargets: Map<String, Int>? = null,
    ): LibrarySyncResult = syncIncremental(
        songs,
        lastScanAtMs,
        lastScanSource,
        totalSizeMb,
        sortField,
        sortDirection,
        fastScrollSectionTargets,
    )

    suspend fun updatePresentation(
        songIds: List<String>,
        sortField: SongSortField,
        sortDirection: SortDirection,
        fastScrollSectionTargets: Map<String, Int>?,
    ) = Unit

    suspend fun clear()
}

internal const val CURRENT_LYRICS_PARSER_VERSION = 5

internal interface ScanEnvironment {
    fun hasAudioReadPermission(): Boolean
    fun canReadTree(treeUri: Uri): Boolean
    fun currentTimeMillis(): Long
    fun playStats(songId: String): PlayStats
    fun clearTransientCache()
    fun persistLastScanSource(source: ScanSource)
    fun lyricsParserVersion(): Int = CURRENT_LYRICS_PARSER_VERSION
    fun persistLyricsParserVersion(version: Int) = Unit
    fun lyricsRetryRequired(): Boolean = false
    fun persistLyricsRetryRequired(required: Boolean) = Unit
}

internal class AndroidLibraryScanner(
    private val context: Context,
) : LibraryScanner {
    override suspend fun scanDevice(
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean,
        forceRefreshArtwork: Boolean,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
    ): ScanResult = MediaStoreScanner.scan(
        context = context,
        options = LibraryScanSettings.scanOptions(context).copy(
            forceRefreshLyrics = forceRefreshLyrics,
            forceRefreshArtwork = forceRefreshArtwork,
        ),
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        onLyricsBatch = onLyricsBatch,
    )

    override suspend fun scanFolder(
        treeUri: Uri,
        cachedSongs: List<Song>,
        onProgress: (Int, Int) -> Unit,
        forceRefreshLyrics: Boolean,
        forceRefreshArtwork: Boolean,
        onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
    ): ScanResult = FolderScanner.scan(
        context = context,
        treeUri = treeUri,
        options = LibraryScanSettings.scanOptions(context).copy(
            forceRefreshLyrics = forceRefreshLyrics,
            forceRefreshArtwork = forceRefreshArtwork,
        ),
        cachedSongs = cachedSongs,
        onProgress = onProgress,
        onLyricsBatch = onLyricsBatch,
    )
}

internal class RoomLibraryStore(
    context: Context,
) : LibraryStore {
    private val repository = LibraryRepository(context)

    override suspend fun loadCached(): CachedLibrary? = repository.loadCached()

    override suspend fun loadLyrics(
        songId: String,
        revision: String,
        priority: List<LyricsSlot>,
    ): LyricsDocument = repository.lyricsById(songId, priority, revision)

    override suspend fun applyLyricsBatch(batch: List<ScannedSongLyrics>) =
        repository.applyLyricsBatch(batch)

    override suspend fun save(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField?,
        sortDirection: SortDirection?,
        fastScrollSectionTargets: Map<String, Int>?,
    ): LibrarySyncResult = repository.save(
        songs,
        lastScanAtMs,
        lastScanSource,
        totalSizeMb,
        sortField,
        sortDirection,
        fastScrollSectionTargets,
    )

    override suspend fun syncIncremental(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField?,
        sortDirection: SortDirection?,
        fastScrollSectionTargets: Map<String, Int>?,
    ): LibrarySyncResult =
        repository.syncIncremental(
            songs,
            lastScanAtMs,
            lastScanSource,
            totalSizeMb,
            sortField,
            sortDirection,
            fastScrollSectionTargets,
        )

    override suspend fun commitScan(
        songs: List<Song>,
        lastScanAtMs: Long,
        lastScanSource: ScanSource,
        totalSizeMb: Int,
        sortField: SongSortField?,
        sortDirection: SortDirection?,
        fastScrollSectionTargets: Map<String, Int>?,
    ): LibrarySyncResult = repository.commitScan(
        songs,
        lastScanAtMs,
        lastScanSource,
        totalSizeMb,
        sortField,
        sortDirection,
        fastScrollSectionTargets,
    )

    override suspend fun updatePresentation(
        songIds: List<String>,
        sortField: SongSortField,
        sortDirection: SortDirection,
        fastScrollSectionTargets: Map<String, Int>?,
    ) = repository.updatePresentation(
        songIds,
        sortField,
        sortDirection,
        fastScrollSectionTargets,
    )

    override suspend fun clear() = repository.clear()
}

internal class AndroidScanEnvironment(
    private val context: Context,
) : ScanEnvironment {
    override fun hasAudioReadPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            },
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun canReadTree(treeUri: Uri): Boolean =
        LibraryFolderStore.canReadTree(context, treeUri)

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun playStats(songId: String): PlayStats =
        PlayHistoryStore.getStats(context, songId)

    override fun clearTransientCache() =
        ScanCacheManager.clearTransientScanCache(context)

    override fun persistLastScanSource(source: ScanSource) =
        LibraryScanSettings.setLastScanSource(context, source)

    override fun lyricsParserVersion(): Int = LibraryScanSettings.lyricsParserVersion(context)

    override fun persistLyricsParserVersion(version: Int) =
        LibraryScanSettings.setLyricsParserVersion(context, version)

    override fun lyricsRetryRequired(): Boolean = LibraryScanSettings.lyricsRetryRequired(context)

    override fun persistLyricsRetryRequired(required: Boolean) =
        LibraryScanSettings.setLyricsRetryRequired(context, required)
}
