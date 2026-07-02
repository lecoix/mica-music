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
