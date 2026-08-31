package com.mica.music.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN albumArtist TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE songs ADD COLUMN filePath TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE songs ADD COLUMN copyright TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE songs ADD COLUMN codecLabel TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN externalLyricsSignature TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE library_meta ADD COLUMN sortField TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE library_meta ADD COLUMN sortDirection TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE library_meta ADD COLUMN fastScrollSectionsJson TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN trackNumber INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN discNumber INTEGER NOT NULL DEFAULT -1")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE songs
            SET discNumber = -1
            WHERE discNumber = 0
                AND (
                    playbackMimeType IN ('audio/dsd', 'audio/x-dsf')
                    OR containerName = 'DSD'
                    OR fileName LIKE '%.dsf'
                )
            """.trimIndent(),
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN replayGainTrackDb REAL")
        db.execSQL("ALTER TABLE songs ADD COLUMN replayGainTrackPeak REAL")
        db.execSQL("ALTER TABLE songs ADD COLUMN replayGainAlbumDb REAL")
        db.execSQL("ALTER TABLE songs ADD COLUMN replayGainAlbumPeak REAL")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS song_lyrics (
                songId TEXT NOT NULL,
                slot TEXT NOT NULL,
                revision TEXT NOT NULL,
                lyricsJson TEXT NOT NULL,
                PRIMARY KEY(songId, slot)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO song_lyrics(songId, slot, revision, lyricsJson)
            SELECT id,
                CASE
                    WHEN lyricsJson LIKE '%"origin":"EXTERNAL"%'
                         AND lyricsJson LIKE '%"format":"TTML"%' THEN 'EXTERNAL_TTML'
                    WHEN lyricsJson LIKE '%"origin":"EXTERNAL"%' THEN 'EXTERNAL_LRC'
                    ELSE 'EMBEDDED'
                END,
                CAST(dateModifiedMs AS TEXT) || ':' || externalLyricsSignature,
                lyricsJson
            FROM songs
            WHERE lyricsJson <> '' AND lyricsJson <> '[]'
            """.trimIndent(),
        )
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS song_lyrics_pending (
                scanId TEXT NOT NULL,
                songId TEXT NOT NULL,
                revision TEXT NOT NULL,
                embeddedJson TEXT,
                externalLrcJson TEXT,
                externalTtmlJson TEXT,
                PRIMARY KEY(scanId, songId)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN videoCoverUri TEXT")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE library_meta ADD COLUMN browseArtistConfigKey TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS browse_groups (
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                subtitle TEXT NOT NULL,
                songCount INTEGER NOT NULL,
                artist TEXT NOT NULL,
                year INTEGER NOT NULL,
                albumArtUri TEXT,
                coverColorArgb INTEGER NOT NULL,
                PRIMARY KEY(kind, title)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE browse_groups ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE library_meta ADD COLUMN artistBrowseSortField TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE library_meta ADD COLUMN artistBrowseSortDirection TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE library_meta ADD COLUMN artistBrowseFastScrollSectionsJson TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE library_meta ADD COLUMN albumBrowseSortField TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE library_meta ADD COLUMN albumBrowseSortDirection TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE library_meta ADD COLUMN albumBrowseFastScrollSectionsJson TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN releaseDate TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE songs ADD COLUMN metadataScanVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE browse_groups ADD COLUMN releaseDate TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE browse_groups_new (
                kind TEXT NOT NULL,
                groupKey TEXT NOT NULL,
                title TEXT NOT NULL,
                subtitle TEXT NOT NULL,
                songCount INTEGER NOT NULL,
                artist TEXT NOT NULL,
                year INTEGER NOT NULL,
                releaseDate TEXT NOT NULL,
                albumArtUri TEXT,
                coverColorArgb INTEGER NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY(kind, groupKey)
            )
            """.trimIndent(),
        )
        // Artist groups remain valid because their identity is already their title. Album groups
        // are derived from the old title-only schema and must be rebuilt with albumArtist.
        db.execSQL(
            """
            INSERT INTO browse_groups_new(
                kind, groupKey, title, subtitle, songCount, artist, year, releaseDate,
                albumArtUri, coverColorArgb, position
            )
            SELECT kind, title, title, subtitle, songCount, artist, year, releaseDate,
                albumArtUri, coverColorArgb, position
            FROM browse_groups
            WHERE kind = 'artist'
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE browse_groups")
        db.execSQL("ALTER TABLE browse_groups_new RENAME TO browse_groups")
        db.execSQL(
            """
            UPDATE library_meta
            SET albumBrowseSortField = '',
                albumBrowseSortDirection = '',
                albumBrowseFastScrollSectionsJson = ''
            """.trimIndent(),
        )
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN embeddedLyricsProbeRevision TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlists (
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                sortField TEXT NOT NULL,
                sortDirection TEXT NOT NULL,
                coverSongId TEXT,
                customCoverPath TEXT,
                position INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlist_songs (
                playlistId TEXT NOT NULL,
                songId TEXT NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY(playlistId, songId),
                FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_playlist_songs_playlistId " +
                "ON playlist_songs(playlistId)",
        )
    }
}

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS song_lyrics_offsets (
                songId TEXT NOT NULL,
                mediaUri TEXT NOT NULL,
                offsetMs INTEGER NOT NULL,
                PRIMARY KEY(songId)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN comment TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN loudnessIntegratedLufs REAL")
        db.execSQL("ALTER TABLE songs ADD COLUMN loudnessSamplePeak REAL")
        db.execSQL("ALTER TABLE songs ADD COLUMN loudnessTrackGainDb REAL")
        db.execSQL("ALTER TABLE songs ADD COLUMN loudnessSourceSizeBytes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE songs ADD COLUMN loudnessSourceModifiedMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE songs ADD COLUMN loudnessAnalyzerRevision INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN musicVideoUri TEXT")
        db.execSQL("ALTER TABLE songs ADD COLUMN musicVideoRevision TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Schema 21 existed briefly on two development lines: mainline added music-video columns,
        // while feature/remote-music added the remote catalog tables. Make the merge migration
        // tolerant of either predecessor so existing QA installs from both branches upgrade in-place.
        if (!db.hasColumn("songs", "musicVideoUri")) {
            db.execSQL("ALTER TABLE songs ADD COLUMN musicVideoUri TEXT")
        }
        if (!db.hasColumn("songs", "musicVideoRevision")) {
            db.execSQL("ALTER TABLE songs ADD COLUMN musicVideoRevision TEXT NOT NULL DEFAULT ''")
        }
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS remote_sources (
                id TEXT NOT NULL,
                type TEXT NOT NULL,
                displayName TEXT NOT NULL,
                endpoint TEXT NOT NULL,
                credentialRef TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                configRevision INTEGER NOT NULL,
                catalogRevision INTEGER NOT NULL,
                lastSyncAtMs INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS remote_tracks (
                sourceInstanceId TEXT NOT NULL,
                opaqueTrackId TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                albumArtist TEXT NOT NULL,
                durationSec INTEGER NOT NULL,
                mimeTypeHint TEXT NOT NULL,
                fileName TEXT NOT NULL,
                suffix TEXT NOT NULL,
                sizeBytes INTEGER NOT NULL,
                year INTEGER NOT NULL,
                trackNumber INTEGER NOT NULL,
                discNumber INTEGER NOT NULL,
                albumOpaqueId TEXT NOT NULL,
                artistOpaqueId TEXT NOT NULL,
                artworkOpaqueId TEXT NOT NULL,
                catalogPosition INTEGER NOT NULL,
                PRIMARY KEY(sourceInstanceId, opaqueTrackId),
                FOREIGN KEY(sourceInstanceId) REFERENCES remote_sources(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_remote_tracks_sourceInstanceId " +
                "ON remote_tracks(sourceInstanceId)",
        )
    }
}

val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE remote_sources ADD COLUMN catalogConfigRevision INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE remote_tracks ADD COLUMN contentRevision TEXT NOT NULL DEFAULT ''",
        )
    }
}

val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE remote_tracks ADD COLUMN metadataProbeRevision INTEGER NOT NULL DEFAULT 0",
        )
    }
}

private fun SupportSQLiteDatabase.hasColumn(tableName: String, columnName: String): Boolean =
    query("PRAGMA table_info(`$tableName`)").use { cursor ->
        val nameColumn = cursor.getColumnIndexOrThrow("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameColumn) == columnName) return@use true
        }
        false
    }


val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE remote_tracks ADD COLUMN sampleRateHz INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE remote_tracks ADD COLUMN bitsPerSample INTEGER")
        db.execSQL("ALTER TABLE remote_tracks ADD COLUMN bitrateKbps INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE remote_tracks ADD COLUMN channelCount INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Technical audio fields were added in v25. Existing catalogs need one safe refresh so
        // protocol/tag metadata can populate them; keep rows/credentials intact until that sync publishes.
        db.execSQL("UPDATE remote_sources SET lastSyncAtMs = 0 WHERE enabled = 1")
    }
}
