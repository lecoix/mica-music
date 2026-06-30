package com.mica.music.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.CachedLibrary
import com.mica.music.data.local.LibrarySyncResult
import com.mica.music.data.scanner.ScanResult
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    fun lyricsParserUpgradeForcesOneSuccessfulLyricsRefresh() = runTest {
        val cached = SongFixtures.song("cached")
        val scanner = ControlledScanner()
        val environment = FakeScanEnvironment(parserVersion = 0)
        val library = library(
            scanner = scanner,
            store = FakeLibraryStore(CachedLibrary(listOf(cached), 100, ScanSource.DEVICE, 1)),
            environment = environment,
        )

        val scan = async { library.scanDeviceWide() }
        runCurrent()
        assertTrue(scanner.deviceRequests.single().cachedSongs.single().lyrics.isEmpty())
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(cached), 1))
        scan.await()

        assertEquals(CURRENT_LYRICS_PARSER_VERSION, environment.parserVersion)
        library.release()
    }

    @Test
    fun latestScanWinsWhenOlderResultArrivesLast() = runTest {
        val scanner = ControlledScanner()
        val library = library(scanner, FakeLibraryStore())

        val oldScan = async { library.scanDeviceWide() }
        runCurrent()
        val newScan = async { library.scanDeviceWide() }
        runCurrent()

        scanner.deviceRequests[1].result.complete(
            ScanResult(listOf(SongFixtures.song("new")), 20),
        )
        newScan.await()
        scanner.deviceRequests[0].result.complete(
            ScanResult(listOf(SongFixtures.song("old")), 10),
        )
        oldScan.await()

        assertEquals(listOf("new"), library.songs.map { it.id })
        assertEquals(20, library.totalSizeMb)
        library.release()
    }

    @Test
    fun releasePreventsLateScanStatePublication() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val library = library(scanner, store)

        val scan = async { library.scanDeviceWide() }
        runCurrent()
        library.release()
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("late")), 99),
        )
        scan.await()

        assertTrue(library.songs.isEmpty())
        assertFalse(library.hasScanned)
        assertFalse(library.isScanning)
        assertNull(library.scanProgressLabel)
        assertNull(library.lastScanAtMs)
        assertTrue(store.syncedSongs.isEmpty())
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
            return ScanRequest(cachedSongs).also(deviceRequests::add).result.await()
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
        ): LibrarySyncResult = syncIncremental(
            songs,
            lastScanAtMs,
            lastScanSource,
            totalSizeMb,
        )

        override suspend fun syncIncremental(
            songs: List<Song>,
            lastScanAtMs: Long,
            lastScanSource: ScanSource,
            totalSizeMb: Int,
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
        ): LibrarySyncResult = syncIncremental(
            songs,
            lastScanAtMs,
            lastScanSource,
            totalSizeMb,
        )

        override suspend fun syncIncremental(
            songs: List<Song>,
            lastScanAtMs: Long,
            lastScanSource: ScanSource,
            totalSizeMb: Int,
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
