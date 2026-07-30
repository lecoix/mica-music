package com.mica.music.data.local

import android.content.Context
import android.os.SystemClock
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mica.music.util.DiagnosticLog

@Database(
    entities = [
        SongEntity::class,
        SongLyricsEntity::class,
        PendingSongLyricsEntity::class,
        LibraryMetaEntity::class,
        BrowseGroupEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
abstract class MicaDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao

    abstract fun songLyricsDao(): SongLyricsDao

    abstract fun libraryMetaDao(): LibraryMetaDao

    abstract fun browseGroupDao(): BrowseGroupDao

    companion object {
        internal const val DATABASE_NAME = "mica_library.db"

        @Volatile
        private var instance: MicaDatabase? = null

        fun get(context: Context): MicaDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    val startedMs = SystemClock.elapsedRealtime()
                    Room.databaseBuilder(
                        context.applicationContext,
                        MicaDatabase::class.java,
                        DATABASE_NAME,
                    )
                        .addMigrations(
                            MIGRATION_1_2,
                            MIGRATION_2_3,
                            MIGRATION_3_4,
                            MIGRATION_4_5,
                            MIGRATION_5_6,
                            MIGRATION_6_7,
                            MIGRATION_7_8,
                            MIGRATION_8_9,
                            MIGRATION_9_10,
                            MIGRATION_10_11,
                            MIGRATION_11_12,
                            MIGRATION_12_13,
                            MIGRATION_13_14,
                        )
                        .build()
                        .also {
                            instance = it
                            DiagnosticLog.event(
                                "LibraryDb",
                                "database build durMs=${SystemClock.elapsedRealtime() - startedMs}",
                            )
                        }
                }
            }
    }
}
