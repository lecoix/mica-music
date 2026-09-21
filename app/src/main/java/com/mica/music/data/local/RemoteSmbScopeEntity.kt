package com.mica.music.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "remote_smb_scopes",
    foreignKeys = [
        ForeignKey(
            entity = RemoteSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceInstanceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sourceInstanceId"),
        Index(
            value = ["sourceInstanceId", "relativeDirectory", "includeSubdirectories", "kind"],
            unique = true,
        ),
    ],
)
data class RemoteSmbScopeEntity(
    @androidx.room.PrimaryKey
    val id: String,
    val sourceInstanceId: String,
    val relativeDirectory: String,
    val includeSubdirectories: Boolean,
    val kind: String,
    val observedConfigRevision: Long,
    val lastCompletedAtMs: Long,
)

@Entity(
    tableName = "remote_smb_scope_tracks",
    primaryKeys = ["scopeId", "opaqueTrackId"],
    foreignKeys = [
        ForeignKey(
            entity = RemoteSmbScopeEntity::class,
            parentColumns = ["id"],
            childColumns = ["scopeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RemoteSmbScopeTrackEntity(
    val scopeId: String,
    val opaqueTrackId: String,
)
