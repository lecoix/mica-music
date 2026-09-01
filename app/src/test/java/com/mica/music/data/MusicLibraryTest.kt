package com.mica.music.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.CachedLibrary
import com.mica.music.data.local.LibrarySyncResult
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.preferences.PreferencesTestFixtures
import com.mica.music.data.scanner.CoverColorExtractor
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
    fun coldSongRestoreDoesNotPrepareUnrelatedBrowseGroups() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferencesTestFixtures.clearMicaSettings(context)
        val song = SongFixtures.song("cached").copy(artist = "Song Artist", album = "Song Album")
        val library = library(
            ControlledScanner(),
            FakeLibraryStore(
                cached = CachedLibrary(
                    songs = listOf(song),
                    lastScanAtMs = 100,
                    lastScanSource = ScanSource.DEVICE,
                    totalSizeMb = 1,
                    artistGroups = listOf(BrowseGroup("Persisted Artist", "1 song", 1)),
                    albumGroups = listOf(BrowseGroup("Persisted Album", "Persisted Artist", 1)),
                    browseArtistConfigKey = ArtistSplitConfig().cacheKey(),
                ),
            ),
        )

        library.loadCachedLibrary(StartupBrowseTarget.NONE)

        assertEquals(listOf("cached"), library.songs.map { it.id })
        assertEquals(
            listOf("Song Artist"),
            library.artistGroupPresentation(ArtistBrowseSortField.TITLE, SortDirection.ASC).groups.map { it.title },
        )
        assertEquals(
            listOf("Song Album"),
            library.albumGroupPresentation(AlbumBrowseSortField.TITLE, SortDirection.ASC).groups.map { it.title },
        )
        library.release()
    }

    @Test
    fun coldAlbumRestorePreparesOnlyTheAlbumBrowseGroups() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferencesTestFixtures.clearMicaSettings(context)
        val song = SongFixtures.song("cached").copy(artist = "Song Artist", album = "Song Album")
        val library = library(
            ControlledScanner(),
            FakeLibraryStore(
                cached = CachedLibrary(
                    songs = listOf(song),
                    lastScanAtMs = 100,
                    lastScanSource = ScanSource.DEVICE,
                    totalSizeMb = 1,
                    artistGroups = listOf(BrowseGroup("Persisted Artist", "1 song", 1)),
                    albumGroups = listOf(
                        BrowseGroup("Persisted Z", "Persisted Artist", 1),
                        BrowseGroup("Persisted A", "Persisted Artist", 1),
                    ),
                    browseArtistConfigKey = ArtistSplitConfig().cacheKey(),
                    albumBrowseSortField = AlbumBrowseSortField.TITLE,
                    albumBrowseSortDirection = SortDirection.ASC,
                    albumBrowseFastScrollSectionTargets = mapOf("Z" to 0, "A" to 1),
                ),
            ),
        )

        library.loadCachedLibrary(StartupBrowseTarget.ALBUMS)

        assertEquals(
            listOf("Persisted Z", "Persisted A"),
            library.albumGroupPresentation(AlbumBrowseSortField.TITLE, SortDirection.ASC).groups.map { it.title },
        )
        assertEquals(
            listOf("Song Artist"),
            library.artistGroupPresentation(ArtistBrowseSortField.TITLE, SortDirection.ASC).groups.map { it.title },
        )
        library.release()
    }

    @Test
    fun coldCacheHydratesBrowseGroupsWithoutRebuildingThem() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferencesTestFixtures.clearMicaSettings(context)
        val song = SongFixtures.song("cached").copy(artist = "Song Metadata Artist", album = "Song Metadata Album")
        val persistedArtist = BrowseGroup("Persisted Artist", "1 首", 1)
        val persistedAlbum = BrowseGroup("Persisted Album", "Persisted Artist", 1)
        val store = FakeLibraryStore(
            cached = CachedLibrary(
                songs = listOf(song),
                lastScanAtMs = 100,
                lastScanSource = ScanSource.DEVICE,
                totalSizeMb = 1,
                artistGroups = listOf(persistedArtist),
                albumGroups = listOf(persistedAlbum),
                browseArtistConfigKey = ArtistSplitConfig().cacheKey(),
                artistBrowseSortField = ArtistBrowseSortField.TITLE,
                artistBrowseSortDirection = SortDirection.ASC,
                artistBrowseFastScrollSectionTargets = mapOf("P" to 0),
                albumBrowseSortField = AlbumBrowseSortField.TITLE,
                albumBrowseSortDirection = SortDirection.ASC,
                albumBrowseFastScrollSectionTargets = mapOf("P" to 0),
            ),
        )
        val library = library(ControlledScanner(), store)

        library.loadCachedLibrary()
        library.prewarmBrowseGroupCache()

        assertEquals(
            listOf("Persisted Artist"),
            library.artistGroupPresentation(ArtistBrowseSortField.TITLE, SortDirection.ASC).groups.map { it.title },
        )
        assertEquals(
            listOf("Persisted Album"),
            library.albumGroupPresentation(AlbumBrowseSortField.TITLE, SortDirection.ASC).groups.map { it.title },
        )
        assertEquals(0, store.browseGroupUpdateCount)
        library.release()
    }

    @Test
    fun changedBrowseSortRewritesTheReadyPresentationSnapshot() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferencesTestFixtures.clearMicaSettings(context)
        val store = FakeLibraryStore(
            cached = CachedLibrary(
                songs = listOf(
                    SongFixtures.song("a").copy(album = "Album A"),
                    SongFixtures.song("z").copy(album = "Album Z"),
                ),
                lastScanAtMs = 100,
                lastScanSource = ScanSource.DEVICE,
                totalSizeMb = 1,
                artistGroups = listOf(BrowseGroup("Artist", "2 songs", 2)),
                albumGroups = listOf(BrowseGroup("Album A", "Artist", 1), BrowseGroup("Album Z", "Artist", 1)),
                browseArtistConfigKey = ArtistSplitConfig().cacheKey(),
                artistBrowseSortField = ArtistBrowseSortField.TITLE,
                artistBrowseSortDirection = SortDirection.ASC,
                artistBrowseFastScrollSectionTargets = mapOf("A" to 0),
                albumBrowseSortField = AlbumBrowseSortField.TITLE,
                albumBrowseSortDirection = SortDirection.ASC,
                albumBrowseFastScrollSectionTargets = mapOf("A" to 0, "Z" to 1),
            ),
        )
        val library = library(ControlledScanner(), store)
        library.loadCachedLibrary()
        LibraryBrowseSettings.setAlbumBrowseSort(context, AlbumBrowseSortField.TITLE, SortDirection.DESC)

        library.albumGroupPresentation(AlbumBrowseSortField.TITLE, SortDirection.DESC)
        library.prewarmBrowseGroupCache()

        assertEquals(1, store.browseGroupUpdateCount)
        assertEquals(SortDirection.DESC, store.updatedAlbumSortDirection)
        library.release()
    }

    @Test
    fun staleBrowsePrewarmCannotOverwriteACompletedNewerScan() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferencesTestFixtures.clearMicaSettings(context)
        val oldSong = SongFixtures.song("browse-old").copy(album = "Old Album", artist = "Old Artist")
        val freshSong = SongFixtures.song("browse-fresh").copy(album = "Fresh Album", artist = "Fresh Artist")
        val scanner = ControlledScanner()
        val store = FakeLibraryStore(
            cached = CachedLibrary(
                songs = listOf(oldSong),
                lastScanAtMs = 100,
                lastScanSource = ScanSource.DEVICE,
                totalSizeMb = 1,
                artistGroups = listOf(BrowseGroup("Old Artist", "1 song", 1)),
                albumGroups = listOf(BrowseGroup("Old Album", "Old Artist", 1)),
                browseArtistConfigKey = ArtistSplitConfig().cacheKey(),
                albumBrowseSortField = AlbumBrowseSortField.TITLE,
                albumBrowseSortDirection = SortDirection.ASC,
            ),
        )
        val library = library(scanner, store)
        library.loadCachedLibrary()
        LibraryBrowseSettings.setAlbumBrowseSort(context, AlbumBrowseSortField.TITLE, SortDirection.DESC)
        library.albumGroupPresentation(AlbumBrowseSortField.TITLE, SortDirection.DESC)
        store.browseGroupUpdateGate = CompletableDeferred()

        val prewarm = async { library.prewarmBrowseGroupCache() }
        runCurrent()
        assertTrue(store.browseGroupUpdateStarted.isCompleted)

        val scan = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(freshSong), totalSizeMb = 2))
        runCurrent()

        if (scan.isCompleted) {
            assertEquals(listOf("Fresh Album"), store.persistedAlbumTitles)
        }
        store.browseGroupUpdateGate?.complete(Unit)
        prewarm.await()
        scan.await()

        assertEquals(listOf("Fresh Album"), store.persistedAlbumTitles)
        library.release()
    }

    @Test
    fun staleArtistRulesRejectPersistedBrowseGroupsAndRebuildOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferencesTestFixtures.clearMicaSettings(context)
        val song = SongFixtures.song("cached").copy(artist = "Current Artist", album = "Current Album")
        val store = FakeLibraryStore(
            cached = CachedLibrary(
                songs = listOf(song),
                lastScanAtMs = 100,
                lastScanSource = ScanSource.DEVICE,
                totalSizeMb = 1,
                artistGroups = listOf(BrowseGroup("Stale Artist", "1 首", 1)),
                albumGroups = listOf(BrowseGroup("Stale Album", "Stale Artist", 1)),
                browseArtistConfigKey = "stale-rules",
            ),
        )
        val library = library(ControlledScanner(), store)

        library.loadCachedLibrary()
        library.prewarmBrowseGroupCache()

        assertEquals(
            listOf("Current Artist"),
            library.artistGroupPresentation(ArtistBrowseSortField.TITLE, SortDirection.ASC).groups.map { it.title },
        )
        assertEquals(1, store.browseGroupUpdateCount)
        library.release()
    }

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
    fun cachedSortMismatchRepairsPresentationWithoutRewritingLibrary() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferencesTestFixtures.clearMicaSettings(context)
        LibraryBrowseSettings.setSongSort(context, SongSortField.TITLE, SortDirection.DESC)
        val store = FakeLibraryStore(
            cached = CachedLibrary(
                songs = listOf(
                    SongFixtures.song(id = "a", title = "Alpha"),
                    SongFixtures.song(id = "b", title = "Beta"),
                ),
                lastScanAtMs = 100,
                lastScanSource = ScanSource.DEVICE,
                totalSizeMb = 1,
            ),
        )
        val library = library(ControlledScanner(), store)

        library.loadCachedLibrary()
        runCurrent()

        assertEquals(listOf("b", "a"), library.songs.map { it.id })
        assertEquals(0, store.fullSaveCount)
        assertEquals(listOf("b", "a"), store.presentationSongIds)
        assertEquals(SongSortField.TITLE, store.presentationSortField)
        assertEquals(SortDirection.DESC, store.presentationSortDirection)
        library.release()
    }

    @Test
    fun lyricsAreLoadedOnceAndThenServedFromTheBoundedCache() = runTest {
        SharedLyricsMemoryCache.clear()
        val fullSong = SongFixtures.song("lazy-cache")
        val summary = fullSong.copy(lyricsDocument = LyricsDocument(), lyricsLoaded = false)
        val store = FakeLibraryStore(
            cached = CachedLibrary(listOf(summary), 100, ScanSource.DEVICE, 1),
        ).also { it.lyricsDocument = fullSong.lyricsDocument }
        val library = library(ControlledScanner(), store)
        library.loadCachedLibrary()

        val first = library.songWithLyrics(library.songs.single())
        val second = library.songWithLyrics(library.songs.single())

        assertEquals(fullSong.lyricsDocument, first.lyricsDocument)
        assertEquals(fullSong.lyricsDocument, second.lyricsDocument)
        assertEquals(1, store.lyricsLoadCount)
        library.release()
    }

    @Test
    fun parserUpgradeDoesNotReuseLyricsCachedFromPreviousDataVersion() = runTest {
        SharedLyricsMemoryCache.clear()
        val summary = SongFixtures.song("parser-cache")
            .copy(lyricsDocument = LyricsDocument(), lyricsLoaded = false)
        val oldLyrics = SongFixtures.song("old-lyrics").lyricsDocument
        val newLyrics = LyricsDocument()
        val scanner = ControlledScanner()
        val store = FakeLibraryStore(
            cached = CachedLibrary(listOf(summary), 100, ScanSource.DEVICE, 1),
        ).also { it.lyricsDocument = oldLyrics }
        val environment = FakeScanEnvironment(parserVersion = 0)
        val library = library(scanner, store, environment)
        library.loadCachedLibrary()

        assertEquals(oldLyrics, library.songWithLyrics(summary).lyricsDocument)
        store.lyricsDocument = newLyrics

        val scan = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(summary), totalSizeMb = 1))
        scan.await()

        assertEquals(CURRENT_LYRICS_PARSER_VERSION, library.lyricsDataVersion)
        assertEquals(newLyrics, library.songWithLyrics(summary).lyricsDocument)
        assertEquals(2, store.lyricsLoadCount)
        library.release()
    }

    @Test
    fun successfulLyricsRetryDoesNotReuseCachedLyricsFromTheSameRevision() = runTest {
        SharedLyricsMemoryCache.clear()
        val summary = SongFixtures.song("same-revision-retry")
            .copy(lyricsDocument = LyricsDocument(), lyricsLoaded = false)
        val oldLyrics = SongFixtures.song("old-retry-lyrics").lyricsDocument
        val newLyrics = SongFixtures.song("new-retry-lyrics").lyricsDocument
        val scanner = ControlledScanner()
        val store = FakeLibraryStore(
            cached = CachedLibrary(listOf(summary), 100, ScanSource.DEVICE, 1),
        ).also { it.lyricsDocument = oldLyrics }
        val library = library(scanner, store)
        library.loadCachedLibrary()

        assertEquals(oldLyrics, library.songWithLyrics(summary).lyricsDocument)

        val scan = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().onLyricsBatch?.invoke(
            LyricsScanBatch(
                completed = listOf(
                    ScannedSongLyrics(
                        summary.id,
                        summary.lyricsCacheRevision,
                        LyricsSlots(embedded = newLyrics),
                    ),
                ),
                readFailedCount = 0,
            ),
        )
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(summary), totalSizeMb = 1))
        scan.await()

        assertEquals(newLyrics, library.songWithLyrics(summary).lyricsDocument)
        assertEquals(2, store.lyricsLoadCount)
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
    fun sameIdsWithChangedMetadataAdvanceQueueRevisionAndInvalidateBrowseCache() = runTest {
        val scanner = ControlledScanner()
        val library = library(scanner, FakeLibraryStore())
        val oldSong = SongFixtures.song("same-id").copy(artist = "Old Artist")

        val firstScan = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[0].result.complete(ScanResult(listOf(oldSong), totalSizeMb = 1))
        firstScan.await()
        val firstRevision = library.queueMetadataRevision
        assertEquals("Old Artist", library.artistGroupPresentation(
            ArtistBrowseSortField.TITLE,
            SortDirection.ASC,
        ).groups.single().title)

        val secondScan = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[1].result.complete(
            ScanResult(listOf(oldSong.copy(artist = "New Artist")), totalSizeMb = 1),
        )
        secondScan.await()

        assertEquals(listOf("same-id"), library.songIds)
        assertEquals("New Artist", library.songById("same-id")?.artist)
        assertNull(library.songById("missing"))
        assertTrue(library.queueMetadataRevision > firstRevision)
        assertEquals("New Artist", library.artistGroupPresentation(
            ArtistBrowseSortField.TITLE,
            SortDirection.ASC,
        ).groups.single().title)
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

        assertFalse(library.hasScanned)
        assertEquals("broken media provider", library.lastScanError)
        assertFalse(library.isScanning)
        assertTrue(library.songs.isEmpty())
        library.release()
    }

    @Test
    fun scannerFailureKeepsPreviousSuccessfulSnapshot() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val library = library(scanner, store)
        val kept = SongFixtures.song("kept")

        val first = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[0].result.complete(ScanResult(listOf(kept), totalSizeMb = 7))
        first.await()
        assertEquals(listOf("kept"), library.songs.map { it.id })
        assertEquals(7, library.totalSizeMb)
        assertTrue(library.hasScanned)

        val failed = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[1].result.completeExceptionally(
            IllegalStateException("broken media provider"),
        )
        failed.await()

        assertEquals(listOf("kept"), library.songs.map { it.id })
        assertEquals(7, library.totalSizeMb)
        assertTrue(library.hasScanned)
        assertEquals("broken media provider", library.lastScanError)
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
        assertEquals(1, scanner.deviceRequests.size)
        assertEquals(1, store.requests.size)

        store.requests[0].release.complete(Unit)
        oldScan.await()
        runCurrent()
        scanner.deviceRequests[1].result.complete(
            ScanResult(listOf(SongFixtures.song("new")), 20),
        )
        runCurrent()
        assertEquals(listOf("new"), store.requests[1].songs.map { it.id })
        store.requests[1].release.complete(Unit)
        newScan.await()

        assertEquals(listOf("new"), library.songs.map { it.id })
        assertEquals(listOf("new"), store.persistedSongs.map { it.id })
        assertEquals(listOf("old", "new"), store.requests.map { it.songs.single().id })
        library.release()
    }

    @Test
    fun latestCatalogChangeIsPersistedAfterAnOlderSaveAlreadyStarted() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferencesTestFixtures.clearMicaSettings(context)
        val scanner = ControlledScanner()
        val store = BlockingLibraryStore()
        val library = library(scanner, store)
        val a = SongFixtures.song(id = "a", title = "Alpha")
        val b = SongFixtures.song(id = "b", title = "Beta")

        val scan = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(a, b), totalSizeMb = 1))
        runCurrent()
        store.requests.single().release.complete(Unit)
        scan.await()

        library.updateSort(SongSortField.CUSTOM, SortDirection.ASC)
        runCurrent()
        assertEquals(listOf("a", "b"), store.requests[1].songs.map { it.id })

        assertTrue(library.moveSongInLibrary(1, 0))
        runCurrent()
        assertEquals(2, store.requests.size)

        store.requests[1].release.complete(Unit)
        runCurrent()
        assertEquals(listOf("b", "a"), store.requests[2].songs.map { it.id })
        store.requests[2].release.complete(Unit)
        runCurrent()

        assertEquals(listOf("b", "a"), store.persistedSongs.map { it.id })
        library.release()
    }

    @Test
    fun removingLastSongPublishesAndPersistsEmptyCatalogAfterOlderStoreWrite() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferencesTestFixtures.clearMicaSettings(context)
        val scanner = ControlledScanner()
        val store = BlockingLibraryStore()
        val library = library(scanner, store)
        val onlySong = SongFixtures.song(id = "only", title = "Only")

        val scan = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(onlySong), totalSizeMb = 1))
        runCurrent()
        store.requests.single().release.complete(Unit)
        scan.await()

        library.updateSort(SongSortField.TITLE, SortDirection.DESC)
        runCurrent()
        assertEquals(listOf("only"), store.requests[1].songs.map { it.id })

        library.removeSongFromLibrary(onlySong.id)
        runCurrent()

        assertTrue(library.songs.isEmpty())
        assertTrue(library.songIds.isEmpty())
        assertEquals(2, store.requests.size)

        store.requests[1].release.complete(Unit)
        runCurrent()
        assertTrue(store.requests[2].songs.isEmpty())
        store.requests[2].release.complete(Unit)
        runCurrent()

        assertTrue(store.persistedSongs.isEmpty())
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

    @Test
    fun changingSortDoesNotRewriteTheFullLibrarySnapshot() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferencesTestFixtures.clearMicaSettings(context)
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val library = library(scanner, store)

        val scan = async { library.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(
            ScanResult(
                listOf(
                    SongFixtures.song(id = "a", title = "Alpha"),
                    SongFixtures.song(id = "b", title = "Beta"),
                ),
                totalSizeMb = 1,
            ),
        )
        scan.await()

        library.updateSort(SongSortField.TITLE, SortDirection.DESC)
        runCurrent()

        assertEquals(0, store.fullSaveCount)
        assertEquals(listOf("b", "a"), store.presentationSongIds)
        assertEquals(SongSortField.TITLE, store.presentationSortField)
        assertEquals(SortDirection.DESC, store.presentationSortDirection)
        library.release()
    }

    @Test
    fun coverColorRepairWritesFallbackArtworkSongAndSkipsUsableColor() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferencesTestFixtures.clearMicaSettings(context)
        val fallback = SongFixtures.song("cached").copy(
            albumArtUri = "file:///cover.jpg",
            coverColorArgb = CoverColorExtractor.FALLBACK_ARGB,
        )
        val store = FakeLibraryStore(
            cached = CachedLibrary(
                songs = listOf(fallback),
                lastScanAtMs = 100,
                lastScanSource = ScanSource.DEVICE,
                totalSizeMb = 1,
            ),
        )
        val library = library(ControlledScanner(), store)
        library.loadCachedLibrary(StartupBrowseTarget.NONE)
        runCurrent()

        val extracted = 0xFFB13B66.toInt()
        library.applyCoverColorArgb(fallback.id, fallback.albumArtUri, extracted)
        runCurrent()

        assertEquals(extracted, library.songById(fallback.id)?.coverColorArgb)
        assertEquals(listOf(fallback.id to extracted), store.coverColorWrites)

        library.applyCoverColorArgb(fallback.id, fallback.albumArtUri, 0xFF111111.toInt())
        runCurrent()

        assertEquals(extracted, library.songById(fallback.id)?.coverColorArgb)
        assertEquals(listOf(fallback.id to extracted), store.coverColorWrites)
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
            onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
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
            onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
        ): ScanResult = error("folder scan not expected")
    }

    private class FakeLibraryStore(
        private val cached: CachedLibrary? = null,
    ) : LibraryStore {
        var syncedSongs: List<Song> = emptyList()
        var syncedSource: ScanSource? = null
        var fullSaveCount: Int = 0
        var presentationSongIds: List<String> = emptyList()
        var presentationSortField: SongSortField? = null
        var presentationSortDirection: SortDirection? = null
        var lyricsDocument: LyricsDocument = LyricsDocument()
        var lyricsLoadCount: Int = 0
        var browseGroupUpdateCount: Int = 0
        var updatedAlbumSortDirection: SortDirection? = null
        var persistedAlbumTitles: List<String> = cached?.albumGroups?.map(BrowseGroup::title).orEmpty()
        var updatedAlbumTitles: List<String> = emptyList()
        val coverColorWrites = mutableListOf<Pair<String, Int>>()
        val browseGroupUpdateStarted = CompletableDeferred<Unit>()
        var browseGroupUpdateGate: CompletableDeferred<Unit>? = null

        override suspend fun loadCached(): CachedLibrary? = cached

        override suspend fun loadLyrics(
            songId: String,
            revision: String,
            priority: List<LyricsSlot>,
        ): LyricsDocument {
            lyricsLoadCount++
            return lyricsDocument
        }

        override suspend fun applyLyricsBatch(batch: List<ScannedSongLyrics>) {
            batch.lastOrNull()?.let { lyricsDocument = it.slots.selected() }
        }

        override suspend fun save(
            songs: List<Song>,
            lastScanAtMs: Long,
            lastScanSource: ScanSource,
            totalSizeMb: Int,
            sortField: SongSortField?,
            sortDirection: SortDirection?,
            fastScrollSectionTargets: Map<String, Int>?,
        ): LibrarySyncResult {
            fullSaveCount++
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
            persistedAlbumTitles = songs.map(Song::album).distinct()
            return LibrarySyncResult(songs.size, 0, 0, 0)
        }

        override suspend fun updatePresentation(
            songIds: List<String>,
            sortField: SongSortField,
            sortDirection: SortDirection,
            fastScrollSectionTargets: Map<String, Int>?,
        ) {
            presentationSongIds = songIds
            presentationSortField = sortField
            presentationSortDirection = sortDirection
        }

        override suspend fun updateCoverColorArgb(songId: String, coverColorArgb: Int) {
            coverColorWrites += songId to coverColorArgb
        }

        override suspend fun updateBrowseGroups(
            artistGroups: List<BrowseGroup>,
            albumGroups: List<BrowseGroup>,
            artistConfigKey: String,
            artistSortField: ArtistBrowseSortField,
            artistSortDirection: SortDirection,
            artistFastScrollSectionTargets: Map<String, Int>?,
            albumSortField: AlbumBrowseSortField,
            albumSortDirection: SortDirection,
            albumFastScrollSectionTargets: Map<String, Int>?,
        ) {
            browseGroupUpdateCount++
            browseGroupUpdateStarted.complete(Unit)
            browseGroupUpdateGate?.await()
            updatedAlbumTitles = albumGroups.map(BrowseGroup::title)
            persistedAlbumTitles = updatedAlbumTitles
            updatedAlbumSortDirection = albumSortDirection
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

        override suspend fun updatePresentation(
            songIds: List<String>,
            sortField: SongSortField,
            sortDirection: SortDirection,
            fastScrollSectionTargets: Map<String, Int>?,
        ) {
            val request = StoreRequest(songIds.map { SongFixtures.song(it) })
            requests += request
            request.release.await()
            persistedSongs = request.songs
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
        override fun pruneAlbumArtCache(songs: List<Song>) = Unit
        override fun persistLastScanSource(source: ScanSource) = Unit
        override fun lyricsParserVersion(): Int = parserVersion
        override fun persistLyricsParserVersion(version: Int) {
            parserVersion = version
        }
    }
}
