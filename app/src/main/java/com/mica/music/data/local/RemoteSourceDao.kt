package com.mica.music.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface RemoteSourceDao {
    @Query("SELECT * FROM remote_sources ORDER BY displayName COLLATE NOCASE ASC, id ASC")
    suspend fun getAll(): List<RemoteSourceEntity>

    @Query("SELECT * FROM remote_sources WHERE enabled = 1 ORDER BY displayName COLLATE NOCASE ASC, id ASC")
    suspend fun getEnabled(): List<RemoteSourceEntity>

    @Query("SELECT * FROM remote_sources WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RemoteSourceEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: RemoteSourceEntity)

    @Update
    suspend fun update(source: RemoteSourceEntity): Int

    @Query(
        "UPDATE remote_sources SET catalogRevision = :catalogRevision, " +
            "catalogConfigRevision = :configRevision, lastSyncAtMs = :lastSyncAtMs " +
            "WHERE id = :sourceInstanceId AND configRevision = :configRevision",
    )
    suspend fun updateCatalogRevisionIfConfigCurrent(
        sourceInstanceId: String,
        configRevision: Long,
        catalogRevision: Long,
        lastSyncAtMs: Long,
    ): Int
}
