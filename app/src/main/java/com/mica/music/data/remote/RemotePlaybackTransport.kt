package com.mica.music.data.remote

internal data class RemoteHttpPlaybackRequest(
    val url: String,
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
    val requestHeaders: Map<String, String> = emptyMap(),
) {
    override fun toString(): String =
        "RemoteHttpPlaybackRequest(sourceInstanceId=$sourceInstanceId, " +
            "sourceConfigRevision=$sourceConfigRevision, credentialRevision=$credentialRevision, url=<redacted>)"
}

internal fun interface RemoteHttpPlaybackRequestResolver {
    suspend fun resolve(mediaId: String): RemoteHttpPlaybackRequest?
}
