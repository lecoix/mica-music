package com.mica.music.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LibraryMetaDao {

    @Query("SELECT * FROM library_meta WHERE id = 1 LIMIT 1")
    suspend fun get(): LibraryMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: LibraryMetaEntity)
}
