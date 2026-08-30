package com.mica.music.data.remote.webdav

import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.TrackMetadata
import com.mica.music.data.remote.RemoteEmbeddedLyricsLoader
import com.mica.music.data.remote.RemoteHttpPlaybackRequest
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavLyricsTransportTest {
    @Test
    fun sameNameTtmlWinsWithoutLrcOrEmbeddedRead() = runTest {
        val paths = mutableListOf<String>()
        var embeddedCalls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/dav/Album/Song.ttml" -> response(
                        request,
                        200,
                        """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="2s" end="3s">WebDAV TTML</p></div></body></tt>"""
                            .encodeToByteArray(),
                    )
                    else -> error("Unexpected WebDAV lyrics request ${request.url}")
                }
            }
            .build()
        val loader = WebDavLyricsLoader(
            requestResolver = RemoteHttpPlaybackRequestResolver { request() },
            embeddedLoader = RemoteEmbeddedLyricsLoader { _, _, _ ->
                embeddedCalls++
                embeddedDocument()
            },
            baseClient = client,
        )

        val document = loader.load(song())

        assertEquals(LyricsFormat.TTML, document.format)
        assertEquals(LyricsOrigin.EXTERNAL, document.origin)
        assertEquals("WebDAV TTML", document.lines.single().parts.single().text)
        assertEquals(listOf("/dav/Album/Song.ttml"), paths)
        assertEquals(0, embeddedCalls)
    }

    @Test
    fun missingSidecarsFallBackToStrictRangeEmbeddedRead() = runTest {
        val paths = mutableListOf<String>()
        val ranges = mutableListOf<String?>()
        val audio = byteArrayOf(10, 11, 12, 13, 14)
        var embeddedCalls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                paths += request.url.encodedPath
                ranges += request.header("Range")
                when {
                    request.url.encodedPath.endsWith(".ttml", ignoreCase = true) ||
                        request.url.encodedPath.endsWith(".lrc", ignoreCase = true) -> response(request, 404)
                    request.url.encodedPath == "/dav/Album/Song.flac" -> {
                        assertEquals("bytes=1-3", request.header("Range"))
                        response(
                            request = request,
                            code = 206,
                            body = audio.copyOfRange(1, 4),
                            headers = mapOf("Content-Range" to "bytes 1-3/5"),
                        )
                    }
                    else -> error("Unexpected WebDAV lyrics request ${request.url}")
                }
            }
            .build()
        val loader = WebDavLyricsLoader(
            requestResolver = RemoteHttpPlaybackRequestResolver { request() },
            embeddedLoader = RemoteEmbeddedLyricsLoader { source, fileName, mimeType ->
                embeddedCalls++
                assertEquals("Song.flac", fileName)
                assertEquals("audio/flac", mimeType)
                val bytes = ByteArray(3)
                assertEquals(3, source.readAt(1, bytes, 0, bytes.size))
                assertEquals(listOf<Byte>(11, 12, 13), bytes.toList())
                embeddedDocument()
            },
            baseClient = client,
        )

        val document = loader.load(song())

        assertEquals(1, embeddedCalls)
        assertEquals(LyricsOrigin.EMBEDDED, document.origin)
        assertEquals("Embedded line", document.lines.single().parts.single().text)
        assertEquals(
            listOf(
                "/dav/Album/Song.ttml",
                "/dav/Album/Song.TTML",
                "/dav/Album/Song.lrc",
                "/dav/Album/Song.LRC",
                "/dav/Album/Song.flac",
            ),
            paths,
        )
        assertTrue(ranges.take(4).all { it == null })
        assertEquals("bytes=1-3", ranges.last())
    }

    @Test
    fun requestToStringDoesNotExposeTrackUrl() {
        val value = request().toString()
        assertFalse(value.contains("Song.flac"))
        assertFalse(value.contains("dav/Album"))
    }

    private fun request() = RemoteHttpPlaybackRequest(
        url = "https://music.example/dav/Album/Song.flac",
        sourceInstanceId = "webdav-lyrics",
        sourceConfigRevision = 1,
        credentialRevision = 1,
    )

    private fun song() = Song(
        id = "remote-song",
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationSec = 10,
        metadata = TrackMetadata("FLAC", 44_100, 16, 0, 2, "audio/flac"),
        albumArtUri = null,
        coverColorArgb = 0,
        mediaUri = "mica-remote://song",
        fileName = "Song.flac",
        sizeBytes = 5,
        lyricsLoaded = false,
        source = SongSource.REMOTE,
    )

    private fun embeddedDocument() = LyricsDocument(
        format = LyricsFormat.PLAIN,
        origin = LyricsOrigin.EMBEDDED,
        lines = listOf(
            LyricLineNode(
                id = "embedded-1",
                startMs = 0,
                parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "Embedded line")),
            ),
        ),
    )

    private fun response(
        request: Request,
        code: Int,
        body: ByteArray = ByteArray(0),
        headers: Map<String, String> = emptyMap(),
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "Not Found")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .body(body.toResponseBody(null))
        .build()
}
