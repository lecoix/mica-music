package com.mica.music.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY queueOrder ASC")
    suspend fun getAllOrdered(): List<SongEntity>

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SongEntity>)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(entities: List<SongEntity>) {
        deleteAll()
        if (entities.isNotEmpty()) insertAll(entities)
    }

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Transaction
    suspend fun syncIncremental(entities: List<SongEntity>, removeIds: List<String>) {
        if (removeIds.isNotEmpty()) deleteByIds(removeIds)
        if (entities.isNotEmpty()) insertAll(entities)
    }
}
