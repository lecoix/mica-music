package com.mica.music.data.library

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mica.music.data.LibraryScanner
import com.mica.music.data.LibraryStore
import com.mica.music.data.ScanEnvironment
import com.mica.music.data.ScanSource
import com.mica.music.data.Song
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

internal class MusicLibraryBacking(
    val context: Context,
    val libraryScanner: LibraryScanner,
    val libraryStore: LibraryStore,
    val scanEnvironment: ScanEnvironment,
    mainDispatcher: CoroutineDispatcher,
    val ioDispatcher: CoroutineDispatcher,
) {
    val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    val scanScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    var scanJob: Job? = null
    /**
     * Library-wide publication generation (historically named scanGeneration).
     * Bumped when starting cache hydrate, scan, clear, or release so stale
     * complete-snapshot publishers discard their results.
     */
    @Volatile
    var scanGeneration = 0
    var released = false
    val scanExecutionMutex = Mutex()
    val storeSyncMutex = Mutex()
    private val latestStoreRevision = AtomicLong(0L)

    private val songsById = HashMap<String, Song>()
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    var songIds by mutableStateOf<List<String>>(emptyList())
    var catalogRevision by mutableLongStateOf(0L)
    var queueMetadataRevision by mutableLongStateOf(0L)
    var lyricsDataVersion by mutableIntStateOf(scanEnvironment.lyricsParserVersion())
    var sortField by mutableStateOf(SongSortField.TITLE)
    var sortDirection by mutableStateOf(SortDirection.ASC)
    var customSongOrderLocked by mutableStateOf(false)
    var isLoadingCachedLibrary by mutableStateOf(false)
    var isScanning by mutableStateOf(false)
    var hasScanned by mutableStateOf(false)
    var totalSizeMb by mutableIntStateOf(0)
    var lastScanAtMs by mutableStateOf<Long?>(null)
    var permissionGranted by mutableStateOf(false)
    var libraryFolderUri by mutableStateOf<String?>(null)
    var libraryFolderLabel by mutableStateOf<String?>(null)
    var lastScanSource by mutableStateOf(ScanSource.DEVICE)
    var lastScanError by mutableStateOf<String?>(null)
    var lastScanSyncSummary by mutableStateOf<String?>(null)
    var scanProgressLabel by mutableStateOf<String?>(null)
    var songFastScrollLabels by mutableStateOf<List<String>?>(null)
    var songFastScrollSectionTargets by mutableStateOf<Map<String, Int>?>(null)

    val catalog = LibraryCatalogPublisher(this)
    val folder = LibraryFolderBinding(this)
    val cacheLoader = LibraryCacheLoader(this)
    val scanOrchestrator = LibraryScanOrchestrator(this)

    fun songById(id: String): Song? = songsById[id]

    fun replaceSongs(value: List<Song>) {
        songs = value
        songsById.clear()
        value.associateByTo(songsById, Song::id)
    }

    fun replaceSongAt(index: Int, value: Song) {
        val previousId = songs[index].id
        songs = songs.toMutableList().also { it[index] = value }
        if (previousId != value.id) songsById.remove(previousId)
        songsById[value.id] = value
    }

    fun isActiveGeneration(generation: Int): Boolean =
        !released && generation == scanGeneration

    fun nextStoreRevision(): Long = latestStoreRevision.incrementAndGet()

    fun isLatestStoreRevision(revision: Long): Boolean =
        revision == latestStoreRevision.get()

    /** Runs maintenance after any in-flight scan has settled, using the committed snapshot. */
    fun launchAlbumArtCacheMaintenance(fallbackSongs: List<Song>) {
        val fallback = fallbackSongs.toList()
        ioScope.launch {
            scanExecutionMutex.withLock {
                if (!released) scanEnvironment.pruneAlbumArtCache(fallback)
            }
        }
    }

    /**
     * Runs a catalog-dependent publication while no complete scan can adopt a newer snapshot.
     * Partial derived state must use this seam before publishing to memory or the store.
     */
    suspend fun <T> withCurrentCatalogPublication(
        expectedCatalogRevision: Long,
        block: suspend () -> T,
    ): T? = scanExecutionMutex.withLock {
        if (released || catalogRevision != expectedCatalogRevision) return@withLock null
        block()
    }

    /**
     * Writes derived state for the current catalog under the store revision protocol.
     * The caller takes [scanExecutionMutex] separately for the short in-memory publication.
     */
    suspend fun storeWriteIfCurrentCatalog(
        expectedCatalogRevision: Long,
        isCurrent: () -> Boolean,
        block: suspend () -> Unit,
    ): Boolean {
        val storeRevision = nextStoreRevision()
        return storeSyncMutex.withLock {
            if (
                released ||
                catalogRevision != expectedCatalogRevision ||
                !isCurrent() ||
                !isLatestStoreRevision(storeRevision)
            ) {
                return@withLock false
            }
            withContext(ioDispatcher) { block() }
            !released &&
                catalogRevision == expectedCatalogRevision &&
                isCurrent() &&
                isLatestStoreRevision(storeRevision)
        }
    }

    /**
     * Runs a store side effect only while the complete-snapshot generation and store revision
     * remain current. The lock intentionally covers the whole IO transaction so clear/commit
     * cannot finish before an older transaction and then be followed by that older write.
     */
    suspend fun storeWriteIfCurrent(
        generation: Int,
        block: suspend () -> Unit,
    ): Boolean {
        val storeRevision = nextStoreRevision()
        return storeSyncMutex.withLock {
            if (!isActiveGeneration(generation)) return@withLock false
            if (!isLatestStoreRevision(storeRevision)) return@withLock false
            withContext(ioDispatcher) { block() }
            isActiveGeneration(generation) && isLatestStoreRevision(storeRevision)
        }
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
}
