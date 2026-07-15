package com.mica.music.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction

@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY queueOrder ASC")
    suspend fun getAllOrdered(): List<SongEntity>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM songs ORDER BY queueOrder ASC")
    suspend fun getAllSummariesOrdered(): List<SongSummaryEntity>

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SongEntity?

    @Query("SELECT lyricsJson FROM songs WHERE id = :id LIMIT 1")
    suspend fun getLyricsById(id: String): LyricsJsonRow?

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

    @Query("UPDATE songs SET queueOrder = :queueOrder WHERE id = :songId")
    suspend fun updateQueueOrder(songId: String, queueOrder: Int)

    @Transaction
    suspend fun updateQueueOrders(songIds: List<String>) {
        songIds.forEachIndexed { index, songId -> updateQueueOrder(songId, index) }
    }

    @Transaction
    suspend fun syncIncremental(
        entities: List<SongEntity>,
        removeIds: List<String>,
        orderedSongIds: List<String>,
    ) {
        if (removeIds.isNotEmpty()) deleteByIds(removeIds)
        if (entities.isNotEmpty()) insertAll(entities)
        updateQueueOrders(orderedSongIds)
    }
}

@Dao
interface SongLyricsDao {
    @Query("SELECT * FROM song_lyrics WHERE songId = :songId AND (:revision IS NULL OR revision = :revision)")
    suspend fun getBySongId(songId: String, revision: String? = null): List<SongLyricsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SongLyricsEntity>)

    @Query("DELETE FROM song_lyrics WHERE songId IN (:songIds)")
    suspend fun deleteBySongIds(songIds: List<String>)

    @Query("DELETE FROM song_lyrics")
    suspend fun deleteAll()

    @Query("DELETE FROM song_lyrics WHERE songId NOT IN (SELECT id FROM songs)")
    suspend fun deleteOrphans()
}
