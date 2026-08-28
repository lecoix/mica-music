package com.mica.music.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface RemoteTrackDao {
    @Query(
        "SELECT * FROM remote_tracks WHERE sourceInstanceId = :sourceInstanceId " +
            "ORDER BY catalogPosition ASC",
    )
    suspend fun getForSource(sourceInstanceId: String): List<RemoteTrackEntity>

    @Query(
        "SELECT * FROM remote_tracks WHERE sourceInstanceId = :sourceInstanceId " +
            "AND opaqueTrackId IN (:opaqueTrackIds)",
    )
    suspend fun getByOpaqueIds(
        sourceInstanceId: String,
        opaqueTrackIds: List<String>,
    ): List<RemoteTrackEntity>

    @Query(
        "SELECT t.* FROM remote_tracks t " +
            "INNER JOIN remote_sources s ON s.id = t.sourceInstanceId " +
            "WHERE s.enabled = 1 " +
            "ORDER BY s.displayName COLLATE NOCASE ASC, s.id ASC, t.catalogPosition ASC",
    )
    suspend fun getForEnabledSources(): List<RemoteTrackEntity>

    @Query("SELECT COUNT(*) FROM remote_tracks WHERE sourceInstanceId = :sourceInstanceId")
    suspend fun countForSource(sourceInstanceId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<RemoteTrackEntity>)

    @Query("DELETE FROM remote_tracks WHERE sourceInstanceId = :sourceInstanceId")
    suspend fun clearSource(sourceInstanceId: String)

    @Transaction
    suspend fun replaceSourceCatalog(
        sourceInstanceId: String,
        tracks: List<RemoteTrackEntity>,
    ) {
        clearSource(sourceInstanceId)
        tracks.chunked(REMOTE_TRACK_INSERT_BATCH_SIZE).forEach { insertAll(it) }
    }
}

private const val REMOTE_TRACK_INSERT_BATCH_SIZE = 500
