package com.mica.music.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.CachedLibrary
import com.mica.music.data.local.LibrarySyncResult
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.preferences.PreferencesTestFixtures
import com.mica.music.data.scanner.ScanResult
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MusicLibraryTest {

    @Test
    fun successfulScanUsesCacheAndPublishesSyncResult() = runTest {
        val cached = SongFixtures.song("cached")
        val fresh = SongFixtures.song("fresh")
        val scanner = ControlledScanner()
        val store = FakeLibraryStore(
            cached = CachedLibrary(listOf(cached), 100, ScanSource.DEVICE, 1),
        )
        val library = library(scanner, store)

        val scan = async { library.scanDeviceWide() }
        runCurrent()
        assertEquals(listOf("cached"), scanner.deviceRequests.single().cachedSongs.map { it.id })
        assertTrue(library.isScanning)

        scanner.deviceRequests.single().result.complete(ScanResult(listOf(fresh), totalSizeMb = 12))
        scan.await()

        assertEquals(listOf("fresh"), library.songs.map { it.id })
        assertEquals(12, library.totalSizeMb)
        assertEquals(1_234L, library.lastScanAtMs)
        assertEquals(ScanSource.DEVICE, library.lastScanSource)
        assertTrue(library.hasScanned)
        assertFalse(library.isScanning)
        assertNull(library.lastScanError)
        assertEquals(listOf("fresh"), store.syncedSongs.map { it.id })
        assertEquals(ScanSource.DEVICE, store.syncedSource)
        library.release()
    }

    @Test
    fun artworkCacheRepairStartsForcedArtworkOnlyScanWhenCachedArtNeedsRepair() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val missingArt = File(context.cacheDir, "album_art/missing-startup.jpg")
        val cached = SongFixtures.song("cached").copy(albumArtUri = missingArt.toURI().toString())
        val scanner = ControlledScanner()
        val library = library(
            scanner = scanner,
            store = FakeLibraryStore(CachedLibrary(listOf(cached), 100, ScanSource.DEVICE, 1)),
        )

        library.loadCachedLibrary()
        library.launchArtworkCacheRepairIfNeeded("test")
        runCurrent()

        val request = scanner.deviceRequests.single()
        assertTrue(request.forceRefreshArtwork)
        assertFalse(request.forceRefreshLyrics)
        request.result.complete(
            ScanResult(listOf(cached.copy(albumArtUri = "content://media/external/audio/albums/1")), 1),
        )
        runCurrent()

        assertFalse(library.isScanning)
        assertEquals("content://media/external/audio/albums/1", library.songs.single().albumArtUri)
        library.release()
    }

    @Test
    fun scannerFailureIsExposedAndLeavesPreviousSongsUntouched() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val library = library(scanner, store)

        val scan = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.completeExceptionally(
            IllegalStateException("broken media provider"),
        )
        scan.await()

        assertTrue(library.hasScanned)
        assertEquals("broken media provider", library.lastScanError)
        assertFalse(library.isScanning)
        assertTrue(library.songs.isEmpty())
        library.release()
    }

    @Test
    fun newestScanIsPersistedLastWhenOlderDatabaseSyncIsStillRunning() = runTest {
        val scanner = ControlledScanner()
        val store = BlockingLibraryStore()
        val library = library(scanner, store)

        val oldScan = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[0].result.complete(
            ScanResult(listOf(SongFixtures.song("old")), 10),
        )
        runCurrent()
        assertEquals(listOf("old"), store.requests.single().songs.map { it.id })

        val newScan = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[1].result.complete(
            ScanResult(listOf(SongFixtures.song("new")), 20),
        )
        runCurrent()
        assertEquals(1, store.requests.size)

        store.requests[0].release.complete(Unit)
        runCurrent()
        assertEquals(listOf("new"), store.requests[1].songs.map { it.id })
        store.requests[1].release.complete(Unit)
        oldScan.await()
        newScan.await()

        assertEquals(listOf("new"), library.songs.map { it.id })
        assertEquals(listOf("new"), store.persistedSongs.map { it.id })
        assertEquals(listOf("old", "new"), store.requests.map { it.songs.single().id })
        library.release()
    }

    @Test
    fun customSortSeedsVisibleOrderAndReusesSavedOrderAfterOtherSorts() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferencesTestFixtures.clearMicaSettings(context)
        val scanner = ControlledScanner()
        val library = library(scanner, FakeLibraryStore())
        val z = SongFixtures.song(id = "z", title = "Zulu")
        val a = SongFixtures.song(id = "a", title = "Alpha")
        val m = SongFixtures.song(id = "m", title = "Mike")

        val scan = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(z, a, m), totalSizeMb = 1))
        scan.await()

        assertEquals(listOf("a", "m", "z"), library.songs.map { it.id })

        library.updateSort(SongSortField.CUSTOM, SortDirection.DESC)

        assertEquals(SongSortField.CUSTOM, library.sortField)
        assertEquals(SortDirection.ASC, library.sortDirection)
        assertEquals(listOf("a", "m", "z"), library.songs.map { it.id })
        assertEquals(listOf("a", "m", "z"), LibraryBrowseSettings.customSongOrderIds(context))

        assertTrue(library.moveSongInLibrary(2, 0))
        assertEquals(listOf("z", "a", "m"), library.songs.map { it.id })
        assertEquals(listOf("z", "a", "m"), LibraryBrowseSettings.customSongOrderIds(context))

        library.updateCustomSongOrderLocked(true)
        assertFalse(library.moveSongInLibrary(1, 2))
        assertEquals(listOf("z", "a", "m"), library.songs.map { it.id })

        library.updateSort(SongSortField.TITLE, SortDirection.DESC)
        assertEquals(listOf("z", "m", "a"), library.songs.map { it.id })

        library.updateSort(SongSortField.CUSTOM, SortDirection.ASC)
        assertEquals(listOf("z", "a", "m"), library.songs.map { it.id })
        library.release()
    }

    private fun kotlinx.coroutines.test.TestScope.library(
        scanner: ControlledScanner,
        store: LibraryStore,
        environment: FakeScanEnvironment = FakeScanEnvironment(),
    ): MusicLibrary {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MusicLibrary(
            context = ApplicationProvider.getApplicationContext(),
            libraryScanner = scanner,
            libraryStore = store,
            scanEnvironment = environment,
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
    }

    private data class ScanRequest(
        val cachedSongs: List<Song>,
        val forceRefreshLyrics: Boolean,
        val forceRefreshArtwork: Boolean,
        val result: CompletableDeferred<ScanResult> = CompletableDeferred(),
    )

    private class ControlledScanner : LibraryScanner {
        val deviceRequests = mutableListOf<ScanRequest>()

        override suspend fun scanDevice(
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
            forceRefreshLyrics: Boolean,
            forceRefreshArtwork: Boolean,
        ): ScanResult {
            onProgress(0, cachedSongs.size)
            return ScanRequest(
                cachedSongs = cachedSongs,
                forceRefreshLyrics = forceRefreshLyrics,
                forceRefreshArtwork = forceRefreshArtwork,
            ).also(deviceRequests::add).result.await()
        }

        override suspend fun scanFolder(
            treeUri: Uri,
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
            forceRefreshLyrics: Boolean,
            forceRefreshArtwork: Boolean,
        ): ScanResult = error("folder scan not expected")
    }

    private class FakeLibraryStore(
        private val cached: CachedLibrary? = null,
    ) : LibraryStore {
        var syncedSongs: List<Song> = emptyList()
        var syncedSource: ScanSource? = null

        override suspend fun loadCached(): CachedLibrary? = cached

        override suspend fun save(
            songs: List<Song>,
            lastScanAtMs: Long,
            lastScanSource: ScanSource,
            totalSizeMb: Int,
            sortField: SongSortField?,
            sortDirection: SortDirection?,
            fastScrollSectionTargets: Map<String, Int>?,
        ): LibrarySyncResult = syncIncremental(
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
        ): LibrarySyncResult {
            syncedSongs = songs
            syncedSource = lastScanSource
            return LibrarySyncResult(songs.size, 0, 0, 0)
        }

        override suspend fun clear() {
            syncedSongs = emptyList()
        }
    }

    private data class StoreRequest(
        val songs: List<Song>,
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private class BlockingLibraryStore : LibraryStore {
        val requests = mutableListOf<StoreRequest>()
        var persistedSongs: List<Song> = emptyList()

        override suspend fun loadCached(): CachedLibrary? = null

        override suspend fun save(
            songs: List<Song>,
            lastScanAtMs: Long,
            lastScanSource: ScanSource,
            totalSizeMb: Int,
            sortField: SongSortField?,
            sortDirection: SortDirection?,
            fastScrollSectionTargets: Map<String, Int>?,
        ): LibrarySyncResult = syncIncremental(
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
        ): LibrarySyncResult {
            val request = StoreRequest(songs)
            requests += request
            request.release.await()
            persistedSongs = songs
            return LibrarySyncResult(songs.size, 0, 0, 0)
        }

        override suspend fun clear() = Unit
    }

    private class FakeScanEnvironment(
        var parserVersion: Int = CURRENT_LYRICS_PARSER_VERSION,
    ) : ScanEnvironment {
        override fun hasAudioReadPermission(): Boolean = true
        override fun canReadTree(treeUri: Uri): Boolean = true
        override fun currentTimeMillis(): Long = 1_234L
        override fun playStats(songId: String): PlayStats = PlayStats(0, 0)
        override fun clearTransientCache() = Unit
        override fun persistLastScanSource(source: ScanSource) = Unit
        override fun lyricsParserVersion(): Int = parserVersion
        override fun persistLyricsParserVersion(version: Int) {
            parserVersion = version
        }
    }
}
