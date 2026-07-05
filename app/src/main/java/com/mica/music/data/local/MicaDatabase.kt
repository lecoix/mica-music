package com.mica.music.data.local

import android.content.Context
import android.os.SystemClock
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mica.music.util.DiagnosticLog

@Database(
    entities = [SongEntity::class, LibraryMetaEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class MicaDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao

    abstract fun libraryMetaDao(): LibraryMetaDao

    companion object {
        @Volatile
        private var instance: MicaDatabase? = null

        fun get(context: Context): MicaDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    val startedMs = SystemClock.elapsedRealtime()
                    Room.databaseBuilder(
                        context.applicationContext,
                        MicaDatabase::class.java,
                        "mica_library.db",
                    )
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
