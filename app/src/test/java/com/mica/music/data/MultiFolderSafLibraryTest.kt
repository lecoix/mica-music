package com.mica.music.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.library.SourceIdentityKey
import com.mica.music.data.local.CachedLibrary
import com.mica.music.data.local.LibrarySyncResult
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.preferences.MicaSettingsStore
import com.mica.music.data.preferences.PreferencesTestFixtures
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MultiFolderSafLibraryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesTestFixtures.clearMicaSettings(context)
    }

    @Test
    fun folderSetDeduplicatesAndHandlesParentChildOverlap() {
        val music = LibraryFolderEntry(URI_MUSIC, "Music")
        val jazz = LibraryFolderEntry(URI_MUSIC_JAZZ, "Jazz")
        val download = LibraryFolderEntry(URI_DOWNLOAD, "Download")

        val addedSecond = LibraryFolderSet.add(listOf(music), download)
        assertEquals(LibraryFolderAddOutcome.ADDED, addedSecond.outcome)
        assertEquals(listOf(music, download), addedSecond.folders)

        val duplicate = LibraryFolderSet.add(addedSecond.folders, music)
        assertEquals(LibraryFolderAddOutcome.ALREADY_PRESENT, duplicate.outcome)
        assertEquals(listOf(music, download), duplicate.folders)

        val coveredChild = LibraryFolderSet.add(addedSecond.folders, jazz)
        assertEquals(LibraryFolderAddOutcome.COVERED_BY_EXISTING, coveredChild.outcome)
        assertEquals("Music", coveredChild.coveringParentLabel)
        assertEquals(listOf(music, download), coveredChild.folders)

        val replacedByParent = LibraryFolderSet.add(listOf(jazz, download), music)
        assertEquals(LibraryFolderAddOutcome.REPLACED_SUBFOLDERS, replacedByParent.outcome)
        assertEquals(listOf(download, music), replacedByParent.folders)
    }

    @Test
    fun folderSetFormatsLabelsAndDisambiguatesDuplicateRootNames() {
        assertNull(LibraryFolderSet.formatLabel(emptyList()))
        assertEquals("Music", LibraryFolderSet.formatLabel(listOf(LibraryFolderEntry(URI_MUSIC, "Music"))))
        assertEquals(
            "Music、Download",
            LibraryFolderSet.formatLabel(
                listOf(
                    LibraryFolderEntry(URI_MUSIC, "Music"),
                    LibraryFolderEntry(URI_DOWNLOAD, "Download"),
                ),
            ),
        )
        assertEquals(
            "Music、Download 等 3 个文件夹",
            LibraryFolderSet.formatLabel(
                listOf(
                    LibraryFolderEntry(URI_MUSIC, "Music"),
                    LibraryFolderEntry(URI_DOWNLOAD, "Download"),
                    LibraryFolderEntry(URI_NETEASE, "Netease"),
                ),
            ),
        )
        assertEquals(listOf(""), LibraryFolderSet.disambiguateRootLabels(listOf("Music")))
        assertEquals(
            listOf("Music", "Download", "Music (2)"),
            LibraryFolderSet.disambiguateRootLabels(listOf("Music", "Download", "Music")),
        )
    }

    @Test
    fun settingsMigratesLegacySingleFolderAndPersistsMultipleFolders() {
        MicaSettingsStore.prefs(context).edit()
            .putString("library_tree_uri", URI_MUSIC)
            .putString("library_folder_label", "Music")
            .commit()

        assertEquals(
            listOf(LibraryFolderEntry(URI_MUSIC, "Music")),
            LibraryScanSettings.libraryFolders(context),
        )

        val multi = listOf(
            LibraryFolderEntry(URI_MUSIC, "Music"),
            LibraryFolderEntry(URI_DOWNLOAD, "Download"),
        )
        LibraryScanSettings.setLibraryFolders(context, multi)

        assertEquals(multi, LibraryScanSettings.libraryFolders(context))
        assertEquals("Music、Download", LibraryScanSettings.libraryFolderLabel(context))
        assertEquals(Uri.parse(URI_MUSIC), LibraryScanSettings.libraryTreeUri(context))
    }

    @Test
    fun sourceIdentityPreservesSingleFolderKeyAndCanonicalizesMultiFolderOrder() {
        val single = SourceIdentityKey.folders(listOf(URI_MUSIC))
        assertEquals(SourceIdentityKey.folder(URI_MUSIC), single)
        assertEquals(listOf(URI_MUSIC), single?.folderTreeUris())

        val multiAB = SourceIdentityKey.folders(listOf(URI_MUSIC, URI_DOWNLOAD))
        val multiBA = SourceIdentityKey.folders(listOf(URI_DOWNLOAD, URI_MUSIC))
        assertNotNull(multiAB)
        assertEquals(multiAB, multiBA)
        assertEquals(listOf(URI_DOWNLOAD, URI_MUSIC).sorted(), multiAB?.folderTreeUris())
    }

    @Test
    fun multiFolderScanMergesSongsAndFailsClosedWhenAnyFolderIsUnreadable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val songA = SongFixtures.song("doc_a")
        val songB = SongFixtures.song("doc_b")
        val readable = mutableSetOf(URI_MUSIC, URI_DOWNLOAD)
        val scanner = MultiTreeTestScanner(
            songsByTree = mapOf(
                URI_MUSIC to listOf(songA),
                URI_DOWNLOAD to listOf(songB),
            ),
        )
        val store = InMemoryLibraryStore()
        val library = MusicLibrary(
            context = context,
            libraryScanner = scanner,
            libraryStore = store,
            scanEnvironment = MultiTreeScanEnvironment(readable),
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            ownsCoverColorPersistenceSink = false,
        )

        library.setLibraryFolders(listOf(LibraryFolderEntry(URI_MUSIC, "Music")))
        library.scanLibraryFolder()
        assertEquals(listOf("doc_a"), library.songs.map(Song::id))
        assertEquals("Music", library.libraryFolderLabel)

        // Add second folder and scan both.
        library.setLibraryFolders(
            listOf(
                LibraryFolderEntry(URI_MUSIC, "Music"),
                LibraryFolderEntry(URI_DOWNLOAD, "Download"),
            ),
        )
        library.scanLibraryFolder()
        assertEquals(listOf("doc_a", "doc_b"), library.songs.map(Song::id))
        assertEquals("Music、Download", library.libraryFolderLabel)
        assertEquals(2, library.libraryFolders.size)

        // Pending addition with an unreadable folder must fail closed and keep committed [Music, Download].
        library.setLibraryFolders(
            listOf(
                LibraryFolderEntry(URI_MUSIC, "Music"),
                LibraryFolderEntry(URI_DOWNLOAD, "Download"),
                LibraryFolderEntry(URI_NETEASE, "Netease"),
            ),
        )
        library.scanLibraryFolder()
        assertEquals(listOf("doc_a", "doc_b"), library.songs.map(Song::id))
        assertEquals(2, library.libraryFolders.size)
        assertEquals("Music、Download", library.scanLibraryFolderLabel)
        assertTrue(library.lastScanError.orEmpty().contains("Netease"))

        library.release()
    }

    @Test
    fun staleSingleFolderScanCannotOverwriteNewerCommittedMultiFolderScan() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val songOld = SongFixtures.song("doc_old")
        val songNewA = SongFixtures.song("doc_new_a")
        val songNewB = SongFixtures.song("doc_new_b")
        val oldScanStarted = CompletableDeferred<Unit>()
        val releaseOldScan = CompletableDeferred<Unit>()
        val scanner = MultiTreeTestScanner(
            songsByTree = mapOf(
                URI_MUSIC to listOf(songOld),
                URI_DOWNLOAD to listOf(songNewB),
            ),
            onScanTrees = { uris ->
                if (uris.size == 1) {
                    oldScanStarted.complete(Unit)
                    releaseOldScan.await()
                    ScanResult(songs = listOf(songOld), totalSizeMb = 1)
                } else {
                    ScanResult(songs = listOf(songNewA, songNewB), totalSizeMb = 2)
                }
            },
        )
        val store = InMemoryLibraryStore()
        val library = MusicLibrary(
            context = context,
            libraryScanner = scanner,
            libraryStore = store,
            scanEnvironment = MultiTreeScanEnvironment(mutableSetOf(URI_MUSIC, URI_DOWNLOAD)),
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            ownsCoverColorPersistenceSink = false,
        )

        library.setLibraryFolders(listOf(LibraryFolderEntry(URI_MUSIC, "Music")))
        val staleJob = async(dispatcher) { library.scanLibraryFolder() }
        runCurrent()
        oldScanStarted.await()

        // Newer multi-folder selection supersedes stale scan via launchScanLibraryFolder.
        library.setLibraryFolders(
            listOf(
                LibraryFolderEntry(URI_MUSIC, "Music"),
                LibraryFolderEntry(URI_DOWNLOAD, "Download"),
            ),
        )
        library.launchScanLibraryFolder()
        releaseOldScan.complete(Unit)
        staleJob.await()
        runCurrent()

        assertEquals(listOf("doc_new_a", "doc_new_b"), library.songs.map(Song::id))
        assertEquals(listOf("doc_new_a", "doc_new_b"), store.persistedSongs.map(Song::id))
        assertEquals(2, library.libraryFolders.size)
        assertEquals("Music、Download", library.libraryFolderLabel)

        library.release()
    }

    @Test
    fun deviceManualRescanWithNoChangesShowsFolderScanHint() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val existing = SongFixtures.song("ms_1")
        val scanner = MultiTreeTestScanner(deviceSongs = listOf(existing))
        val store = InMemoryLibraryStore()
        val library = MusicLibrary(
            context = context,
            libraryScanner = scanner,
            libraryStore = store,
            scanEnvironment = MultiTreeScanEnvironment(mutableSetOf()),
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            ownsCoverColorPersistenceSink = false,
        )

        // First scan populates the library.
        library.scanDeviceWide()
        assertEquals(listOf("ms_1"), library.songs.map(Song::id))
        library.clearScanSyncSummary()

        // Second manual DEVICE rescan finds 0 changes -> surfaces folder-scan hint.
        library.scanDeviceWide()
        val summary = library.lastScanSyncSummary.orEmpty()
        assertTrue(summary.startsWith("扫描完成"))
        assertTrue(summary.contains("曲库文件夹"))
        assertFalse(library.hasLibraryFolder())

        library.release()
    }

    @Test
    fun reusedCachedSongUpdatesLocationWhenFolderPrefixChanges() {
        val multiFolderSong = SongFixtures.song("doc_1").copy(
            folderPath = "Music/Albums",
            filePath = "Music/Albums/track.flac",
            fileName = "track.flac",
        )
        val singleFolderDraft = com.mica.music.data.scanner.TrackDraft(
            mediaStoreId = 0L,
            title = "Track",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationSec = 180,
            mimeType = "audio/flac",
            displayName = "track.flac",
            sizeBytes = multiFolderSong.sizeBytes,
            bitrateBpsFromStore = 0,
            mediaUri = multiFolderSong.mediaUri,
            coverColorArgb = multiFolderSong.coverColorArgb,
            folderPath = "Albums",
            filePath = "Albums/track.flac",
            dateModifiedMs = multiFolderSong.dateModifiedMs,
        )

        val updated = with(com.mica.music.data.scanner.FolderScanner) {
            multiFolderSong.withDraftLocation(singleFolderDraft)
        }
        assertEquals("Albums", updated.folderPath)
        assertEquals("Albums/track.flac", updated.filePath)
        assertEquals("track.flac", updated.fileName)
        assertEquals(multiFolderSong.title, updated.title)
    }

    private class MultiTreeTestScanner(
        private val songsByTree: Map<String, List<Song>> = emptyMap(),
        private val deviceSongs: List<Song> = emptyList(),
        private val onScanTrees: (suspend (List<Uri>) -> ScanResult)? = null,
    ) : LibraryScanner {
        override suspend fun scanDevice(
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
            forceRefreshLyrics: Boolean,
            forceRefreshArtwork: Boolean,
            onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
        ): ScanResult = ScanResult(songs = deviceSongs, totalSizeMb = deviceSongs.size)

        override suspend fun scanFolder(
            treeUri: Uri,
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
            forceRefreshLyrics: Boolean,
            forceRefreshArtwork: Boolean,
            onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
        ): ScanResult {
            val songs = songsByTree[treeUri.toString()].orEmpty()
            return ScanResult(songs = songs, totalSizeMb = songs.size)
        }

        override suspend fun scanFolders(
            treeUris: List<Uri>,
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
            forceRefreshLyrics: Boolean,
            forceRefreshArtwork: Boolean,
            onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
        ): ScanResult = onScanTrees?.invoke(treeUris)
            ?: super.scanFolders(
                treeUris = treeUris,
                cachedSongs = cachedSongs,
                onProgress = onProgress,
                forceRefreshLyrics = forceRefreshLyrics,
                forceRefreshArtwork = forceRefreshArtwork,
                onLyricsBatch = onLyricsBatch,
            )
    }

    private class InMemoryLibraryStore : LibraryStore {
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
            val previousIds = persistedSongs.mapTo(hashSetOf(), Song::id)
            val nextIds = songs.mapTo(hashSetOf(), Song::id)
            val added = (nextIds - previousIds).size
            val removed = (previousIds - nextIds).size
            val unchanged = (nextIds intersect previousIds).size
            persistedSongs = songs
            return LibrarySyncResult(added = added, updated = 0, removed = removed, unchanged = unchanged)
        }

        override suspend fun updatePresentation(
            songIds: List<String>,
            sortField: SongSortField,
            sortDirection: SortDirection,
            fastScrollSectionTargets: Map<String, Int>?,
        ) = Unit

        override suspend fun clear() {
            persistedSongs = emptyList()
        }
    }

    private class MultiTreeScanEnvironment(
        private val readableUris: Set<String>,
    ) : ScanEnvironment {
        override fun hasAudioReadPermission(): Boolean = true
        override fun canReadTree(treeUri: Uri): Boolean = treeUri.toString() in readableUris
        override fun currentTimeMillis(): Long = 10_000L
        override fun playStats(songId: String): PlayStats = PlayStats(0, 0)
        override fun clearTransientCache() = Unit
        override fun pruneAlbumArtCache(songs: List<Song>) = Unit
        override fun persistLastScanSource(source: ScanSource) = Unit
        override fun lyricsParserVersion(): Int = CURRENT_LYRICS_PARSER_VERSION
        override fun persistLyricsParserVersion(version: Int) = Unit
    }

    private companion object {
        const val URI_MUSIC = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        const val URI_MUSIC_JAZZ = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FJazz"
        const val URI_DOWNLOAD = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        const val URI_NETEASE = "content://com.android.externalstorage.documents/tree/primary%3ANetease"
    }
}
