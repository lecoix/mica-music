package com.mica.music.data.remote.emby

import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.Song
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore
import com.mica.music.data.scanner.ExternalLyricsReader
import com.mica.music.data.scanner.LyricsSanitizer
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal fun interface EmbySubtitleTransport {
    suspend fun get(url: String, apiKey: String): ByteArray?
}

/** Bounded, authenticated subtitle bytes. 404 is a legitimate no-lyrics result. */
internal class OkHttpEmbySubtitleTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).build(),
) : EmbySubtitleTransport {
    override suspend fun get(url: String, apiKey: String): ByteArray? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder()
            .url(url)
            .header(EmbyProtocol.TOKEN_HEADER, apiKey)
            .header("Accept-Encoding", "identity")
            .get().build()).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (response.code == 401 || response.code == 403) {
                throw IOException("Emby lyrics authentication failed")
            }
            if (response.code != 200) throw IOException("Emby lyrics HTTP " + response.code)
            val body = response.body ?: throw IOException("Emby lyrics response had no body")
            if (body.contentLength() > ExternalLyricsReader.MAX_EXTERNAL_LYRICS_BYTES) {
                throw IOException("Emby lyrics exceed size limit")
            }
            body.byteStream().use { input ->
                ExternalLyricsReader.readBoundedLyricsBytes(input)
                    ?: throw IOException("Emby lyrics exceed size limit")
            }
        }
    }
}

/**
 * Playback-only lyrics hydration. No lyric payload is ever requested or stored by catalog sync.
 * Embedded text lives in the selected Emby media stream's Extradata; sidecar LRC is provided
 * by Emby's authenticated subtitle endpoint, never by guessing the server's filesystem path.
 */
internal class EmbyLyricsLoader(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentials: SecureRemoteCredentialStore,
    private val jsonTransport: EmbyJsonTransport = OkHttpEmbyJsonTransport(),
    private val subtitleTransport: EmbySubtitleTransport = OkHttpEmbySubtitleTransport(),
) {
    suspend fun load(song: Song): LyricsDocument {
        val ref = RemoteMediaIdCodec.decode(song.id) ?: return LyricsDocument()
        val owner = sourceOwnerById(ref.sourceInstanceId) ?: return LyricsDocument()
        val operation = owner.beginOperationSnapshot()
        if (!operation.source.instance.enabled || operation.source.instance.type != RemoteSourceType.EMBY) {
            return LyricsDocument()
        }
        val credential = credentials.resolve(operation.source.instance.credentialRef)
            ?: throw IOException("Emby lyrics credentials are missing")
        val key = EmbyProtocol.apiKey(credential)
        fun verifyCurrent() {
            if (!owner.isCurrent(operation.token)) throw IOException("Emby lyrics source changed")
        }

        verifyCurrent()
        val query = EmbyProtocol.url(
            operation.source, "/emby/Items",
            mapOf("Ids" to ref.opaqueTrackId, "Limit" to "1", "Fields" to "MediaSources"),
        )
        val json = jsonTransport.get(query, key)
        verifyCurrent()
        val itemList = try { JSONObject(json).optJSONArray("Items") }
            catch (_: Exception) { throw IOException("Invalid Emby lyrics metadata") }
        val item = (0 until (itemList?.length() ?: 0))
            .asSequence().mapNotNull { itemList?.optJSONObject(it) }
            .firstOrNull {
                it.optString("Id") == ref.opaqueTrackId && it.optString("Type") == "Audio"
            } ?: return LyricsDocument()
        val mediaSource = item.optJSONArray("MediaSources")?.optJSONObject(0) ?: return LyricsDocument()
        val streams = mediaSource.optJSONArray("MediaStreams") ?: return LyricsDocument()
        val candidates = (0 until streams.length())
            .mapNotNull(streams::optJSONObject)
            .filter { stream ->
                stream.optString("Type") == "Subtitle" &&
                    (stream.optString("Codec").equals("lrc", ignoreCase = true) ||
                        stream.optString("Title").equals("Lyrics", ignoreCase = true) ||
                        stream.optString("Extradata").contains("[00:"))
            }
        // Timestamped LRC takes precedence over non-LRC text alternatives.
        val selected = candidates.firstOrNull { it.optString("Codec").equals("lrc", true) }
            ?: candidates.firstOrNull() ?: return LyricsDocument()
        if (!selected.optBoolean("IsExternal")) {
            val embeddedText = selected.optString("Extradata").takeIf(String::isNotBlank)
                ?: return LyricsDocument()
            verifyCurrent()
            return LyricsSanitizer.parseFilteredDocument(embeddedText, LyricsOrigin.EMBEDDED)
        }

        val mediaSourceId = mediaSource.optString("Id").takeIf(String::isNotBlank)
            ?: return LyricsDocument()
        val index = selected.optInt("Index", -1).takeIf { it in 0..255 }
            ?: return LyricsDocument()
        val url = EmbyProtocol.lyricsSubtitle(
            operation.source, ref.opaqueTrackId, mediaSourceId, index,
        )
        verifyCurrent()
        val bytes = subtitleTransport.get(url, key) ?: return LyricsDocument()
        verifyCurrent()
        return ExternalLyricsReader.parseRemoteDocument(bytes) ?: LyricsDocument()
    }
}
