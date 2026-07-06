package com.mica.music.data.library

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.CURRENT_LYRICS_PARSER_VERSION
import com.mica.music.data.LibraryScanner
import com.mica.music.data.LibraryStore
import com.mica.music.data.PlayStats
import com.mica.music.data.ScanEnvironment
import com.mica.music.data.ScanSource
import com.mica.music.data.Song
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.local.CachedLibrary
import com.mica.music.data.local.LibrarySyncResult
import com.mica.music.data.scanner.ScanResult
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class LibraryScanOrchestratorTest {

    @Test
    fun latestScanWinsWhenOlderResultArrivesLast() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)

        val oldScan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        val newScan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()

        scanner.deviceRequests[1].result.complete(
            ScanResult(listOf(SongFixtures.song("new")), 20),
        )
        newScan.await()
        scanner.deviceRequests[0].result.complete(
            ScanResult(listOf(SongFixtures.song("old")), 10),
        )
        oldScan.await()

        assertEquals(listOf("new"), harness.backing.songs.map { it.id })
        assertEquals(20, harness.backing.totalSizeMb)
        harness.backing.release()
    }

    @Test
    fun releasePreventsLateScanStatePublication() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        harness.backing.release()
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("late")), 99),
        )
        scan.await()

        assertTrue(harness.backing.songs.isEmpty())
        assertFalse(harness.backing.hasScanned)
        assertFalse(harness.backing.isScanning)
        assertNull(harness.backing.scanProgressLabel)
        assertNull(harness.backing.lastScanAtMs)
        assertTrue(store.syncedSongs.isEmpty())
    }

    @Test
    fun lyricsParserUpgradeClearsCachedLyricsBeforeScan() = runTest {
        val cached = SongFixtures.song("cached")
        val scanner = ControlledScanner()
        val environment = FakeScanEnvironment(parserVersion = 0)
        val harness = scanHarness(
            scanner = scanner,
            store = FakeLibraryStore(
                CachedLibrary(listOf(cached), 100, ScanSource.DEVICE, 1),
            ),
            environment = environment,
        )

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        assertTrue(scanner.deviceRequests.single().cachedSongs.single().lyrics.isEmpty())
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(cached), 1))
        scan.await()

        assertEquals(CURRENT_LYRICS_PARSER_VERSION, environment.parserVersion)
        harness.backing.release()
    }

    private fun TestScope.scanHarness(
        scanner: ControlledScanner,
        store: LibraryStore = FakeLibraryStore(),
        environment: FakeScanEnvironment = FakeScanEnvironment(),
    ): OrchestratorHarness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backing = MusicLibraryBacking(
            context = ApplicationProvider.getApplicationContext(),
            libraryScanner = scanner,
            libraryStore = store,
            scanEnvironment = environment,
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
        return OrchestratorHarness(backing, backing.scanOrchestrator)
    }

    private data class OrchestratorHarness(
        val backing: MusicLibraryBacking,
        val orchestrator: LibraryScanOrchestrator,
    )

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
            return LibrarySyncResult(songs.size, 0, 0, 0)
        }

        override suspend fun clear() {
            syncedSongs = emptyList()
        }
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
