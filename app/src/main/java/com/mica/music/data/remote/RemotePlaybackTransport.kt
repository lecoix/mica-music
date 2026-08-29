package com.mica.music.data.remote

internal enum class RemoteHttpRangePolicy {
    MEDIA3_DEFAULT,
    STRICT_PARTIAL_CONTENT,
}

/**
 * Ephemeral HTTP authentication material. It exists only between JIT remote resolution and
 * DataSource.open(); it must never be written to MediaItem/session/catalog persistence or logs.
 */
internal sealed interface RemoteHttpAuthentication {
    class UsernamePassword(
        val origin: String,
        val username: String,
        val password: String,
    ) : RemoteHttpAuthentication {
        override fun toString(): String = "UsernamePassword(origin=$origin, username=<redacted>, password=<redacted>)"
    }
}

internal data class RemoteHttpPlaybackRequest(
    val url: String,
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
    val requestHeaders: Map<String, String> = emptyMap(),
    val authentication: RemoteHttpAuthentication? = null,
    val rangePolicy: RemoteHttpRangePolicy = RemoteHttpRangePolicy.MEDIA3_DEFAULT,
) {
    override fun toString(): String =
        "RemoteHttpPlaybackRequest(sourceInstanceId=$sourceInstanceId, " +
            "sourceConfigRevision=$sourceConfigRevision, credentialRevision=$credentialRevision, " +
            "rangePolicy=$rangePolicy, url=<redacted>, requestHeaders=<redacted>, authentication=<redacted>)"
}

internal fun interface RemoteHttpPlaybackRequestResolver {
    suspend fun resolve(mediaId: String): RemoteHttpPlaybackRequest?
}

internal class CompositeRemoteHttpPlaybackRequestResolver(
    private vararg val delegates: RemoteHttpPlaybackRequestResolver,
) : RemoteHttpPlaybackRequestResolver {
    override suspend fun resolve(mediaId: String): RemoteHttpPlaybackRequest? {
        delegates.forEach { resolver ->
            resolver.resolve(mediaId)?.let { return it }
        }
        return null
    }
}
