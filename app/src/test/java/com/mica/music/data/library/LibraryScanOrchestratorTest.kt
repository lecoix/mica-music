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
import com.mica.music.data.StartupBrowseTarget
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
import kotlinx.coroutines.sync.withLock
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
        assertFalse(scanner.deviceRequests.single().forceRefreshLyrics)
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
    fun targetedSongRefreshUsesOneOffProbeTargetWithoutGlobalLyricsRefresh() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val target = SongFixtures.song("target").copy(
            title = "Old title",
            mediaUri = "content://media/external/audio/media/42",
            fileName = "track.flac",
            folderPath = "QQmusic/song",
            filePath = "QQmusic/song/track.flac",
            sizeBytes = 100L,
            dateAddedMs = 10L,
            dateModifiedMs = 20L,
        )
        val other = SongFixtures.song("other")
        harness.backing.replaceSongs(listOf(target, other))
        val refreshedTarget = target.copy(
            title = "New title",
            mediaUri = "content://media/external/audio/media/42",
            fileName = "TRACK.flac",
            folderPath = "qqmusic/song",
            filePath = "qqmusic/song/TRACK.flac",
            sizeBytes = 120L,
            dateAddedMs = 999L,
            dateModifiedMs = 30L,
        )

        val refresh = async { harness.orchestrator.refreshSongMetadata(target.id) }
        runCurrent()

        assertEquals(setOf(target.id), scanner.deviceRequests.single().forceRefreshSongIds)
        assertFalse(scanner.deviceRequests.single().forceRefreshLyrics)
        val scannerChangedOther = other.copy(title = "Scanner should not refresh this")
        val transientExtra = SongFixtures.song("transient-extra")
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(refreshedTarget, scannerChangedOther, transientExtra), 2),
        )
        refresh.await()

        assertEquals(setOf(target.id, other.id), harness.backing.songs.map { it.id }.toSet())
        val published = harness.backing.songs.single { it.id == target.id }
        assertEquals("New title", published.title)
        assertEquals(120L, published.sizeBytes)
        assertEquals(30L, published.dateModifiedMs)
        assertEquals(target.mediaUri, published.mediaUri)
        assertEquals(target.fileName, published.fileName)
        assertEquals(target.folderPath, published.folderPath)
        assertEquals(target.filePath, published.filePath)
        assertEquals(target.dateAddedMs, published.dateAddedMs)
        assertEquals(other.title, harness.backing.songs.single { it.id == other.id }.title)
        harness.backing.release()
    }

    @Test
    fun targetedSongRefreshKeepsPreviousRowWhenTargetTemporarilyDisappearsFromScanner() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val target = SongFixtures.song("target-missing").copy(
            title = "Old title",
            folderPath = "QQmusic/song",
            filePath = "QQmusic/song/track.flac",
        )
        val other = SongFixtures.song("other-kept")
        harness.backing.replaceSongs(listOf(target, other))

        val refresh = async { harness.orchestrator.refreshSongMetadata(target.id) }
        runCurrent()
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(other), 1))
        refresh.await()

        assertEquals(setOf(target.id, other.id), harness.backing.songs.map { it.id }.toSet())
        assertEquals("Old title", harness.backing.songs.single { it.id == target.id }.title)
        assertEquals("QQmusic/song", harness.backing.songs.single { it.id == target.id }.folderPath)
        harness.backing.release()
    }

    @Test
    fun fullDiscoveryScanAllowsStorageLocationToChange() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val previous = SongFixtures.song("moved").copy(
            folderPath = "Music/A",
            filePath = "Music/A/track.flac",
        )
        harness.backing.replaceSongs(listOf(previous))
        val moved = previous.copy(
            folderPath = "Music/B",
            filePath = "Music/B/track.flac",
            dateModifiedMs = previous.dateModifiedMs + 1L,
        )

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(moved), 1))
        scan.await()

        val published = harness.backing.songs.single()
        assertEquals("Music/B", published.folderPath)
        assertEquals("Music/B/track.flac", published.filePath)
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
    fun clearLibraryDoesNotAllowAnInFlightLyricsBatchToRepopulateTheClearedStore() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        val releaseLyricsBatch = CompletableDeferred<Unit>()
        store.lyricsBatchGate = releaseLyricsBatch
        val song = SongFixtures.song("clear-lyrics-race")

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        val applyBatch = async {
            scanner.deviceRequests.single().onLyricsBatch?.invoke(
                LyricsScanBatch(
                    completed = listOf(
                        ScannedSongLyrics(
                            song.id,
                            song.lyricsCacheRevision,
                            LyricsSlots(embedded = song.lyricsDocument),
                        ),
                    ),
                    readFailedCount = 0,
                ),
            )
        }
        runCurrent()
        assertTrue(store.lyricsBatchStarted.isCompleted)

        val generationBeforeClear = harness.backing.scanGeneration
        harness.backing.folder.clearLibrary()
        runCurrent()
        assertTrue(harness.backing.scanGeneration > generationBeforeClear)

        releaseLyricsBatch.complete(Unit)
        applyBatch.await()
        runCurrent()

        assertTrue(store.appliedLyrics.isEmpty())
        assertTrue(store.clearCompleted)
        scan.cancelAndJoin()
        harness.backing.release()
    }

    @Test
    fun successfulScanSchedulesArtworkMaintenanceAfterTheNewLibraryIsCommitted() = runTest {
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
        runCurrent()

        assertEquals(scanned.map(Song::id), store.syncedSongs.map(Song::id))
        assertEquals(scanned.map(Song::id), environment.prunedSongIds)
        assertTrue(environment.prefetchedVideoCoverUris.isEmpty())
        harness.backing.release()
    }

    @Test
    fun generationChangeDuringBackgroundArtworkMaintenanceDoesNotDiscardPublishedSnapshot() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val environment = FakeScanEnvironment()
        val harness = scanHarness(scanner, store, environment)
        environment.duringPrune = {
            harness.backing.scanGeneration++
        }

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("invalidated")), 1),
        )
        scan.await()
        runCurrent()

        assertTrue(environment.prunedSongIds.isNotEmpty())
        assertEquals(listOf("invalidated"), harness.backing.songs.map(Song::id))
        assertTrue(harness.backing.hasScanned)
        harness.backing.release()
    }

    @Test
    fun artworkMaintenanceReadsCatalogOnlyAfterScanLockIsReleased() = runTest {
        val scanner = ControlledScanner()
        val environment = FakeScanEnvironment()
        val harness = scanHarness(scanner, environment = environment)
        val oldSnapshot = SongFixtures.song("old-art")
        val newSnapshot = SongFixtures.song("new-art")
        harness.backing.replaceSongs(listOf(oldSnapshot))

        val releaseScan = CompletableDeferred<Unit>()
        val scanLock = async {
            harness.backing.scanExecutionMutex.withLock {
                releaseScan.await()
            }
        }
        runCurrent()

        harness.backing.launchAlbumArtCacheMaintenance()
        runCurrent()
        harness.backing.replaceSongs(listOf(newSnapshot))
        releaseScan.complete(Unit)
        scanLock.await()
        runCurrent()

        assertEquals(listOf("new-art"), environment.prunedSongIds)
        harness.backing.release()
    }

    @Test
    fun scanFailureDoesNotPublishMetadataOrHasScanned() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        val kept = SongFixtures.song("kept")

        val first = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[0].result.complete(ScanResult(listOf(kept), 5))
        first.await()
        assertEquals(5, harness.backing.totalSizeMb)
        assertEquals(1_234L, harness.backing.lastScanAtMs)

        val failed = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[1].result.completeExceptionally(IllegalStateException("boom"))
        failed.await()

        assertEquals(listOf("kept"), harness.backing.songs.map { it.id })
        assertEquals(5, harness.backing.totalSizeMb)
        assertEquals(1_234L, harness.backing.lastScanAtMs)
        assertTrue(harness.backing.hasScanned)
        assertEquals("boom", harness.backing.lastScanError)
        assertEquals(listOf("kept"), store.syncedSongs.map { it.id })
        harness.backing.release()
    }

    @Test
    fun clearLibraryInvalidatesInFlightScanPublication() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        harness.backing.folder.clearLibrary()
        runCurrent()
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("late")), 99),
        )
        scan.await()
        runCurrent()

        assertTrue(harness.backing.songs.isEmpty())
        assertFalse(harness.backing.hasScanned)
        assertNull(harness.backing.lastScanAtMs)
        assertTrue(store.syncedSongs.isEmpty())
        harness.backing.release()
    }

    @Test
    fun staleCacheHydrateIsDiscardedAfterNewerScanPublishes() = runTest {
        val scanner = ControlledScanner()
        val deferredCache = CompletableDeferred<CachedLibrary?>()
        var loadCachedCalls = 0
        val store = FakeLibraryStore(
            cachedLoader = {
                // First call is cache hydrate (blocked); later scan bootstrap returns empty quickly.
                if (loadCachedCalls++ == 0) deferredCache.await() else null
            },
        )
        val harness = scanHarness(scanner, store)

        val cacheLoad = async { harness.backing.cacheLoader.loadCachedLibrary(StartupBrowseTarget.NONE) }
        runCurrent()

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("fresh")), 3),
        )
        scan.await()
        runCurrent()
        assertEquals(listOf("fresh"), harness.backing.songs.map { it.id })

        deferredCache.complete(
            CachedLibrary(
                songs = listOf(SongFixtures.song("stale")),
                lastScanAtMs = 10L,
                lastScanSource = ScanSource.DEVICE,
                totalSizeMb = 1,
            ),
        )
        assertNull(cacheLoad.await())
        assertEquals(listOf("fresh"), harness.backing.songs.map { it.id })
        assertEquals(3, harness.backing.totalSizeMb)
        harness.backing.release()
    }

    @Test
    fun folderScanEnqueuesUniqueVideoCoverPosterPrefetchAfterPublish() = runTest {
        val scanner = ControlledScanner()
        val environment = FakeScanEnvironment()
        val harness = scanHarness(scanner, environment = environment)
        harness.backing.libraryFolderUri = "content://tree/music"
        val scanned = listOf(
            SongFixtures.song("a").copy(videoCoverUri = "content://video/Album.mp4"),
            SongFixtures.song("b").copy(videoCoverUri = "content://video/Album.mp4"),
            SongFixtures.song("c").copy(videoCoverUri = null),
        )

        val scan = async { harness.orchestrator.scanLibraryFolder() }
        runCurrent()
        scanner.folderRequests.single().result.complete(ScanResult(scanned, 3))
        scan.await()

        assertEquals(
            listOf("content://video/Album.mp4", "content://video/Album.mp4"),
            environment.prefetchedVideoCoverUris,
        )
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
        val forceRefreshSongIds: Set<String> = emptySet(),
        val onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
        val result: CompletableDeferred<ScanResult> = CompletableDeferred(),
    )

    private class ControlledScanner : LibraryScanner {
        val deviceRequests = mutableListOf<ScanRequest>()
        val folderRequests = mutableListOf<ScanRequest>()

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

        override suspend fun scanDeviceForSongs(
            songIds: Set<String>,
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
                forceRefreshSongIds = songIds,
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
        ): ScanResult {
            onProgress(0, cachedSongs.size)
            return ScanRequest(
                cachedSongs = cachedSongs,
                forceRefreshLyrics = forceRefreshLyrics,
                forceRefreshArtwork = forceRefreshArtwork,
                onLyricsBatch = onLyricsBatch,
            ).also(folderRequests::add).result.await()
        }
    }

    private class FakeLibraryStore(
        private val cached: CachedLibrary? = null,
        private val cachedLoader: (suspend () -> CachedLibrary?)? = null,
    ) : LibraryStore {
        var syncedSongs: List<Song> = emptyList()
        val appliedLyrics = mutableListOf<ScannedSongLyrics>()
        val lyricsBatchStarted = CompletableDeferred<Unit>()
        var lyricsBatchGate: CompletableDeferred<Unit>? = null
        var clearCompleted = false

        override suspend fun loadCached(): CachedLibrary? =
            cachedLoader?.invoke() ?: cached

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
            appliedLyrics.clear()
            clearCompleted = true
        }

        override suspend fun applyLyricsBatch(batch: List<ScannedSongLyrics>) {
            lyricsBatchStarted.complete(Unit)
            lyricsBatchGate?.await()
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
        var duringPrune: (() -> Unit)? = null
        var prefetchedVideoCoverUris: List<String> = emptyList()
        override fun hasAudioReadPermission(): Boolean = true
        override fun canReadTree(treeUri: Uri): Boolean = true
        override fun currentTimeMillis(): Long = 1_234L
        override fun playStats(songId: String): PlayStats = PlayStats(0, 0)
        override fun clearTransientCache() = Unit
        override fun pruneAlbumArtCache(songs: List<Song>) {
            prunedSongIds = songs.map(Song::id)
            duringPrune?.invoke()
        }
        override fun enqueueVideoCoverPosterPrefetch(videoCoverUris: Collection<String>) {
            prefetchedVideoCoverUris = videoCoverUris.toList()
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
