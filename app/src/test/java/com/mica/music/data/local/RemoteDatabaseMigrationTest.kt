package com.mica.music.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteDatabaseMigrationTest {
    @Test
    fun migration21To22CreatesRemoteTablesWithExpectedKeysAndNoSecretColumns() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(21) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE songs (" +
                                    "id TEXT NOT NULL PRIMARY KEY, musicVideoUri TEXT, " +
                                    "musicVideoRevision TEXT NOT NULL DEFAULT '')",
                            )
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        val db = helper.writableDatabase
        try {
            MIGRATION_21_22.migrate(db)

            val sourceColumns = tableColumns(db, "remote_sources")
            assertEquals(
                setOf(
                    "id", "type", "displayName", "endpoint", "credentialRef", "enabled",
                    "configRevision", "catalogRevision", "lastSyncAtMs",
                ),
                sourceColumns,
            )
            assertTrue("remote source schema must not persist passwords", sourceColumns.none { it.contains("password", true) })
            assertTrue("remote source schema must not persist tokens", sourceColumns.none { it == "token" || it.contains("auth", true) })

            val trackColumns = tableColumns(db, "remote_tracks")
            assertTrue("remote tracks require stable source identity", "sourceInstanceId" in trackColumns)
            assertTrue("remote tracks require opaque source track identity", "opaqueTrackId" in trackColumns)
            assertTrue("remote tracks must not persist stream URLs", trackColumns.none { it.contains("url", true) || it.contains("uri", true) })
            assertTrue("remote tracks must not persist auth material", trackColumns.none { it.contains("password", true) || it.contains("token", true) })

            db.query("PRAGMA foreign_key_list(`remote_tracks`)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("remote_sources", cursor.getString(cursor.getColumnIndexOrThrow("table")))
                assertEquals("sourceInstanceId", cursor.getString(cursor.getColumnIndexOrThrow("from")))
                assertEquals("id", cursor.getString(cursor.getColumnIndexOrThrow("to")))
                assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            }
            db.query("PRAGMA index_list(`remote_tracks`)").use { cursor ->
                val names = buildSet {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
                assertTrue("source lookup index missing", "index_remote_tracks_sourceInstanceId" in names)
            }
        } finally {
            helper.close()
        }
    }

    private fun tableColumns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
}
