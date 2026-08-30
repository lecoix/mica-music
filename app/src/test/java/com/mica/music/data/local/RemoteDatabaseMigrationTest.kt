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

    @Test
    fun migration22To23AddsCatalogProvenanceAndTrackContentRevisionWithFailClosedDefaults() {
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
            db.execSQL(
                "INSERT INTO remote_sources " +
                    "(id,type,displayName,endpoint,credentialRef,enabled,configRevision,catalogRevision,lastSyncAtMs) " +
                    "VALUES ('smb','SMB','NAS','smb://nas/share','cred',1,7,3,1000)",
            )
            db.execSQL(
                "INSERT INTO remote_tracks " +
                    "(sourceInstanceId,opaqueTrackId,title,artist,album,albumArtist,durationSec,mimeTypeHint," +
                    "fileName,suffix,sizeBytes,year,trackNumber,discNumber,albumOpaqueId,artistOpaqueId," +
                    "artworkOpaqueId,catalogPosition) " +
                    "VALUES ('smb','Track.flac','Track','','','',0,'audio/flac','Track.flac','flac',4,0,0,0,'','','',0)",
            )

            MIGRATION_22_23.migrate(db)

            assertTrue("catalogConfigRevision" in tableColumns(db, "remote_sources"))
            assertTrue("contentRevision" in tableColumns(db, "remote_tracks"))
            db.query("SELECT catalogConfigRevision FROM remote_sources WHERE id='smb'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
            db.query("SELECT contentRevision FROM remote_tracks WHERE sourceInstanceId='smb'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun migration23To24AddsMetadataProbeRevisionWithOneTimeReprobeDefault() {
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
            MIGRATION_22_23.migrate(db)
            db.execSQL(
                "INSERT INTO remote_sources " +
                    "(id,type,displayName,endpoint,credentialRef,enabled,configRevision,catalogRevision," +
                    "lastSyncAtMs,catalogConfigRevision) " +
                    "VALUES ('smb','SMB','NAS','smb://nas/share','cred',1,7,3,1000,7)",
            )
            db.execSQL(
                "INSERT INTO remote_tracks " +
                    "(sourceInstanceId,opaqueTrackId,title,artist,album,albumArtist,durationSec,mimeTypeHint," +
                    "fileName,suffix,sizeBytes,contentRevision,year,trackNumber,discNumber,albumOpaqueId," +
                    "artistOpaqueId,artworkOpaqueId,catalogPosition) " +
                    "VALUES ('smb','Track.flac','Track','','','',0,'audio/flac','Track.flac','flac',4," +
                    "'file:1',0,0,0,'','','',0)",
            )

            MIGRATION_23_24.migrate(db)

            assertTrue("metadataProbeRevision" in tableColumns(db, "remote_tracks"))
            db.query("SELECT metadataProbeRevision FROM remote_tracks WHERE sourceInstanceId='smb'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
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
