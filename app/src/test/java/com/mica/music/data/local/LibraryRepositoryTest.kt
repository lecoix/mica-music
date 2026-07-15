package com.mica.music.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.ScanSource
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.LyricsSlots
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.LyricsSlot
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryTest {

    private lateinit var database: MicaDatabase
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LibraryRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun incrementalSyncReportsAddsUpdatesRemovalsAndPreservesOrder() = runTest {
        val initial = SongFixtures.queue(3)
        assertEquals(
            LibrarySyncResult(added = 3, updated = 0, removed = 0, unchanged = 0),
            repository.syncIncremental(initial, 100, ScanSource.DEVICE, 3),
        )

        val changed = listOf(
            initial[2],
            initial[0].copy(title = "Updated"),
            SongFixtures.song(id = "song-new", queueOrder = 9),
        )
        assertEquals(
            LibrarySyncResult(added = 1, updated = 2, removed = 1, unchanged = 0),
            repository.syncIncremental(changed, 200, ScanSource.FOLDER, 4),
        )

        val cached = repository.loadCached()!!
        assertEquals(changed.map { it.id }, cached.songs.map { it.id })
        assertEquals(ScanSource.FOLDER, cached.lastScanSource)
        assertEquals(4, cached.totalSizeMb)
    }

    @Test
    fun clearMakesCacheUnavailable() = runTest {
        repository.save(SongFixtures.queue(2), 100, ScanSource.DEVICE, 2)
        repository.clear()
        assertNull(repository.loadCached())
    }

    @Test
    fun songByIdLoadsSingleSongWithLyrics() = runTest {
        val song = SongFixtures.song("with-lyrics")
        repository.save(listOf(song), 100, ScanSource.DEVICE, 1)

        val loaded = repository.songById(song.id)

        assertEquals(song.lyricsDocument, loaded?.lyricsDocument)
        assertEquals(song.id, loaded?.id)
    }

    @Test
    fun cachedCatalogOmitsLyricsUntilRequested() = runTest {
        val song = SongFixtures.song("summary-only")
        repository.save(listOf(song), 100, ScanSource.DEVICE, 1)

        val cachedSong = repository.loadCached()!!.songs.single()

        assertEquals(LyricsDocument(), cachedSong.lyricsDocument)
        assertEquals(false, cachedSong.lyricsLoaded)
        assertEquals(song.lyricsDocument, repository.lyricsById(song.id))
    }

    @Test
    fun syncingUnloadedSummaryPreservesStoredLyrics() = runTest {
        val song = SongFixtures.song("preserve-lazy")
        repository.save(listOf(song), 100, ScanSource.DEVICE, 1)
        val summary = repository.loadCached()!!.songs.single()

        repository.syncIncremental(
            songs = listOf(summary.copy(title = "Updated without lyrics")),
            lastScanAtMs = 200,
            lastScanSource = ScanSource.DEVICE,
            totalSizeMb = 1,
        )

        val stored = repository.songById(song.id)!!
        assertEquals("Updated without lyrics", stored.title)
        assertEquals(song.lyricsDocument, stored.lyricsDocument)
    }

    @Test
    fun threeSlotsAreStoredAndDefaultSelectionPrefersExternalTtml() = runTest {
        val song = SongFixtures.song("three-slots")
        repository.save(listOf(song.copy(lyricsLoaded = false)), 100, ScanSource.DEVICE, 1)
        val embedded = song.lyricsDocument.copy(format = LyricsFormat.SYLT, origin = LyricsOrigin.EMBEDDED)
        val lrc = song.lyricsDocument.copy(format = LyricsFormat.LRC, origin = LyricsOrigin.EXTERNAL)
        val ttml = song.lyricsDocument.copy(format = LyricsFormat.TTML, origin = LyricsOrigin.EXTERNAL)

        repository.applyLyricsBatch(
            listOf(ScannedSongLyrics(song.id, song.lyricsCacheRevision, LyricsSlots(embedded, lrc, ttml))),
        )
        repository.commitScan(
            listOf(song.copy(lyricsLoaded = false)),
            100,
            ScanSource.DEVICE,
            1,
        )

        assertEquals(3, database.songLyricsDao().getBySongId(song.id).size)
        assertEquals(ttml, repository.lyricsById(song.id))
        assertEquals(embedded, repository.lyricsById(song.id, listOf(LyricsSlot.EMBEDDED)))
        assertEquals(ttml, repository.lyricsById(song.id, revision = "stale-revision"))
    }

    @Test
    fun completedBatchIsVisibleImmediatelyAndRevisionDoesNotHideConservativeLyrics() = runTest {
        val oldSong = SongFixtures.song("staged").copy(dateModifiedMs = 1L, lyricsLoaded = false)
        val oldLyrics = SongFixtures.song("old-lyrics").lyricsDocument.copy(
            format = LyricsFormat.LRC,
            origin = LyricsOrigin.EXTERNAL,
        )
        repository.save(
            listOf(oldSong.copy(lyricsDocument = oldLyrics, lyricsLoaded = true)),
            100,
            ScanSource.DEVICE,
            1,
        )

        val newSong = oldSong.copy(dateModifiedMs = 2L)
        val newLyrics = oldLyrics.copy(format = LyricsFormat.TTML)
        repository.applyLyricsBatch(
            listOf(
                ScannedSongLyrics(
                    newSong.id,
                    newSong.lyricsCacheRevision,
                    LyricsSlots(externalTtml = newLyrics),
                ),
            ),
        )

        assertEquals(newLyrics, repository.lyricsById(oldSong.id, revision = oldSong.lyricsCacheRevision))
        assertEquals(newLyrics, repository.lyricsById(newSong.id, revision = newSong.lyricsCacheRevision))

        repository.commitScan(listOf(newSong), 200, ScanSource.DEVICE, 1)

        assertEquals(newLyrics, repository.lyricsById(newSong.id, revision = newSong.lyricsCacheRevision))
        assertEquals(newLyrics, repository.lyricsById(oldSong.id, revision = oldSong.lyricsCacheRevision))
    }

    @Test
    fun authoritativeEmptyBatchDeletesAllThreeSlots() = runTest {
        val song = SongFixtures.song("empty-complete")
        val document = song.lyricsDocument
        repository.save(listOf(song.copy(lyricsLoaded = false)), 100, ScanSource.DEVICE, 1)
        repository.applyLyricsBatch(
            listOf(
                ScannedSongLyrics(
                    song.id,
                    song.lyricsCacheRevision,
                    LyricsSlots(document, document, document),
                ),
            ),
        )
        assertEquals(3, database.songLyricsDao().getBySongId(song.id).size)

        repository.applyLyricsBatch(
            listOf(ScannedSongLyrics(song.id, song.lyricsCacheRevision, LyricsSlots())),
        )

        assertEquals(0, database.songLyricsDao().getBySongId(song.id).size)
        assertEquals(LyricsDocument(), repository.lyricsById(song.id))
    }

    @Test
    fun presentationUpdatePreservesLyricsPayloadAndUpdatesCachedOrder() = runTest {
        val songs = SongFixtures.queue(2)
        repository.save(songs, 100, ScanSource.DEVICE, 2)
        val lyricsBefore = database.songDao().getById(songs[0].id)!!.lyricsJson

        repository.updatePresentation(
            songIds = songs.reversed().map { it.id },
            sortField = SongSortField.SIZE,
            sortDirection = SortDirection.DESC,
            fastScrollSectionTargets = mapOf("#" to 0),
        )

        val cached = repository.loadCached()!!
        assertEquals(songs.reversed().map { it.id }, cached.songs.map { it.id })
        assertEquals(SongSortField.SIZE, cached.sortField)
        assertEquals(SortDirection.DESC, cached.sortDirection)
        assertEquals(mapOf("#" to 0), cached.fastScrollSectionTargets)
        assertEquals(lyricsBefore, database.songDao().getById(songs[0].id)!!.lyricsJson)
    }

    @Test
    fun daoReplaceAllIsAtomicFromCallerPerspective() = runTest {
        val dao = database.songDao()
        dao.replaceAll(SongFixtures.queue(2).mapIndexed { index, song -> song.toEntity(index) })
        dao.replaceAll(listOf(SongFixtures.song("replacement").toEntity(0)))
        assertEquals(listOf("replacement"), dao.getAllOrdered().map { it.id })
    }
}
