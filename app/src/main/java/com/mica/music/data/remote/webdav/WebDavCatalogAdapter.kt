package com.mica.music.data.remote.webdav

import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.RemoteFileArtworkIdCodec
import com.mica.music.data.remote.REMOTE_METADATA_PROBE_REVISION
import com.mica.music.data.remote.RemoteOperationSnapshot
import com.mica.music.data.remote.RemoteSidecarArtworkCandidate
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackMetadataProbe
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.SecureRemoteCredentialStore
import com.mica.music.data.remote.canUseRemoteFolderArtwork
import com.mica.music.data.remote.isRemoteSidecarArtworkFile
import com.mica.music.data.remote.remoteArtworkRevisionKey
import com.mica.music.data.remote.selectRemoteFolderSidecarArtwork
import com.mica.music.data.remote.selectRemoteTrackSidecarArtwork
import com.mica.music.util.DiagnosticLog
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import com.thegrizzlylabs.sardineandroid.impl.handler.ResourcesResponseHandler
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class WebDavSyncResult(
    val sourceInstanceId: String,
    val configRevision: Long,
    val trackCount: Int,
    val metadataProbedCount: Int = 0,
    val metadataReusedCount: Int = 0,
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
    private val metadataProbe: RemoteTrackMetadataProbe? = null,
) {
    suspend fun testConnection(sourceInstanceId: String) {
        val session = beginSession(sourceInstanceId)
        listResources(session, session.root.toString(), depth = 0)
        ensureCurrent(session)
    }

    suspend fun sync(
        sourceInstanceId: String,
        limit: Int = Int.MAX_VALUE,
    ): WebDavSyncResult {
        require(limit >= 0) { "limit must not be negative" }
        val session = beginSession(sourceInstanceId)
        val reusableCatalog = catalogRepository.reusableCatalogIfCurrent(session.operation.token).orEmpty()
        ensureCurrent(session)
        val scan = if (limit == 0) ScanResult(emptyList(), 0, 0) else scan(session, limit, reusableCatalog)
        ensureCurrent(session)
        val published = catalogRepository.publishCatalogIfCurrent(
            token = session.operation.token,
            tracks = scan.tracks,
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
            trackCount = scan.tracks.size,
            metadataProbedCount = scan.metadataProbedCount,
            metadataReusedCount = scan.metadataReusedCount,
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
        val client = clientBuilder
            .addNetworkInterceptor(WebDavStrictRangeInterceptor())
            .build()
        return Session(
            owner = owner,
            operation = operation,
            root = WebDavPathCodec.sourceRoot(source.endpoint),
            client = client,
            adapter = WebDavCatalogAdapter(client),
        )
    }

    private suspend fun scan(
        session: Session,
        limit: Int,
        reusableCatalog: Map<String, RemoteTrackSummary>,
    ): ScanResult {
        val source = session.operation.source.instance
        val pending = ArrayDeque<String>()
        pending.addLast(session.root.toString())
        val visitedDirectories = linkedSetOf<String>()
        val tracks = ArrayList<RemoteTrackSummary>()
        var metadataProbedCount = 0
        var metadataReusedCount = 0

        while (pending.isNotEmpty() && tracks.size < limit) {
            ensureCurrent(session)
            val directoryUrl = pending.removeFirst()
            val directoryId = WebDavPathCodec.opaqueResourceId(source.endpoint, directoryUrl)
                ?: throw WebDavException(WebDavFailureKind.PROTOCOL, "WebDAV directory escaped configured root")
            if (!visitedDirectories.add(directoryId)) continue

            val resources = listResources(session, directoryUrl, depth = 1)
            ensureCurrent(session)
            val resolvedResources = resources.mapNotNull { resource ->
                val href = resource.href?.toString() ?: return@mapNotNull null
                val resolvedUrl = WebDavPathCodec.resolveResourceUrl(
                    endpoint = source.endpoint,
                    baseUrl = directoryUrl,
                    href = href,
                ) ?: return@mapNotNull null
                val resourceId = WebDavPathCodec.opaqueResourceId(source.endpoint, resolvedUrl.toString())
                    ?: return@mapNotNull null
                if (resourceId == directoryId) return@mapNotNull null
                ResolvedResource(resource, resolvedUrl, resourceId)
            }
            resolvedResources
                .filter { it.resource.isDirectory }
                .forEach { resolved ->
                    if (resolved.resourceId !in visitedDirectories) {
                        pending.addLast(WebDavPathCodec.asCollectionUrl(resolved.url).toString())
                    }
                }
            val artworkCandidates = resolvedResources.mapNotNull { resolved ->
                if (resolved.resource.isDirectory) return@mapNotNull null
                val fileName = resourceFileName(resolved.resource, resolved.resourceId)
                if (!isRemoteSidecarArtworkFile(fileName)) return@mapNotNull null
                val sizeBytes = (resolved.resource.contentLength ?: 0L).coerceAtLeast(0L)
                RemoteSidecarArtworkCandidate(
                    fileName = fileName,
                    resourceId = resolved.resourceId,
                    contentRevision = remoteArtworkRevisionKey(resolved.resource.contentRevision(), sizeBytes),
                    sizeBytes = sizeBytes,
                )
            }
            val folderArtwork = selectRemoteFolderSidecarArtwork(artworkCandidates)
            val audioResources = resolvedResources.filter { resolved ->
                !resolved.resource.isDirectory && isAudioResource(resolved.resource, resolved.resourceId)
            }
            val directoryComplete = tracks.size + audioResources.size <= limit
            val directoryStart = tracks.size

            for (resolved in audioResources) {
                if (tracks.size >= limit) break
                val resource = resolved.resource
                val resourceId = resolved.resourceId
                val fileName = resourceFileName(resource, resourceId)
                val artwork = selectRemoteTrackSidecarArtwork(fileName, artworkCandidates)
                val base = resource.toTrackSummary(source.id, resourceId).copy(
                    artworkOpaqueId = artwork?.let { candidate ->
                        RemoteFileArtworkIdCodec.encode(candidate.resourceId, candidate.contentRevision)
                    }.orEmpty(),
                )
                val reusable = reusableCatalog[resourceId]
                if (canReuseMetadata(base, reusable)) {
                    tracks += reuseMetadata(base, checkNotNull(reusable))
                    metadataReusedCount++
                } else {
                    tracks += enrichMetadata(session, resolved.url, base)
                    if (metadataProbe != null) metadataProbedCount++
                }
            }
            if (directoryComplete && folderArtwork != null && tracks.size > directoryStart) {
                val directoryTracks = tracks.subList(directoryStart, tracks.size)
                if (canUseRemoteFolderArtwork(directoryTracks)) {
                    val artworkId = RemoteFileArtworkIdCodec.encode(
                        folderArtwork.resourceId,
                        folderArtwork.contentRevision,
                    )
                    for (index in directoryStart until tracks.size) {
                        if (tracks[index].artworkOpaqueId.isBlank()) {
                            tracks[index] = tracks[index].copy(artworkOpaqueId = artworkId)
                        }
                    }
                }
            }
        }
        return ScanResult(
            tracks = tracks,
            metadataProbedCount = metadataProbedCount,
            metadataReusedCount = metadataReusedCount,
        )
    }

    private fun canReuseMetadata(base: RemoteTrackSummary, previous: RemoteTrackSummary?): Boolean =
        previous != null &&
            base.contentRevision.isNotBlank() &&
            previous.contentRevision == base.contentRevision &&
            previous.sizeBytes == base.sizeBytes &&
            (metadataProbe == null || previous.metadataProbeRevision == REMOTE_METADATA_PROBE_REVISION)

    private fun reuseMetadata(base: RemoteTrackSummary, previous: RemoteTrackSummary): RemoteTrackSummary {
        val artworkId = base.artworkOpaqueId.ifBlank {
            previous.artworkOpaqueId.takeIf { RemoteEmbeddedArtworkIdCodec.decode(it) != null }.orEmpty()
        }
        return previous.copy(
            ref = base.ref,
            mimeTypeHint = base.mimeTypeHint,
            fileName = base.fileName,
            suffix = base.suffix,
            sizeBytes = base.sizeBytes,
            contentRevision = base.contentRevision,
            artworkOpaqueId = artworkId,
        )
    }

    private suspend fun enrichMetadata(
        session: Session,
        resolvedUrl: HttpUrl,
        base: RemoteTrackSummary,
    ): RemoteTrackSummary {
        val probe = metadataProbe ?: return base
        if (base.sizeBytes <= 0L) return base
        ensureCurrent(session)
        val metadata = withContext(Dispatchers.IO) {
            runCatching {
                WebDavSeekableByteSource(
                    client = session.client,
                    url = resolvedUrl,
                    sizeBytes = base.sizeBytes,
                ).use { source ->
                    probe.probe(base.fileName, source)
                }
            }.onFailure { failure ->
                DiagnosticLog.event(
                    "RemoteMetadata",
                    "webdav-probe fallback song=${base.mediaId.takeLast(12)} error=${failure.javaClass.simpleName}",
                )
            }.getOrNull()
        }
        ensureCurrent(session)
        return metadata?.let { tags ->
            base.copy(
                title = tags.title.ifBlank { base.title },
                artist = tags.artist,
                album = tags.album,
                albumArtist = tags.albumArtist,
                durationSec = tags.durationSec,
                sampleRateHz = tags.sampleRateHz,
                bitsPerSample = tags.bitsPerSample,
                bitrateKbps = tags.bitrateKbps,
                channelCount = tags.channelCount,
                year = tags.year,
                trackNumber = tags.trackNumber,
                discNumber = tags.discNumber,
                metadataProbeRevision = REMOTE_METADATA_PROBE_REVISION,
                artworkOpaqueId = base.artworkOpaqueId.ifBlank {
                    if (tags.hasEmbeddedArtwork) {
                        RemoteEmbeddedArtworkIdCodec.encode(
                            base.ref.opaqueTrackId,
                            base.contentRevision,
                            base.sizeBytes,
                        )
                    } else {
                        ""
                    }
                },
            )
        } ?: base
    }

    private suspend fun listResources(
        session: Session,
        url: String,
        depth: Int,
    ): List<DavResource> {
        ensureCurrent(session)
        return withContext(Dispatchers.IO) {
            session.adapter.list(url, depth)
        }
    }

    private fun ensureCurrent(session: Session) {
        if (!session.owner.isCurrent(session.operation.token)) {
            throw WebDavException(WebDavFailureKind.STALE_OPERATION, "WebDAV source changed during listing")
        }
    }

    private fun isAudioResource(resource: DavResource, resourceId: String): Boolean {
        val suffix = extension(resourceFileName(resource, resourceId))
        if (suffix in LYRIC_SIDECAR_EXTENSIONS) return false
        val contentType = resource.contentType.orEmpty().lowercase(Locale.US)
        if (contentType.startsWith("audio/")) return true
        return suffix in AUDIO_EXTENSIONS
    }

    private fun DavResource.toTrackSummary(sourceInstanceId: String, resourceId: String): RemoteTrackSummary {
        val fileName = resourceFileName(this, resourceId)
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
            contentRevision = contentRevision(),
        )
    }

    private fun DavResource.contentRevision(): String {
        val etagPart = etag?.trim().orEmpty()
        val modifiedPart = modified?.time?.takeIf { it >= 0L }?.toString().orEmpty()
        if (etagPart.isBlank() && modifiedPart.isBlank()) return ""
        return "etag=$etagPart;mtime=$modifiedPart"
    }

    private fun resourceFileName(resource: DavResource, resourceId: String): String =
        resource.name?.takeIf(String::isNotBlank)
            ?: resourceId.substringAfterLast('/').ifBlank { resourceId }

    private fun extension(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .lowercase(Locale.US)

    private data class Session(
        val owner: RemoteSourceOwner,
        val operation: RemoteOperationSnapshot,
        val root: HttpUrl,
        val client: OkHttpClient,
        val adapter: WebDavCatalogAdapter,
    )

    private data class ResolvedResource(
        val resource: DavResource,
        val url: HttpUrl,
        val resourceId: String,
    )

    private data class ScanResult(
        val tracks: List<RemoteTrackSummary>,
        val metadataProbedCount: Int,
        val metadataReusedCount: Int,
    )

    companion object {
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "wma", "alac", "aiff", "aif",
        )
        private val LYRIC_SIDECAR_EXTENSIONS = setOf("lrc", "ttml")
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
