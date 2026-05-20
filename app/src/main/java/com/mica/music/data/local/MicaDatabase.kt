package com.mica.music.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SongEntity::class, LibraryMetaEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class MicaDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao

    abstract fun libraryMetaDao(): LibraryMetaDao

    companion object {
        @Volatile
        private var instance: MicaDatabase? = null

        fun get(context: Context): MicaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MicaDatabase::class.java,
                    "mica_library.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
