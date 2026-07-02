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
    fun freshDatabaseMatchesExportedEntitySchema() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, MicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val db = database.openHelper.writableDatabase

        val songColumns = tableColumns(db, "songs")
        val metaColumns = tableColumns(db, "library_meta")

        assertTrue(
            songColumns.containsAll(
                listOf(
                    "id",
                    "title",
                    "albumArtist",
                    "filePath",
                    "copyright",
                    "codecLabel",
                    "externalLyricsSignature",
                    "lyricsJson",
                    "queueOrder",
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
        database.close()
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
}
