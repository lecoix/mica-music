package com.mica.music.data.remote

/** Public, credential-free status for one configured remote source. */
data class RemoteSourceStatus(
    val instance: RemoteSourceInstance,
    val configRevision: Long,
    val catalogRevision: Long,
    val catalogConfigRevision: Long,
    val lastSyncAtMs: Long,
    val trackCount: Int,
)
