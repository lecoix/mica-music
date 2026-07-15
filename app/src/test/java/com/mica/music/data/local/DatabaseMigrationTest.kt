package com.mica.music.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DatabaseMigrationTest {

    @Test
    fun migrationOneToTwoAddsColumnsWithSafeDefaults() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE songs (id TEXT NOT NULL PRIMARY KEY)")
                            db.execSQL("INSERT INTO songs(id) VALUES ('legacy')")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        val db = helper.writableDatabase

        MIGRATION_1_2.migrate(db)

        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(songs)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        assertTrue(columns.containsAll(listOf("albumArtist", "filePath", "copyright", "codecLabel")))
        db.query(
            "SELECT albumArtist, filePath, copyright, codecLabel FROM songs WHERE id = 'legacy'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            repeat(4) { index -> assertEquals("", cursor.getString(index)) }
        }
        helper.close()
    }

    @Test
    fun migrationTwoToThreeAddsExternalLyricsSignatureDefault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE songs (id TEXT NOT NULL PRIMARY KEY)")
                            db.execSQL("INSERT INTO songs(id) VALUES ('legacy')")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        val db = helper.writableDatabase

        MIGRATION_2_3.migrate(db)

        val columns = tableColumns(db, "songs")
        assertTrue(columns.contains("externalLyricsSignature"))
        db.query("SELECT externalLyricsSignature FROM songs WHERE id = 'legacy'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
        }
        helper.close()
    }

    @Test
    fun migrationThreeToFourAddsSortCacheDefaults() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE library_meta (" +
                                    "id INTEGER NOT NULL PRIMARY KEY, " +
                                    "lastScanAtMs INTEGER NOT NULL, " +
                                    "lastScanSource TEXT NOT NULL, " +
                                    "totalSizeMb INTEGER NOT NULL, " +
                                    "songCount INTEGER NOT NULL" +
                                    ")",
                            )
                            db.execSQL(
                                "INSERT INTO library_meta(id, lastScanAtMs, lastScanSource, totalSizeMb, songCount) " +
                                    "VALUES (1, 100, 'DEVICE', 1, 1)",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        val db = helper.writableDatabase

        MIGRATION_3_4.migrate(db)

        val columns = tableColumns(db, "library_meta")
        assertTrue(columns.containsAll(listOf("sortField", "sortDirection", "fastScrollSectionsJson")))
        db.query("SELECT sortField, sortDirection, fastScrollSectionsJson FROM library_meta WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            repeat(3) { index -> assertEquals("", cursor.getString(index)) }
        }
        helper.close()
    }

    @Test
    fun migrationFiveToSixAddsDiscNumberDefault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE songs (id TEXT NOT NULL PRIMARY KEY)")
                            db.execSQL("INSERT INTO songs(id) VALUES ('legacy')")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        val db = helper.writableDatabase

        MIGRATION_5_6.migrate(db)

        val columns = tableColumns(db, "songs")
        assertTrue(columns.contains("discNumber"))
        db.query("SELECT discNumber FROM songs WHERE id = 'legacy'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(-1, cursor.getInt(0))
        }
        helper.close()
    }

    @Test
    fun migrationSixToSevenMarksCachedDsdDiscNumberUnrefreshed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE songs (" +
                                    "id TEXT NOT NULL PRIMARY KEY, " +
                                    "containerName TEXT NOT NULL, " +
                                    "playbackMimeType TEXT NOT NULL, " +
                                    "fileName TEXT NOT NULL, " +
                                    "discNumber INTEGER NOT NULL" +
                                    ")",
                            )
                            db.execSQL(
                                "INSERT INTO songs(id, containerName, playbackMimeType, fileName, discNumber) " +
                                    "VALUES ('dsf-zero', 'DSD', 'audio/x-dsf', 'song.dsf', 0)",
                            )
                            db.execSQL(
                                "INSERT INTO songs(id, containerName, playbackMimeType, fileName, discNumber) " +
                                    "VALUES ('dsf-known', 'DSD', 'audio/x-dsf', 'known.dsf', 2)",
                            )
                            db.execSQL(
                                "INSERT INTO songs(id, containerName, playbackMimeType, fileName, discNumber) " +
                                    "VALUES ('wav-zero', 'WAV', 'audio/wav', 'song.wav', 0)",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        val db = helper.writableDatabase

        MIGRATION_6_7.migrate(db)

        assertEquals(-1, discNumberFor(db, "dsf-zero"))
        assertEquals(2, discNumberFor(db, "dsf-known"))
        assertEquals(0, discNumberFor(db, "wav-zero"))
        helper.close()
    }

    @Test
    fun migrationSevenToEightAddsNullableReplayGainColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE songs (id TEXT NOT NULL PRIMARY KEY)")
                            db.execSQL("INSERT INTO songs(id) VALUES ('legacy')")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        val db = helper.writableDatabase

        MIGRATION_7_8.migrate(db)

        val columns = tableColumns(db, "songs")
        assertTrue(columns.containsAll(listOf(
            "replayGainTrackDb",
            "replayGainTrackPeak",
            "replayGainAlbumDb",
            "replayGainAlbumPeak",
        )))
        db.query("SELECT replayGainTrackDb, replayGainTrackPeak, replayGainAlbumDb, replayGainAlbumPeak FROM songs")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                repeat(4) { index -> assertTrue(cursor.isNull(index)) }
            }
        helper.close()
    }

    @Test
    fun freshDatabaseMatchesExportedEntitySchema() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, MicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val db = database.openHelper.writableDatabase

        val songColumns = tableColumns(db, "songs")
        val metaColumns = tableColumns(db, "library_meta")
        val lyricsColumns = tableColumns(db, "song_lyrics")
        val pendingLyricsColumns = tableColumns(db, "song_lyrics_pending")

        assertTrue(
            songColumns.containsAll(
                listOf(
                    "id",
                    "title",
                    "albumArtist",
                    "filePath",
                    "copyright",
                    "codecLabel",
                    "discNumber",
                    "externalLyricsSignature",
                    "lyricsJson",
                    "queueOrder",
                    "replayGainTrackDb",
                    "replayGainTrackPeak",
                    "replayGainAlbumDb",
                    "replayGainAlbumPeak",
                ),
            ),
        )
        assertEquals(
            setOf(
                "id",
                "lastScanAtMs",
                "lastScanSource",
                "totalSizeMb",
                "songCount",
                "sortField",
                "sortDirection",
                "fastScrollSectionsJson",
            ),
            metaColumns,
        )
        assertEquals(setOf("songId", "slot", "revision", "lyricsJson"), lyricsColumns)
        assertEquals(
            setOf(
                "scanId",
                "songId",
                "revision",
                "embeddedJson",
                "externalLrcJson",
                "externalTtmlJson",
            ),
            pendingLyricsColumns,
        )
        database.close()
    }

    @Test
    fun migrationEightToNineMovesLegacyPayloadIntoMatchingSlot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE songs (id TEXT NOT NULL PRIMARY KEY, lyricsJson TEXT NOT NULL, " +
                                "dateModifiedMs INTEGER NOT NULL, externalLyricsSignature TEXT NOT NULL)",
                        )
                        db.execSQL(
                            "INSERT INTO songs VALUES ('legacy', " +
                                "'{\"format\":\"TTML\",\"origin\":\"EXTERNAL\",\"lines\":[]}', 7, 'sig')",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase

        MIGRATION_8_9.migrate(db)

        db.query("SELECT slot, revision FROM song_lyrics WHERE songId = 'legacy'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("EXTERNAL_TTML", cursor.getString(0))
            assertEquals("7:sig", cursor.getString(1))
        }
        helper.close()
    }

    @Test
    fun migrationNineToTenAddsLyricsStagingTable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase

        MIGRATION_9_10.migrate(db)

        assertEquals(
            setOf(
                "scanId",
                "songId",
                "revision",
                "embeddedJson",
                "externalLrcJson",
                "externalTtmlJson",
            ),
            tableColumns(db, "song_lyrics_pending"),
        )
        helper.close()
    }

    private fun tableColumns(
        db: SupportSQLiteDatabase,
        table: String,
    ): Set<String> = buildSet {
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }

    private fun discNumberFor(db: SupportSQLiteDatabase, id: String): Int =
        db.query("SELECT discNumber FROM songs WHERE id = '$id'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
