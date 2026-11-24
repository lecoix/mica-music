package com.mica.music.data.remote.emby

import android.content.Context
import com.mica.music.data.remote.AndroidTagLibEmbeddedArtworkLoader
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.RemoteArtworkRef
import com.mica.music.data.remote.webdav.WebDavSeekableByteSource
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class EmbyArtworkRequest(
    val url: String,
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
    val apiKey: String,
    val embeddedSizeBytes: Long? = null,
) {
    override fun toString(): String =
        "EmbyArtworkRequest(source=$sourceInstanceId, config=$sourceConfigRevision, credential=$credentialRevision, url=<redacted>, apiKey=<redacted>)"
}

internal class EmbyArtworkRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
) {
    suspend fun resolve(ref: RemoteArtworkRef): EmbyArtworkRequest? {
        val owner = sourceOwnerById(ref.sourceInstanceId) ?: return null
        val snapshot = owner.beginOperationSnapshot()
        if (snapshot.source.instance.type != RemoteSourceType.EMBY || !snapshot.source.instance.enabled) return null
        val credential = credentialStore.resolve(snapshot.source.instance.credentialRef) ?: return null
        val key = EmbyProtocol.apiKey(credential)
        if (!owner.isCurrent(snapshot.token)) return null
        val embedded = RemoteEmbeddedArtworkIdCodec.decode(ref.opaqueArtworkId)
        if (embedded != null && embedded.sizeBytes <= 0L) return null
        val url = if (embedded != null) {
            EmbyProtocol.stream(snapshot.source, embedded.resourceId)
        } else {
            EmbyProtocol.artwork(snapshot.source, ref.opaqueArtworkId)
        }
        val request = EmbyArtworkRequest(
            url = url,
            sourceInstanceId = ref.sourceInstanceId,
            sourceConfigRevision = snapshot.source.configRevision,
            credentialRevision = credential.revision,
            apiKey = key,
            embeddedSizeBytes = embedded?.sizeBytes,
        )
        return request.takeIf { owner.isCurrent(snapshot.token) }
    }
}

internal class EmbyArtworkLoader(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
    private val maxBytes: Int = 16 * 1024 * 1024,
) {
    // Emby exposes a direct byte-range stream. Smaller metadata windows avoid
    // transferring multiple MiB per list thumbnail without affecting SMB/WebDAV.
    private val embeddedLoader = AndroidTagLibEmbeddedArtworkLoader(context, readAheadBytes = 256 * 1024)

    suspend fun load(request: EmbyArtworkRequest): ByteArray = withContext(Dispatchers.IO) {
        if (request.embeddedSizeBytes != null) {
            // Auth stays on the exact source request; the public URI never contains a token.
            // TagLib issues byte-range reads rather than downloading the full audio file.
            val authenticated = client.newBuilder()
                .followRedirects(false).followSslRedirects(false)
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder()
                        .header(EmbyProtocol.TOKEN_HEADER, request.apiKey).build())
                }.build()
            val cover = WebDavSeekableByteSource(
                client = authenticated,
                url = request.url.toHttpUrl(),
                sizeBytes = request.embeddedSizeBytes,
            ).use(embeddedLoader::load)
            if (cover.size > maxBytes) throw IOException("Emby embedded artwork exceeds size limit")
            return@withContext cover
        }
        client.newCall(Request.Builder().url(request.url)
            .header(EmbyProtocol.TOKEN_HEADER, request.apiKey).get().build()).execute().use { response ->
            if (response.code in listOf(401, 403)) throw IOException("Emby artwork authentication failed")
            if (!response.isSuccessful) throw IOException("Emby artwork HTTP ${response.code}")
            val body = response.body ?: throw IOException("Emby artwork is empty")
            if (body.contentLength() > maxBytes) throw IOException("Emby artwork exceeds size limit")
            val bytes = ByteArrayOutputStream()
            body.byteStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (bytes.size() + count > maxBytes) throw IOException("Emby artwork exceeds size limit")
                    bytes.write(buffer, 0, count)
                }
            }
            bytes.toByteArray()
        }
    }
}
