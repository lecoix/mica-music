package com.mica.music.data.remote.webdav

import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteHttpAuthentication
import com.mica.music.data.remote.RemoteHttpPlaybackRequest
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.RemoteHttpRangePolicy
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object WebDavPathCodec {
    fun sourceRoot(endpoint: String): HttpUrl = requireNotNull(endpoint.toHttpUrlOrNull()) {
        "Invalid WebDAV endpoint"
    }.let { url ->
        if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
    }

    /** Returns a canonical encoded path relative to the configured WebDAV root. */
    fun opaqueResourceId(endpoint: String, href: String): String? {
        val root = sourceRoot(endpoint)
        val resolved = root.resolve(href) ?: return null
        if (!sameOrigin(root, resolved)) return null
        val rootSegments = root.pathSegments.dropLastWhile(String::isEmpty)
        val resourceSegments = resolved.pathSegments.dropLastWhile(String::isEmpty)
        if (resourceSegments.size < rootSegments.size || resourceSegments.take(rootSegments.size) != rootSegments) {
            return null
        }
        val relative = resourceSegments.drop(rootSegments.size)
        if (relative.isEmpty()) return ""
        return HttpUrl.Builder()
            .scheme("https")
            .host("mica.invalid")
            .apply { relative.forEach(::addPathSegment) }
            .build()
            .encodedPath
            .removePrefix("/")
    }

    fun opaqueTrackId(endpoint: String, href: String): String? =
        opaqueResourceId(endpoint, href)

    fun resolveResourceUrl(endpoint: String, href: String): HttpUrl? =
        resolveResourceUrl(endpoint, sourceRoot(endpoint).toString(), href)

    fun resolveResourceUrl(endpoint: String, baseUrl: String, href: String): HttpUrl? {
        val root = sourceRoot(endpoint)
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        if (!sameOrigin(root, base)) return null
        if (opaqueResourceId(endpoint, base.toString()) == null) return null
        val collectionBase = asCollectionUrl(base)
        val candidate = collectionBase.resolve(href) ?: return null
        if (!sameOrigin(root, candidate)) return null
        if (opaqueResourceId(endpoint, candidate.toString()) == null) return null
        return candidate
    }

    fun asCollectionUrl(url: HttpUrl): HttpUrl =
        if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()

    fun resolveTrackUrl(endpoint: String, opaqueTrackId: String): HttpUrl? {
        if (opaqueTrackId.isBlank()) return null
        val root = sourceRoot(endpoint)
        val candidate = root.resolve(opaqueTrackId) ?: return null
        if (!sameOrigin(root, candidate)) return null
        val canonical = opaqueResourceId(endpoint, candidate.toString()) ?: return null
        if (canonical != opaqueTrackId) return null
        return candidate
    }

    fun origin(endpoint: String): String {
        val root = sourceRoot(endpoint)
        return root.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    }

    internal fun sameOrigin(left: HttpUrl, right: HttpUrl): Boolean =
        left.scheme == right.scheme && left.host == right.host && left.port == right.port
}

internal class WebDavStreamRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
) : RemoteHttpPlaybackRequestResolver {
    override suspend fun resolve(mediaId: String): RemoteHttpPlaybackRequest? {
        val trackRef = RemoteMediaIdCodec.decode(mediaId) ?: return null
        val owner = sourceOwnerById(trackRef.sourceInstanceId) ?: return null
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        if (source.type != RemoteSourceType.WEBDAV || !source.enabled) return null
        val credential = credentialStore.resolve(source.credentialRef) ?: return null
        if (!owner.isCurrent(operation.token)) return null
        val url = WebDavPathCodec.resolveTrackUrl(source.endpoint, trackRef.opaqueTrackId) ?: return null
        val authentication = when (val material = credential.material) {
            RemoteCredentialMaterial.Anonymous -> null
            is RemoteCredentialMaterial.UsernamePassword -> RemoteHttpAuthentication.UsernamePassword(
                origin = WebDavPathCodec.origin(source.endpoint),
                username = material.username,
                password = material.password,
            )
            is RemoteCredentialMaterial.BearerToken -> return null
        }
        if (!owner.isCurrent(operation.token)) return null
        return RemoteHttpPlaybackRequest(
            url = url.toString(),
            sourceInstanceId = source.id,
            sourceConfigRevision = operation.source.configRevision,
            credentialRevision = credential.revision,
            authentication = authentication,
            rangePolicy = RemoteHttpRangePolicy.STRICT_PARTIAL_CONTENT,
        )
    }
}
