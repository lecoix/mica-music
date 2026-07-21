package com.mica.music.data.library

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.CURRENT_LYRICS_PARSER_VERSION
import com.mica.music.data.LibraryScanner
import com.mica.music.data.LibraryStore
import com.mica.music.data.LyricsSlots
import com.mica.music.data.LyricsScanBatch
import com.mica.music.data.PlayStats
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.ScanEnvironment
import com.mica.music.data.ScanSource
import com.mica.music.data.Song
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.local.CachedLibrary
import com.mica.music.data.local.LibrarySyncResult
import com.mica.music.data.scanner.ScanResult
import com.mica.music.data.scanner.ScanProbeStats
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
    fun concurrentScansExecuteSequentiallyAndLatestResultWins() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)

        val oldScan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        val newScan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()

        assertEquals(1, scanner.deviceRequests.size)
        assertFalse(scanner.deviceRequests.single().forceRefreshArtwork)
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("old")), 10),
        )
        oldScan.await()
        runCurrent()
        scanner.deviceRequests[1].result.complete(
            ScanResult(listOf(SongFixtures.song("new")), 20),
        )
        newScan.await()

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
    fun lyricsParserUpgradeForcesProbeWithoutMutatingCachedLyrics() = runTest {
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
        assertEquals(0, harness.backing.lyricsDataVersion)
        assertTrue(scanner.deviceRequests.single().forceRefreshLyrics)
        assertEquals(cached.lyricsDocument, scanner.deviceRequests.single().cachedSongs.single().lyricsDocument)
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(cached), 1))
        scan.await()

        assertEquals(CURRENT_LYRICS_PARSER_VERSION, environment.parserVersion)
        assertEquals(CURRENT_LYRICS_PARSER_VERSION, harness.backing.lyricsDataVersion)
        harness.backing.release()
    }

    @Test
    fun lyricsReadFailurePersistsRetryAndBlocksParserVersionAdvanceUntilCleanScan() = runTest {
        val scanner = ControlledScanner()
        val environment = FakeScanEnvironment(parserVersion = 0)
        val harness = scanHarness(scanner, environment = environment)
        val song = SongFixtures.song("retry")

        val failedScan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[0].onLyricsBatch?.invoke(LyricsScanBatch(emptyList(), 1))
        assertTrue(environment.retryRequired)
        scanner.deviceRequests[0].result.complete(
            ScanResult(
                songs = listOf(song),
                totalSizeMb = 1,
                probeStats = ScanProbeStats(lyricsReadFailed = 1),
            ),
        )
        failedScan.await()

        assertTrue(environment.retryRequired)
        assertEquals(0, environment.parserVersion)

        val cleanScan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        assertTrue(scanner.deviceRequests[1].forceRefreshLyrics)
        scanner.deviceRequests[1].result.complete(ScanResult(listOf(song), 1))
        cleanScan.await()

        assertFalse(environment.retryRequired)
        assertEquals(CURRENT_LYRICS_PARSER_VERSION, environment.parserVersion)
        harness.backing.release()
    }

    @Test
    fun canceledScanKeepsAlreadyCommittedTrustedLyricsWithoutPublishingCatalog() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val environment = FakeScanEnvironment()
        val harness = scanHarness(scanner, store, environment)
        val song = SongFixtures.song("staged")

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().onLyricsBatch?.invoke(
            LyricsScanBatch(listOf(
                ScannedSongLyrics(
                    song.id,
                    song.lyricsCacheRevision,
                    LyricsSlots(embedded = song.lyricsDocument),
                ),
            ), 0),
        )
        assertEquals(1, store.appliedLyrics.size)

        scan.cancelAndJoin()

        assertEquals(1, store.appliedLyrics.size)
        assertTrue(store.syncedSongs.isEmpty())
        assertTrue(environment.prunedSongIds.isEmpty())
        harness.backing.release()
    }

    @Test
    fun successfulScanPrunesArtworkOnlyAfterTheNewLibraryIsCommitted() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val environment = FakeScanEnvironment()
        val harness = scanHarness(scanner, store, environment)
        val scanned = listOf(SongFixtures.song("one"), SongFixtures.song("two"))

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        assertTrue(environment.prunedSongIds.isEmpty())

        scanner.deviceRequests.single().result.complete(ScanResult(scanned, 2))
        scan.await()

        assertEquals(scanned.map(Song::id), store.syncedSongs.map(Song::id))
        assertEquals(scanned.map(Song::id), environment.prunedSongIds)
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
        val onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
        val result: CompletableDeferred<ScanResult> = CompletableDeferred(),
    )

    private class ControlledScanner : LibraryScanner {
        val deviceRequests = mutableListOf<ScanRequest>()

        override suspend fun scanDevice(
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
            forceRefreshLyrics: Boolean,
            forceRefreshArtwork: Boolean,
            onLyricsBatch: (suspend (com.mica.music.data.LyricsScanBatch) -> Unit)?,
        ): ScanResult {
            onProgress(0, cachedSongs.size)
            return ScanRequest(
                cachedSongs = cachedSongs,
                forceRefreshLyrics = forceRefreshLyrics,
                forceRefreshArtwork = forceRefreshArtwork,
                onLyricsBatch = onLyricsBatch,
            ).also(deviceRequests::add).result.await()
        }

        override suspend fun scanFolder(
            treeUri: Uri,
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
            forceRefreshLyrics: Boolean,
            forceRefreshArtwork: Boolean,
            onLyricsBatch: (suspend (com.mica.music.data.LyricsScanBatch) -> Unit)?,
        ): ScanResult = error("folder scan not expected")
    }

    private class FakeLibraryStore(
        private val cached: CachedLibrary? = null,
    ) : LibraryStore {
        var syncedSongs: List<Song> = emptyList()
        val appliedLyrics = mutableListOf<ScannedSongLyrics>()

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

        override suspend fun applyLyricsBatch(batch: List<ScannedSongLyrics>) {
            appliedLyrics += batch
        }

        override suspend fun commitScan(
            songs: List<Song>,
            lastScanAtMs: Long,
            lastScanSource: ScanSource,
            totalSizeMb: Int,
            sortField: SongSortField?,
            sortDirection: SortDirection?,
            fastScrollSectionTargets: Map<String, Int>?,
        ): LibrarySyncResult {
            return syncIncremental(
                songs,
                lastScanAtMs,
                lastScanSource,
                totalSizeMb,
                sortField,
                sortDirection,
                fastScrollSectionTargets,
            )
        }
    }

    private class FakeScanEnvironment(
        var parserVersion: Int = CURRENT_LYRICS_PARSER_VERSION,
        var retryRequired: Boolean = false,
    ) : ScanEnvironment {
        var prunedSongIds: List<String> = emptyList()
        override fun hasAudioReadPermission(): Boolean = true
        override fun canReadTree(treeUri: Uri): Boolean = true
        override fun currentTimeMillis(): Long = 1_234L
        override fun playStats(songId: String): PlayStats = PlayStats(0, 0)
        override fun clearTransientCache() = Unit
        override fun pruneAlbumArtCache(songs: List<Song>) {
            prunedSongIds = songs.map(Song::id)
        }
        override fun persistLastScanSource(source: ScanSource) = Unit
        override fun lyricsParserVersion(): Int = parserVersion
        override fun persistLyricsParserVersion(version: Int) {
            parserVersion = version
        }
        override fun lyricsRetryRequired(): Boolean = retryRequired
        override fun persistLyricsRetryRequired(required: Boolean) {
            retryRequired = required
        }
    }
}
