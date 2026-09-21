package com.mica.music.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Durable descriptions, independent of catalog membership and source deletion. No credentials. */
@Entity(tableName = "remote_selected_tracks", primaryKeys = ["sourceInstanceId", "opaqueTrackId"])
data class RemoteSelectedTrackEntity(
    @Embedded val track: RemoteTrackEntity,
    val observedConfigRevision: Long,
)

@Dao
interface RemoteSelectedTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(rows: List<RemoteSelectedTrackEntity>)

    @Query("SELECT * FROM remote_selected_tracks WHERE sourceInstanceId = :source AND opaqueTrackId IN (:ids)")
    suspend fun find(source: String, ids: List<String>): List<RemoteSelectedTrackEntity>

    @Query("SELECT * FROM remote_selected_tracks")
    fun observe(): Flow<List<RemoteSelectedTrackEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM remote_selected_tracks WHERE sourceInstanceId = :source AND observedConfigRevision = :revision AND artworkOpaqueId = :artwork)")
    suspend fun hasArtwork(source: String, revision: Long, artwork: String): Boolean
}
