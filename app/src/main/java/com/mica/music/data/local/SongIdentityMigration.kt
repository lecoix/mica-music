package com.mica.music.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mica.music.data.PlayHistoryStore
import com.mica.music.data.PlaybackSessionStore
import com.mica.music.data.PlaylistStore
import com.mica.music.data.SongIdentity
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.playback.ServicePlaybackStateStore
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One-time data migration from URI hash IDs to collision-resistant document IDs. */
object SongIdentityMigration {
    private const val PREFS_NAME = "mica_song_identity_migration"
    private const val KEY_VERSION = "version"
    private const val CURRENT_VERSION = 1
    private const val TEMP_PREFIX = "__song_identity_migration_"
    private val mutex = Mutex()

    internal suspend fun migrate(
        context: Context,
        database: MicaDatabase,
    ) {
        mutex.withLock {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_VERSION, 0) >= CURRENT_VERSION) return@withLock
            val legacyRows = database.songDao().getAllSummariesOrdered()
                .filter { SongIdentity.isLegacyDocumentId(it.id) && it.mediaUri.isNotBlank() }
            val mapping = legacyRows.associate { row ->
                row.id to SongIdentity.documentId(row.mediaUri)
            }
            if (mapping.isEmpty()) {
                prefs.edit().putInt(KEY_VERSION, CURRENT_VERSION).commit()
                return@withLock
            }

            // Preferences are rewritten before the DB transaction so a retry after a crash is safe:
            // the mapping can still be reconstructed from the legacy DB rows.
            PlaylistStore.migrateSongIds(context, database, mapping)
            PlayHistoryStore.migrateSongIds(context, mapping)
            PlaybackSessionStore.migrateSongIds(context, mapping)
            LibraryBrowseSettings.migrateSongIds(context, mapping)
            ServicePlaybackStateStore(context).migrateSongIds(mapping)

            migrateDatabase(database, legacyRows, mapping)
            prefs.edit().putInt(KEY_VERSION, CURRENT_VERSION).commit()
            DiagnosticLog.event(
                "SongIdentityMigration",
                "migrated legacy document ids count=${mapping.size}",
            )
        }
    }

    private fun migrateDatabase(
        database: MicaDatabase,
        legacyRows: List<SongSummaryEntity>,
        mapping: Map<String, String>,
    ) {
        val rowsByNewId = legacyRows.groupBy { mapping.getValue(it.id) }
        val temporaryIds = mapping.keys.withIndex().associate { (index, oldId) ->
            oldId to "$TEMP_PREFIX$index"
        }
        database.runInTransaction {
            val db = database.openHelper.writableDatabase
            mapping.keys.forEach { oldId ->
                val temporaryId = temporaryIds.getValue(oldId)
                updateId(db, "songs", "id", oldId, temporaryId)
                updateId(db, "song_lyrics", "songId", oldId, temporaryId)
                updateId(db, "song_lyrics_pending", "songId", oldId, temporaryId)
            }
            rowsByNewId.forEach { (newId, rows) ->
                val survivor = rows.minBy(SongSummaryEntity::queueOrder)
                rows.filterNot { it.id == survivor.id }.forEach { duplicate ->
                    val temporaryId = temporaryIds.getValue(duplicate.id)
                    deleteById(db, "song_lyrics", "songId", temporaryId)
                    deleteById(db, "song_lyrics_pending", "songId", temporaryId)
                    deleteById(db, "songs", "id", temporaryId)
                }
                val survivorTemporaryId = temporaryIds.getValue(survivor.id)
                updateId(db, "songs", "id", survivorTemporaryId, newId)
                updateId(db, "song_lyrics", "songId", survivorTemporaryId, newId)
                updateId(db, "song_lyrics_pending", "songId", survivorTemporaryId, newId)
            }
        }
    }

    private fun updateId(
        db: SupportSQLiteDatabase,
        table: String,
        column: String,
        from: String,
        to: String,
    ) {
        db.execSQL("UPDATE $table SET $column = ? WHERE $column = ?", arrayOf(to, from))
    }

    private fun deleteById(
        db: SupportSQLiteDatabase,
        table: String,
        column: String,
        value: String,
    ) {
        db.execSQL("DELETE FROM $table WHERE $column = ?", arrayOf(value))
    }
}
