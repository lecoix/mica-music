package com.mica.music.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface BrowseGroupDao {
    @Query("SELECT * FROM browse_groups WHERE kind = 'artist' ORDER BY position ASC")
    suspend fun getArtists(): List<BrowseGroupEntity>

    @Query("SELECT * FROM browse_groups WHERE kind = 'album' ORDER BY position ASC")
    suspend fun getAlbums(): List<BrowseGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(groups: List<BrowseGroupEntity>)

    @Query("DELETE FROM browse_groups")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(
        artistGroups: List<BrowseGroupEntity>,
        albumGroups: List<BrowseGroupEntity>,
    ) {
        deleteAll()
        artistGroups.chunked(BROWSE_GROUP_INSERT_BATCH_SIZE).forEach { insertAll(it) }
        albumGroups.chunked(BROWSE_GROUP_INSERT_BATCH_SIZE).forEach { insertAll(it) }
    }
}

private const val BROWSE_GROUP_INSERT_BATCH_SIZE = 500
