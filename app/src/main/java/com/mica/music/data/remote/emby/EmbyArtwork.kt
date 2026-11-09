package com.mica.music.data.remote.emby

import com.mica.music.data.remote.RemoteArtworkRef
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class EmbyArtworkRequest(
    val url: String,
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
    val apiKey: String,
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
        val request = EmbyArtworkRequest(
            url = EmbyProtocol.artwork(snapshot.source, ref.opaqueArtworkId),
            sourceInstanceId = ref.sourceInstanceId,
            sourceConfigRevision = snapshot.source.configRevision,
            credentialRevision = credential.revision,
            apiKey = key,
        )
        return request.takeIf { owner.isCurrent(snapshot.token) }
    }
}

internal class EmbyArtworkLoader(
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
    private val maxBytes: Int = 16 * 1024 * 1024,
) {
    suspend fun load(request: EmbyArtworkRequest): ByteArray = withContext(Dispatchers.IO) {
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
