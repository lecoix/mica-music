package com.mica.music.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.ScanSource
import com.mica.music.data.SongIdentity
import com.mica.music.data.UserPlaylist
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
class SongIdentityMigrationTest {

    private lateinit var context: Context
    private lateinit var database: MicaDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mica_song_identity_migration", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        database = Room.inMemoryDatabaseBuilder(context, MicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        context.getSharedPreferences("mica_song_identity_migration", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun migratesSongAndLazyLyricsReferencesFromLegacyId() = runTest {
        val mediaUri = "content://provider/library/song.flac"
        val legacyId = SongIdentity.legacyDocumentId(mediaUri)
        val song = SongFixtures.song(legacyId).copy(mediaUri = mediaUri)
        LibraryRepository(database).save(listOf(song), 100, ScanSource.DEVICE, 1)
        PlaylistRepository(database).replaceAll(
            listOf(
                UserPlaylist(
                    id = "playlist",
                    name = "Migrated",
                    songIds = listOf(legacyId),
                    coverSongId = legacyId,
                ),
            ),
        )

        SongIdentityMigration.migrate(context, database)

        val newId = SongIdentity.documentId(mediaUri)
        assertNull(database.songDao().getById(legacyId))
        assertEquals(newId, database.songDao().getById(newId)?.id)
        assertEquals(1, database.songLyricsDao().getBySongId(newId).size)
        assertEquals(0, database.songLyricsDao().getBySongId(legacyId).size)
        val playlist = PlaylistRepository(database).load().single()
        assertEquals(listOf(newId), playlist.songIds)
        assertEquals(newId, playlist.coverSongId)
    }
}
