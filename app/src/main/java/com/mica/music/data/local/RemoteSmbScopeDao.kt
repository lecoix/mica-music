package com.mica.music.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RemoteSmbScopeDao {
    @Query(
        "SELECT * FROM remote_smb_scopes WHERE sourceInstanceId = :sourceInstanceId " +
            "AND kind = 'MANAGED' ORDER BY relativeDirectory COLLATE NOCASE, includeSubdirectories",
    )
    suspend fun managedForSource(sourceInstanceId: String): List<RemoteSmbScopeEntity>

    @Query(
        "SELECT * FROM remote_smb_scopes WHERE sourceInstanceId = :sourceInstanceId " +
            "AND relativeDirectory = :relativeDirectory AND includeSubdirectories = :includeSubdirectories " +
            "AND kind = 'MANAGED' LIMIT 1",
    )
    suspend fun findManaged(
        sourceInstanceId: String,
        relativeDirectory: String,
        includeSubdirectories: Boolean,
    ): RemoteSmbScopeEntity?

    @Query("SELECT COUNT(*) FROM remote_smb_scopes WHERE sourceInstanceId = :sourceInstanceId")
    suspend fun countForSource(sourceInstanceId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putScope(scope: RemoteSmbScopeEntity)

    @Query(
        "DELETE FROM remote_smb_scopes WHERE id = :scopeId " +
            "AND sourceInstanceId = :sourceInstanceId AND kind = 'MANAGED'",
    )
    suspend fun deleteManaged(scopeId: String, sourceInstanceId: String): Int

    @Query("DELETE FROM remote_smb_scope_tracks WHERE scopeId = :scopeId")
    suspend fun clearMembership(scopeId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putMembership(rows: List<RemoteSmbScopeTrackEntity>)
}
