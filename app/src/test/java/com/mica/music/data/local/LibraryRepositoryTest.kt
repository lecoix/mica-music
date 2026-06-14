package com.mica.music.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.ScanSource
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
    fun daoReplaceAllIsAtomicFromCallerPerspective() = runTest {
        val dao = database.songDao()
        dao.replaceAll(SongFixtures.queue(2).mapIndexed { index, song -> song.toEntity(index) })
        dao.replaceAll(listOf(SongFixtures.song("replacement").toEntity(0)))
        assertEquals(listOf("replacement"), dao.getAllOrdered().map { it.id })
    }
}
