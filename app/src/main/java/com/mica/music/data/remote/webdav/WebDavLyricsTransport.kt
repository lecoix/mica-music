package com.mica.music.data.remote.webdav

import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsSlots
import com.mica.music.data.Song
import com.mica.music.data.remote.RemoteEmbeddedLyricsLoader
import com.mica.music.data.remote.RemoteHttpAuthentication
import com.mica.music.data.remote.RemoteHttpPlaybackRequest
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import com.mica.music.data.scanner.ExternalLyricsReader
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** On-demand sidecar/embedded lyrics over WebDAV. No lyric payload is fetched during catalog sync. */
internal class WebDavLyricsLoader(
    private val requestResolver: RemoteHttpPlaybackRequestResolver,
    private val embeddedLoader: RemoteEmbeddedLyricsLoader,
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    suspend fun load(song: Song): LyricsDocument {
        val request = requestResolver.resolve(song.id) ?: return LyricsDocument()
        val audioUrl = request.url.toHttpUrlOrNull() ?: throw IOException("Invalid WebDAV lyrics track URL")
        return withContext(Dispatchers.IO) {
            val client = authenticatedClient(request)
            val audioName = song.fileName.ifBlank { audioUrl.pathSegments.lastOrNull().orEmpty() }
            val baseName = audioName.substringBeforeLast('.').trim()
            if (baseName.isNotEmpty()) {
                val externalTtml = firstSidecarDocument(client, audioUrl, baseName, listOf("ttml", "TTML"))
                if (externalTtml != null) return@withContext LyricsSlots(externalTtml = externalTtml).selected()
                val externalLrc = firstSidecarDocument(client, audioUrl, baseName, listOf("lrc", "LRC"))
                if (externalLrc != null) return@withContext LyricsSlots(externalLrc = externalLrc).selected()
            }

            if (song.sizeBytes <= 0L) throw IOException("WebDAV embedded lyrics source size is unavailable")
            val embedded = WebDavSeekableByteSource(
                client = client,
                url = audioUrl,
                sizeBytes = song.sizeBytes,
            ).use(embeddedLoader::load)
            LyricsSlots(embedded = embedded).selected()
        }
    }

    private fun authenticatedClient(request: RemoteHttpPlaybackRequest): OkHttpClient {
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
        return builder.build()
    }

    private fun firstSidecarDocument(
        client: OkHttpClient,
        audioUrl: HttpUrl,
        baseName: String,
        extensions: List<String>,
    ): LyricsDocument? {
        for (extension in extensions) {
            val url = siblingUrl(audioUrl, "$baseName.$extension")
            when (val result = readSidecar(client, url)) {
                SidecarResult.Missing -> Unit
                is SidecarResult.Found -> result.document?.let { return it }
            }
        }
        return null
    }

    private fun siblingUrl(audioUrl: HttpUrl, fileName: String): HttpUrl {
        val lastIndex = audioUrl.pathSegments.lastIndex
        if (lastIndex < 0) throw IOException("WebDAV track URL has no file segment")
        return audioUrl.newBuilder()
            .removePathSegment(lastIndex)
            .addPathSegment(fileName)
            .query(null)
            .fragment(null)
            .build()
    }

    private fun readSidecar(client: OkHttpClient, url: HttpUrl): SidecarResult {
        val call = client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .get()
                .build(),
        )
        try {
            call.execute().use { response ->
                if (response.code == 404) return SidecarResult.Missing
                if (response.code == 401 || response.code == 403) {
                    throw IOException("WebDAV lyrics authentication failed")
                }
                if (response.code != 200) throw IOException("WebDAV lyrics HTTP ${response.code}")
                val body = response.body ?: throw IOException("WebDAV lyrics response had no body")
                if (body.contentLength() > ExternalLyricsReader.MAX_EXTERNAL_LYRICS_BYTES) {
                    throw IOException("WebDAV lyrics exceeds size limit")
                }
                val bytes = body.byteStream().use(ExternalLyricsReader::readBoundedLyricsBytes)
                    ?: throw IOException("WebDAV lyrics exceeds size limit")
                return SidecarResult.Found(ExternalLyricsReader.parseRemoteDocument(bytes))
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("WebDAV lyrics read failed", failure)
        }
    }

    private sealed interface SidecarResult {
        data object Missing : SidecarResult
        data class Found(val document: LyricsDocument?) : SidecarResult
    }
}
