package com.mica.music.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmbBrowseMigrationTest {
    @Test fun version32UpgradesThroughRoomValidationWithoutLosingSources() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "smb-migration-test.db"
        context.deleteDatabase(name)
        val relative = "schemas/com.mica.music.data.local.MicaDatabase/32.json"
        val schemaFile = listOf(File(relative), File("app/$relative")).first { it.exists() }
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(32) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.getJSONArray("indices")
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                    db.execSQL("INSERT INTO remote_sources VALUES ('smb','SMB','Router','smb://router/share','opaque',1,1,7,1,1234)")
                    db.execSQL(
                        """
                        INSERT INTO remote_tracks(
                            sourceInstanceId, opaqueTrackId, title, artist, album, albumArtist,
                            durationSec, mimeTypeHint, fileName, suffix, sizeBytes, sampleRateHz,
                            bitsPerSample, bitrateKbps, channelCount, contentRevision,
                            metadataProbeRevision, year, trackNumber, discNumber, albumOpaqueId,
                            artistOpaqueId, artworkOpaqueId, catalogPosition
                        ) VALUES(
                            'smb', 'Album/Old.flac', 'Old', '', '', '',
                            0, 'audio/flac', 'Old.flac', 'flac', 1000, 0,
                            NULL, 0, 0, 'v1',
                            0, 0, 0, 0, '',
                            '', '', 0
                        )
                        """.trimIndent(),
                    )
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
        )
        helper.writableDatabase
        helper.close()
        val room = Room.databaseBuilder(context, MicaDatabase::class.java, name)
            .addMigrations(MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase // validates every entity and index against v34
            db.query("SELECT displayName FROM remote_sources WHERE id='smb'").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("Router", cursor.getString(0))
            }
            db.query("SELECT COUNT(*) FROM remote_selected_tracks").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0))
            }
            db.query(
                "SELECT relativeDirectory, includeSubdirectories, kind, lastCompletedAtMs " +
                    "FROM remote_smb_scopes WHERE id='legacy:smb'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals("LEGACY", cursor.getString(2))
                assertEquals(1234L, cursor.getLong(3))
            }
            db.query(
                "SELECT opaqueTrackId FROM remote_smb_scope_tracks WHERE scopeId='legacy:smb'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("Album/Old.flac", cursor.getString(0))
            }
            db.query("SELECT lyricsRevision FROM remote_tracks WHERE sourceInstanceId='smb'").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("", cursor.getString(0))
            }
        } finally { room.close(); context.deleteDatabase(name) }
    }
}
