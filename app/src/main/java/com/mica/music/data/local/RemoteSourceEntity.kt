package com.mica.music.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceType

@Entity(tableName = "remote_sources")
data class RemoteSourceEntity(
    @PrimaryKey val id: String,
    val type: String,
    val displayName: String,
    val endpoint: String,
    val credentialRef: String,
    val enabled: Boolean,
    val configRevision: Long,
    val catalogRevision: Long,
    val lastSyncAtMs: Long,
)

internal fun RemoteSourceEntity.toRemoteSourceInstance(): RemoteSourceInstance = RemoteSourceInstance(
    id = id,
    type = RemoteSourceType.valueOf(type),
    displayName = displayName,
    endpoint = endpoint,
    credentialRef = credentialRef,
    enabled = enabled,
)

internal fun RemoteSourceInstance.toEntity(
    configRevision: Long,
    catalogRevision: Long = 0L,
    lastSyncAtMs: Long = 0L,
): RemoteSourceEntity = RemoteSourceEntity(
    id = id,
    type = type.name,
    displayName = displayName,
    endpoint = endpoint,
    credentialRef = credentialRef,
    enabled = enabled,
    configRevision = configRevision,
    catalogRevision = catalogRevision,
    lastSyncAtMs = lastSyncAtMs,
)
