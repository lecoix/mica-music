package com.mica.music.data.remote.emby

import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteOperationSnapshot
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.SecureRemoteCredentialStore
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal fun interface EmbyJsonTransport {
    suspend fun get(url: String, apiKey: String): String
}

internal class OkHttpEmbyJsonTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).build(),
    private val maxResponseBytes: Int = 4 * 1024 * 1024,
) : EmbyJsonTransport {
    override suspend fun get(url: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header(EmbyProtocol.TOKEN_HEADER, apiKey)
            .header("Accept", "application/json").get().build()
        client.newCall(request).execute().use { response ->
            if (response.code in listOf(401, 403)) throw IOException("Emby authentication failed (${response.code})")
            if (!response.isSuccessful) throw IOException("Emby HTTP ${response.code}")
            val body = response.body ?: throw IOException("Emby response has no body")
            if (body.contentLength() > maxResponseBytes) throw IOException("Emby page is too large")
            body.byteStream().use { stream ->
                val data = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    if (data.size() + n > maxResponseBytes) throw IOException("Emby page is too large")
                    data.write(buffer, 0, n)
                }
                data.toString(Charsets.UTF_8.name())
            }
        }
    }
}

internal data class EmbyPage(val tracks: List<RemoteTrackSummary>, val rawCount: Int, val totalCount: Int)

/** A session pins one owner operation and credential revision across the whole enumeration. */
internal class EmbyCatalogClient(
    private val owner: RemoteSourceOwner,
    private val credentials: SecureRemoteCredentialStore,
    private val transport: EmbyJsonTransport = OkHttpEmbyJsonTransport(),
) {
    suspend fun beginSession(): Session {
        val operation = owner.beginOperationSnapshot()
        require(operation.source.instance.enabled && operation.source.instance.type == com.mica.music.data.remote.RemoteSourceType.EMBY) {
            "Emby source is unavailable"
        }
        val credential = credentials.resolve(operation.source.instance.credentialRef)
            ?: throw IOException("Emby credentials are missing")
        EmbyProtocol.apiKey(credential)
        check(owner.isCurrent(operation.token)) { "Emby source was modified" }
        return Session(owner, operation, credential, transport)
    }

    suspend fun testConnection() { beginSession().testConnection() }

    internal class Session(
        private val owner: RemoteSourceOwner,
        val operation: RemoteOperationSnapshot,
        private val credential: RemoteCredentialSnapshot,
        private val transport: EmbyJsonTransport,
    ) {
        suspend fun testConnection() {
            // Check actual Audio catalog access; unlike an unauthenticated server info endpoint,
            // this fails for invalid keys or insufficient item permissions.
            page(0, 1)
        }

        suspend fun page(offset: Int, count: Int): EmbyPage {
            require(offset >= 0 && count in 1..EmbyProtocol.PAGE_SIZE)
            val url = EmbyProtocol.url(operation.source, "/emby/Items", linkedMapOf(
                "IncludeItemTypes" to "Audio",
                "Recursive" to "true",
                "StartIndex" to offset.toString(),
                "Limit" to count.toString(),
                "Fields" to "Path,MediaSources,AlbumArtists,Artists,ParentId",
            ))
            if (!owner.isCurrent(operation.token)) throw IOException("Emby source changed during sync")
            val body = transport.get(url, EmbyProtocol.apiKey(credential))
            if (!owner.isCurrent(operation.token)) throw IOException("Emby source changed during sync")
            val json = try { JSONObject(body) } catch (_: Exception) { throw IOException("Invalid Emby library response") }
            val array = json.optJSONArray("Items") ?: throw IOException("Emby response has no Items array")
            val items = ArrayList<RemoteTrackSummary>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("Id").takeIf { it.isNotBlank() } ?: continue
                if (item.optString("Type") != "Audio") continue
                val artists = item.optJSONArray("Artists")
                val artist = if (artists != null && artists.length() > 0) artists.optString(0) else item.optString("Artist")
                val ticks = item.optLong("RunTimeTicks", 0L)
                val path = item.optString("Path")
                val source = item.optJSONArray("MediaSources")?.optJSONObject(0)
                val suffix = path.substringAfterLast('.', "").takeIf { it.length in 1..8 }.orEmpty()
                items += RemoteTrackSummary(
                    ref = RemoteTrackRef(operation.source.instance.id, id),
                    title = item.optString("Name").ifBlank { id },
                    artist = artist,
                    album = item.optString("Album"),
                    albumArtist = item.optString("AlbumArtist"),
                    durationSec = (ticks / 10_000_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    mimeTypeHint = source?.optString("MimeType").orEmpty(),
                    fileName = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { item.optString("Name") },
                    suffix = suffix,
                    sizeBytes = source?.optLong("Size", 0L)?.coerceAtLeast(0L) ?: 0L,
                    bitrateKbps = ((source?.optLong("Bitrate", 0L) ?: 0L) / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    year = item.optInt("ProductionYear", 0).coerceAtLeast(0),
                    trackNumber = item.optInt("IndexNumber", 0).coerceAtLeast(0),
                    discNumber = item.optInt("ParentIndexNumber", 0).coerceAtLeast(0),
                    albumOpaqueId = item.optString("AlbumId"),
                    artworkOpaqueId = item.optString("Id").takeIf { item.optJSONObject("ImageTags")?.optString("Primary").orEmpty().isNotBlank() }.orEmpty(),
                )
            }
            return EmbyPage(items, array.length(), json.optInt("TotalRecordCount", offset + array.length()))
        }
    }
}
