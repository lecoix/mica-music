package com.mica.music.data.remote

enum class RemoteSourceType {
    NAVIDROME,
    WEBDAV,
    SMB,
}

data class RemoteSourceInstance(
    val id: String,
    val type: RemoteSourceType,
    val displayName: String,
    val endpoint: String,
    val credentialRef: String,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "Remote source id must not be blank" }
        require(displayName.isNotBlank()) { "Remote source displayName must not be blank" }
        require(endpoint.isNotBlank()) { "Remote source endpoint must not be blank" }
    }
}

data class RemoteTrackRef(
    val sourceInstanceId: String,
    val opaqueTrackId: String,
) {
    init {
        require(sourceInstanceId.isNotBlank()) { "sourceInstanceId must not be blank" }
        require(opaqueTrackId.isNotBlank()) { "opaqueTrackId must not be blank" }
    }
}

data class RemoteSourceSnapshot(
    val instance: RemoteSourceInstance,
    val configRevision: Long,
    val operationGeneration: Long,
)

data class RemoteOperationToken(
    val sourceInstanceId: String,
    val configRevision: Long,
    val operationGeneration: Long,
)
data class RemoteOperationSnapshot(
    val source: RemoteSourceSnapshot,
    val token: RemoteOperationToken,
)
data class RemoteTrackSummary(
    val ref: RemoteTrackRef,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val durationSec: Int = 0,
    val mimeTypeHint: String = "",
    val fileName: String = "",
    val suffix: String = "",
    val sizeBytes: Long = 0L,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val albumOpaqueId: String = "",
    val artistOpaqueId: String = "",
    val artworkOpaqueId: String = "",
) {
    val mediaId: String get() = RemoteMediaIdCodec.encode(ref)
}

fun interface RemoteTrackSummaryLookup {
    suspend fun find(refs: List<RemoteTrackRef>): Map<RemoteTrackRef, RemoteTrackSummary>
}
