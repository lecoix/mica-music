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
    fun freshVersionTwoDatabaseMatchesExportedEntitySchema() {
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
                    "lyricsJson",
                    "queueOrder",
                ),
            ),
        )
        assertEquals(
            setOf("id", "lastScanAtMs", "lastScanSource", "totalSizeMb", "songCount"),
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
