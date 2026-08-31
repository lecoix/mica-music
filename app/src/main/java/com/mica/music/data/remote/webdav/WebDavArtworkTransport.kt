package com.mica.music.data.remote.webdav

import android.content.Context
import com.mica.music.data.remote.AndroidTagLibEmbeddedArtworkLoader
import com.mica.music.data.remote.DEFAULT_REMOTE_ARTWORK_MAX_BYTES
import com.mica.music.data.remote.RemoteArtworkRef
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.RemoteFileArtworkIdCodec
import com.mica.music.data.remote.RemoteHttpAuthentication
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class WebDavArtworkRequest(
    val url: String,
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
    val authentication: RemoteHttpAuthentication?,
) {
    override fun toString(): String =
        "WebDavArtworkRequest(sourceInstanceId=$sourceInstanceId, " +
            "sourceConfigRevision=$sourceConfigRevision, credentialRevision=$credentialRevision, " +
            "url=<redacted>, authentication=<redacted>)"
}

internal data class WebDavEmbeddedArtworkRequest(
    val url: HttpUrl,
    val sizeBytes: Long,
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
    val authentication: RemoteHttpAuthentication?,
) {
    override fun toString(): String =
        "WebDavEmbeddedArtworkRequest(sourceInstanceId=$sourceInstanceId, " +
            "sourceConfigRevision=$sourceConfigRevision, credentialRevision=$credentialRevision, " +
            "sizeBytes=$sizeBytes, url=<redacted>, authentication=<redacted>)"
}

internal class WebDavArtworkRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
) {
    suspend fun resolve(ref: RemoteArtworkRef): WebDavArtworkRequest? {
        val target = RemoteFileArtworkIdCodec.decode(ref.opaqueArtworkId) ?: return null
        val owner = sourceOwnerById(ref.sourceInstanceId) ?: return null
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        if (source.type != RemoteSourceType.WEBDAV || !source.enabled) return null
        val credential = credentialStore.resolve(source.credentialRef) ?: return null
        if (!owner.isCurrent(operation.token)) return null
        val url = WebDavPathCodec.resolveTrackUrl(source.endpoint, target.resourceId) ?: return null
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
        return WebDavArtworkRequest(
            url = url.toString(),
            sourceInstanceId = source.id,
            sourceConfigRevision = operation.source.configRevision,
            credentialRevision = credential.revision,
            authentication = authentication,
        )
    }
}

internal class WebDavEmbeddedArtworkRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
) {
    suspend fun resolve(ref: RemoteArtworkRef): WebDavEmbeddedArtworkRequest? {
        val target = RemoteEmbeddedArtworkIdCodec.decode(ref.opaqueArtworkId) ?: return null
        val owner = sourceOwnerById(ref.sourceInstanceId) ?: return null
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        if (source.type != RemoteSourceType.WEBDAV || !source.enabled) return null
        val credential = credentialStore.resolve(source.credentialRef) ?: return null
        if (!owner.isCurrent(operation.token)) return null
        val url = WebDavPathCodec.resolveTrackUrl(source.endpoint, target.resourceId) ?: return null
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
        return WebDavEmbeddedArtworkRequest(
            url = url,
            sizeBytes = target.sizeBytes,
            sourceInstanceId = source.id,
            sourceConfigRevision = operation.source.configRevision,
            credentialRevision = credential.revision,
            authentication = authentication,
        )
    }
}

internal class WebDavArtworkByteLoader(
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val maxBytes: Long = DEFAULT_REMOTE_ARTWORK_MAX_BYTES,
) {
    init {
        require(maxBytes >= 0L) { "WebDAV artwork byte limit must be non-negative" }
    }

    fun load(request: WebDavArtworkRequest): ByteArray {
        val builder = baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
        when (val authentication = request.authentication) {
            null -> Unit
            is RemoteHttpAuthentication.UsernamePassword -> builder.authenticator(
                WebDavHttpAuthenticator(
                    origin = authentication.origin,
                    username = authentication.username,
                    password = authentication.password,
                ),
            )
        }
        val call = builder.build().newCall(
            Request.Builder()
                .url(request.url)
                .header("Accept-Encoding", "identity")
                .get()
                .build(),
        )
        try {
            call.execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    throw IOException("WebDAV artwork authentication failed")
                }
                if (response.code != 200) {
                    throw IOException("WebDAV artwork HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("WebDAV artwork response had no body")
                val declaredLength = body.contentLength()
                if (declaredLength > maxBytes) throw IOException("WebDAV artwork exceeds size limit")
                val initialCapacity = declaredLength
                    .takeIf { it in 1..minOf(maxBytes, Int.MAX_VALUE.toLong()) }
                    ?.toInt()
                    ?: DEFAULT_BUFFER_SIZE
                return ByteArrayOutputStream(initialCapacity).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            total += read
                            if (total > maxBytes) throw IOException("WebDAV artwork exceeds size limit")
                            output.write(buffer, 0, read)
                        }
                    }
                    output.toByteArray()
                }
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("WebDAV artwork read failed", failure)
        }
    }
}

internal class WebDavEmbeddedArtworkByteLoader(
    context: Context,
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    private val embeddedLoader = AndroidTagLibEmbeddedArtworkLoader(context)

    fun load(request: WebDavEmbeddedArtworkRequest): ByteArray {
        val builder = baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
        when (val authentication = request.authentication) {
            null -> Unit
            is RemoteHttpAuthentication.UsernamePassword -> builder.authenticator(
                WebDavHttpAuthenticator(
                    origin = authentication.origin,
                    username = authentication.username,
                    password = authentication.password,
                ),
            )
        }
        return WebDavSeekableByteSource(
            client = builder.build(),
            url = request.url,
            sizeBytes = request.sizeBytes,
        ).use(embeddedLoader::load)
    }
}
