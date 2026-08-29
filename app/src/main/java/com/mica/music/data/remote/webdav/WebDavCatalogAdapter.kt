package com.mica.music.data.remote.webdav

import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteOperationSnapshot
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.SecureRemoteCredentialStore
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import com.thegrizzlylabs.sardineandroid.impl.handler.ResourcesResponseHandler
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class WebDavSyncResult(
    val sourceInstanceId: String,
    val configRevision: Long,
    val trackCount: Int,
)

internal enum class WebDavFailureKind {
    AUTH,
    HTTP,
    PROTOCOL,
    STALE_OPERATION,
}

internal class WebDavException(
    val kind: WebDavFailureKind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Source-owned WebDAV listing adapter.
 *
 * Sardine owns DAV XML/resource parsing. Mica owns authentication lifetime, redirect policy,
 * same-origin/root containment and the compatibility retry for servers that reject the normal
 * XML-body PROPFIND but accept an empty body.
 */
internal class WebDavCatalogAdapter(
    private val client: OkHttpClient,
) {
    private val sardine = OkHttpSardine(client)

    fun list(url: String, depth: Int): List<DavResource> {
        require(depth == 0 || depth == 1) { "WebDAV catalog depth must be 0 or 1" }
        return try {
            sardine.list(url, depth)
        } catch (failure: SardineException) {
            if (failure.statusCode != 400) throw classify(failure)
            listWithEmptyPropfindBody(url, depth)
        } catch (failure: IOException) {
            throw WebDavException(WebDavFailureKind.HTTP, "WebDAV listing failed", failure)
        }
    }

    private fun listWithEmptyPropfindBody(url: String, depth: Int): List<DavResource> {
        val request = Request.Builder()
            .url(url)
            .header("Depth", depth.toString())
            .header("Accept", "application/xml, text/xml, */*")
            .method("PROPFIND", ByteArray(0).toRequestBody(PROPFIND_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                return ResourcesResponseHandler().handleResponse(response)
            }
        } catch (failure: SardineException) {
            throw classify(failure)
        } catch (failure: IOException) {
            throw WebDavException(WebDavFailureKind.HTTP, "WebDAV empty-body PROPFIND failed", failure)
        }
    }

    private fun classify(failure: SardineException): WebDavException = WebDavException(
        kind = when (failure.statusCode) {
            401, 403 -> WebDavFailureKind.AUTH
            else -> WebDavFailureKind.HTTP
        },
        message = "WebDAV server rejected PROPFIND with HTTP ${failure.statusCode}",
        cause = failure,
    )

    companion object {
        private val PROPFIND_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()
    }
}

/** Full source-scoped WebDAV catalog refresh with one coherent operation/credential snapshot. */
internal class WebDavSourceSync(
    private val catalogRepository: RemoteCatalogRepository,
    private val credentialStore: SecureRemoteCredentialStore,
) {
    suspend fun testConnection(sourceInstanceId: String) {
        val session = beginSession(sourceInstanceId)
        session.adapter.list(session.root.toString(), depth = 0)
        ensureCurrent(session)
    }

    suspend fun sync(
        sourceInstanceId: String,
        limit: Int = Int.MAX_VALUE,
    ): WebDavSyncResult {
        require(limit >= 0) { "limit must not be negative" }
        val session = beginSession(sourceInstanceId)
        val tracks = if (limit == 0) emptyList() else scan(session, limit)
        ensureCurrent(session)
        val published = catalogRepository.publishCatalogIfCurrent(
            token = session.operation.token,
            tracks = tracks,
        )
        if (!published) {
            throw WebDavException(
                WebDavFailureKind.STALE_OPERATION,
                "WebDAV catalog became stale before storage publication",
            )
        }
        return WebDavSyncResult(
            sourceInstanceId = sourceInstanceId,
            configRevision = session.operation.source.configRevision,
            trackCount = tracks.size,
        )
    }

    private suspend fun beginSession(sourceInstanceId: String): Session {
        val owner = catalogRepository.sourceOwner(sourceInstanceId)
            ?: throw WebDavException(WebDavFailureKind.PROTOCOL, "Unknown WebDAV source")
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        if (source.type != RemoteSourceType.WEBDAV) {
            throw WebDavException(WebDavFailureKind.PROTOCOL, "Remote source is not WebDAV")
        }
        if (!source.enabled) {
            throw WebDavException(WebDavFailureKind.PROTOCOL, "WebDAV source is disabled")
        }
        val credential = credentialStore.resolve(source.credentialRef)
            ?: throw WebDavException(WebDavFailureKind.AUTH, "WebDAV credential is unavailable")
        if (!owner.isCurrent(operation.token)) {
            throw WebDavException(WebDavFailureKind.STALE_OPERATION, "WebDAV source changed before listing")
        }
        val clientBuilder = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
        when (val material = credential.material) {
            RemoteCredentialMaterial.Anonymous -> Unit
            is RemoteCredentialMaterial.UsernamePassword -> clientBuilder.authenticator(
                WebDavHttpAuthenticator(
                    origin = WebDavPathCodec.origin(source.endpoint),
                    username = material.username,
                    password = material.password,
                ),
            )
            is RemoteCredentialMaterial.BearerToken -> throw WebDavException(
                WebDavFailureKind.AUTH,
                "Bearer credentials are not supported for WebDAV",
            )
        }
        return Session(
            owner = owner,
            operation = operation,
            root = WebDavPathCodec.sourceRoot(source.endpoint),
            adapter = WebDavCatalogAdapter(clientBuilder.build()),
        )
    }

    private fun scan(session: Session, limit: Int): List<RemoteTrackSummary> {
        val source = session.operation.source.instance
        val pending = ArrayDeque<String>()
        pending.addLast(session.root.toString())
        val visitedDirectories = linkedSetOf<String>()
        val tracks = ArrayList<RemoteTrackSummary>()

        while (pending.isNotEmpty() && tracks.size < limit) {
            ensureCurrent(session)
            val directoryUrl = pending.removeFirst()
            val directoryId = WebDavPathCodec.opaqueResourceId(source.endpoint, directoryUrl)
                ?: throw WebDavException(WebDavFailureKind.PROTOCOL, "WebDAV directory escaped configured root")
            if (!visitedDirectories.add(directoryId)) continue

            val resources = session.adapter.list(directoryUrl, depth = 1)
            ensureCurrent(session)
            resources.forEach { resource ->
                if (tracks.size >= limit) return@forEach
                val href = resource.href?.toString() ?: return@forEach
                val resolvedUrl = WebDavPathCodec.resolveResourceUrl(
                    endpoint = source.endpoint,
                    baseUrl = directoryUrl,
                    href = href,
                ) ?: return@forEach
                val resourceId = WebDavPathCodec.opaqueResourceId(source.endpoint, resolvedUrl.toString()) ?: return@forEach
                if (resourceId == directoryId) return@forEach
                if (resource.isDirectory) {
                    if (resourceId !in visitedDirectories) {
                        pending.addLast(WebDavPathCodec.asCollectionUrl(resolvedUrl).toString())
                    }
                    return@forEach
                }
                if (!isAudioResource(resource, resourceId)) return@forEach
                tracks += resource.toTrackSummary(source.id, resourceId)
            }
        }
        return tracks
    }

    private fun ensureCurrent(session: Session) {
        if (!session.owner.isCurrent(session.operation.token)) {
            throw WebDavException(WebDavFailureKind.STALE_OPERATION, "WebDAV source changed during listing")
        }
    }

    private fun isAudioResource(resource: DavResource, resourceId: String): Boolean {
        val contentType = resource.contentType.orEmpty().lowercase(Locale.US)
        if (contentType.startsWith("audio/")) return true
        return extension(resourceId) in AUDIO_EXTENSIONS
    }

    private fun DavResource.toTrackSummary(sourceInstanceId: String, resourceId: String): RemoteTrackSummary {
        val fileName = name?.takeIf(String::isNotBlank)
            ?: resourceId.substringAfterLast('/').ifBlank { resourceId }
        val suffix = extension(fileName)
        val title = fileName.substringBeforeLast('.', fileName).ifBlank { fileName }
        val mime = contentType.orEmpty().takeIf { it.startsWith("audio/", ignoreCase = true) }
            ?: MIME_BY_EXTENSION[suffix].orEmpty()
        return RemoteTrackSummary(
            ref = RemoteTrackRef(sourceInstanceId, resourceId),
            title = title,
            mimeTypeHint = mime,
            fileName = fileName,
            suffix = suffix,
            sizeBytes = (contentLength ?: 0L).coerceAtLeast(0L),
        )
    }

    private fun extension(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .lowercase(Locale.US)

    private data class Session(
        val owner: RemoteSourceOwner,
        val operation: RemoteOperationSnapshot,
        val root: HttpUrl,
        val adapter: WebDavCatalogAdapter,
    )

    companion object {
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "wma", "alac", "aiff", "aif",
        )
        private val MIME_BY_EXTENSION = mapOf(
            "mp3" to "audio/mpeg",
            "flac" to "audio/flac",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg",
            "opus" to "audio/opus",
            "wav" to "audio/wav",
            "ape" to "audio/ape",
            "wma" to "audio/x-ms-wma",
            "alac" to "audio/alac",
            "aiff" to "audio/aiff",
            "aif" to "audio/aiff",
        )
    }
}
