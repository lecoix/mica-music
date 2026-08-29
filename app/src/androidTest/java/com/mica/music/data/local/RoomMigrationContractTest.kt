package com.mica.music.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMigrationContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MicaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun deleteDatabases() {
        context.deleteDatabase(FOUR_TO_FIVE_DB)
        context.deleteDatabase(TWO_TO_CURRENT_DB)
        context.deleteDatabase(SIXTEEN_TO_SEVENTEEN_DB)
        context.deleteDatabase(SEVENTEEN_TO_EIGHTEEN_DB)
    }

    @Test
    fun migrationFourToFiveAddsTrackNumberAndPreservesSong() {
        helper.createDatabase(FOUR_TO_FIVE_DB, 4).apply {
            insertLegacySong(version = 4)
            close()
        }

        helper.runMigrationsAndValidate(
            FOUR_TO_FIVE_DB,
            5,
            true,
            MIGRATION_4_5,
        ).use { database ->
            database.query("SELECT title, trackNumber FROM songs WHERE id = 'legacy'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("Legacy title", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
        }
    }

    @Test
    fun migrationTwoToCurrentValidatesExportedSchemaAndReadsThroughRealDaos() {
        helper.createDatabase(TWO_TO_CURRENT_DB, 2).apply {
            insertLegacySong(version = 2)
            execSQL(
                "INSERT INTO library_meta(" +
                    "id, lastScanAtMs, lastScanSource, totalSizeMb, songCount" +
                    ") VALUES (1, 1234, 'DEVICE', 42, 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TWO_TO_CURRENT_DB,
            22,
            true,
            *MIGRATIONS_TWO_TO_CURRENT,
        ).close()

        val database = Room.databaseBuilder(context, MicaDatabase::class.java, TWO_TO_CURRENT_DB)
            .addMigrations(*MIGRATIONS_TWO_TO_CURRENT)
            .build()
        try {
            runBlocking {
                val song = database.songDao().getById("legacy")!!
                assertEquals("Legacy title", song.title)
                assertEquals(0, song.trackNumber)
                assertEquals(-1, song.discNumber)
                assertEquals("", song.externalLyricsSignature)
                assertNull(song.replayGainTrackDb)
                assertNull(song.replayGainTrackPeak)
                assertNull(song.replayGainAlbumDb)
                assertNull(song.replayGainAlbumPeak)

                val meta = database.libraryMetaDao().get()!!
                assertEquals(1234L, meta.lastScanAtMs)
                assertEquals("DEVICE", meta.lastScanSource)
                assertEquals("", meta.sortField)
                assertEquals("", meta.sortDirection)
                assertEquals("", meta.fastScrollSectionsJson)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationSixteenToSeventeenAddsOrderedPlaylistTables() {
        helper.createDatabase(SIXTEEN_TO_SEVENTEEN_DB, 16).close()

        helper.runMigrationsAndValidate(
            SIXTEEN_TO_SEVENTEEN_DB,
            17,
            true,
            MIGRATION_16_17,
        ).use { database ->
            database.execSQL(
                "INSERT INTO playlists(id, name, sortField, sortDirection, position) " +
                    "VALUES ('playlist', 'Migrated', 'custom', 'asc', 0)",
            )
            database.execSQL(
                "INSERT INTO playlist_songs(playlistId, songId, position) " +
                    "VALUES ('playlist', 'song', 0)",
            )
            database.query(
                "SELECT songId FROM playlist_songs WHERE playlistId = 'playlist'",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("song", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrationSeventeenToEighteenAddsSongLyricsOffsets() {
        helper.createDatabase(SEVENTEEN_TO_EIGHTEEN_DB, 17).close()

        helper.runMigrationsAndValidate(
            SEVENTEEN_TO_EIGHTEEN_DB,
            18,
            true,
            MIGRATION_17_18,
        ).use { database ->
            database.execSQL(
                "INSERT INTO song_lyrics_offsets(songId, mediaUri, offsetMs) " +
                    "VALUES ('song', 'content://song', 500)",
            )
            database.query(
                "SELECT mediaUri, offsetMs FROM song_lyrics_offsets WHERE songId = 'song'",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("content://song", cursor.getString(0))
                assertEquals(500, cursor.getInt(1))
            }
        }
    }

    private fun SupportSQLiteDatabase.insertLegacySong(version: Int) {
        val externalLyricsColumn = if (version >= 3) ", externalLyricsSignature" else ""
        val externalLyricsValue = if (version >= 3) ", 'legacy-signature'" else ""
        execSQL(
            "INSERT INTO songs(" +
                "id, title, artist, album, albumArtist, durationSec, containerName, sampleRateHz, " +
                "bitsPerSample, bitrateKbps, channelCount, playbackMimeType, albumArtUri, " +
                "coverColorArgb, mediaUri, fileName, sizeBytes, year, folderPath, filePath, " +
                "copyright, codecLabel, dateAddedMs, dateModifiedMs, playCount, lyricsJson, queueOrder" +
                externalLyricsColumn +
                ") VALUES (" +
                "'legacy', 'Legacy title', 'Legacy artist', 'Legacy album', 'Legacy album artist', " +
                "180, 'WAV', 44100, 16, 1411, 2, 'audio/wav', NULL, 0, " +
                "'content://legacy/song', 'legacy.wav', 123456, 1999, 'Legacy', '/legacy/song.wav', " +
                "'copyright', 'PCM', 1000, 2000, 7, '', 0" +
                externalLyricsValue +
                ")",
        )
    }

    private companion object {
        const val FOUR_TO_FIVE_DB = "room-migration-4-5"
        const val TWO_TO_CURRENT_DB = "room-migration-2-current"
        const val SIXTEEN_TO_SEVENTEEN_DB = "room-migration-16-17"
        const val SEVENTEEN_TO_EIGHTEEN_DB = "room-migration-17-18"
        val MIGRATIONS_TWO_TO_CURRENT = arrayOf(
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
        )
    }
}
