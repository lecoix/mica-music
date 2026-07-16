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
