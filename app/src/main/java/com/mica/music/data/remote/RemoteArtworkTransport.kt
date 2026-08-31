package com.mica.music.data.remote

internal data class RemoteHttpArtworkRequest(
    val url: String,
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
) {
    override fun toString(): String =
        "RemoteHttpArtworkRequest(sourceInstanceId=$sourceInstanceId, " +
            "sourceConfigRevision=$sourceConfigRevision, credentialRevision=$credentialRevision, url=<redacted>)"
}

internal fun interface RemoteHttpArtworkRequestResolver {
    suspend fun resolve(ref: RemoteArtworkRef): RemoteHttpArtworkRequest?
}
