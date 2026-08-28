package com.mica.music.data.remote

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.navidrome.NavidromeArtworkHttpStreamer
import com.mica.music.data.remote.navidrome.NavidromeArtworkRequestResolver
import com.mica.music.data.remote.navidrome.NavidromeStreamRequestResolver
import com.mica.music.media.MicaRoutingDataSourceFactory
import com.mica.music.media.TrustedRemoteMediaItemProvider
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavidromeMvpAcceptanceTest {
    private lateinit var database: MicaDatabase
    private lateinit var catalog: RemoteCatalogRepository
    private lateinit var credentials: InMemoryCredentialStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        catalog = RemoteCatalogRepository(database)
        credentials = InMemoryCredentialStore()
        com.mica.music.data.SharedLyricsMemoryCache.clear()
    }

    @After
    fun tearDown() {
        com.mica.music.data.SharedLyricsMemoryCache.clear()
        database.close()
    }

    @Test
    fun `one fake Navidrome server completes sync playback artwork and lyrics acceptance path`() = runTest {
        val audio = "0123456789-remote-audio".toByteArray()
        val artwork = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val requests = CopyOnWriteArrayList<TestRequest>()

        TinyNavidromeServer { request ->
            requests += request
            assertTrue(request.target.contains("u=alice"))
            assertTrue(request.target.contains("t="))
            assertTrue(request.target.contains("s="))
            when {
                request.target.startsWith("/rest/ping?") -> json(okResponse())
                request.target.startsWith("/rest/search3?") -> json(searchResponse())
                request.target.startsWith("/rest/stream?") -> {
                    val position = request.headers["range"]
                        ?.removePrefix("bytes=")
                        ?.substringBefore('-')
                        ?.toIntOrNull()
                        ?: 0
                    val body = audio.copyOfRange(position.coerceIn(0, audio.size), audio.size)
                    TestResponse(
                        status = if (position > 0) 206 else 200,
                        headers = buildMap {
                            put("Content-Type", "audio/flac")
                            if (position > 0) {
                                put("Content-Range", "bytes $position-${audio.lastIndex}/${audio.size}")
                            }
                        },
                        body = body,
                    )
                }
                request.target.startsWith("/rest/getCoverArt?") -> TestResponse(
                    status = 200,
                    headers = mapOf("Content-Type" to "image/jpeg"),
                    body = artwork,
                )
                request.target.startsWith("/rest/getLyricsBySongId?") -> json(structuredLyricsResponse())
                else -> TestResponse(status = 404)
            }
        }.use { server ->
            val manager = RemoteSourceManager(catalog, credentials)
            val source = manager.createNavidrome(
                displayName = "Acceptance",
                endpoint = server.baseUrl,
                username = "alice",
                password = "secret-password",
            )

            manager.testConnection(source.id)
            val sync = manager.syncNavidrome(source.id)
            assertEquals(1, sync.trackCount)

            val track = catalog.tracksForSource(source.id).single()
            assertEquals("track-42", track.ref.opaqueTrackId)
            assertEquals("cover-42", track.artworkOpaqueId)
            assertFalse(track.toString().contains("secret-password"))

            val mediaId = track.mediaId
            val trustedItem = TrustedRemoteMediaItemProvider(catalog)
                .resolve(listOf(mediaId))
                .getValue(mediaId)
            assertEquals(RemotePlaybackUriCodec.encode(mediaId), trustedItem.localConfiguration?.uri?.toString())
            assertFalse(trustedItem.toString().contains("secret-password"))

            val streamResolver = NavidromeStreamRequestResolver(
                sourceOwnerById = { sourceId -> catalog.sourceOwner(sourceId) },
                credentialStore = credentials,
            )
            val dataSource = MicaRoutingDataSourceFactory(
                context = ApplicationProvider.getApplicationContext(),
                remoteResolver = streamResolver,
            ).createDataSource()
            val dataSpec = DataSpec.Builder()
                .setUri(RemotePlaybackUriCodec.encode(mediaId))
                .setPosition(4)
                .build()
            dataSource.open(dataSpec)
            val receivedAudio = try {
                readAll(dataSource)
            } finally {
                dataSource.close()
            }
            assertArrayEquals(audio.copyOfRange(4, audio.size), receivedAudio)

            val artworkRef = RemoteArtworkRef(source.id, track.artworkOpaqueId)
            val artworkRequest = NavidromeArtworkRequestResolver(
                sourceOwnerById = { sourceId -> catalog.sourceOwner(sourceId) },
                credentialStore = credentials,
            ).resolve(artworkRef)
            assertNotNull(artworkRequest)
            val artworkOutput = ByteArrayOutputStream()
            NavidromeArtworkHttpStreamer().stream(requireNotNull(artworkRequest), artworkOutput)
            assertArrayEquals(artwork, artworkOutput.toByteArray())

            val hydrated = RemoteLyricsRepository(catalog, credentials)
                .songWithLyrics(track.toPlaybackSong())
            assertTrue(hydrated.lyricsLoaded)
            assertEquals(listOf(1234, 5678), hydrated.lyricsDocument.lines.map { it.startMs })
            assertEquals(
                listOf("First remote line", "Second remote line"),
                hydrated.lyricsDocument.lines.map { it.parts.single().text },
            )

            assertTrue(requests.any { it.target.startsWith("/rest/ping?") })
            assertTrue(requests.any { it.target.startsWith("/rest/search3?") })
            assertTrue(requests.any { it.target.startsWith("/rest/stream?") && it.headers["range"] == "bytes=4-" })
            assertTrue(requests.any { it.target.startsWith("/rest/getCoverArt?") })
            assertTrue(requests.any { it.target.startsWith("/rest/getLyricsBySongId?") })
        }
    }

    private fun readAll(dataSource: androidx.media3.datasource.DataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8)
        while (true) {
            val read = dataSource.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun json(body: String) = TestResponse(
        status = 200,
        headers = mapOf("Content-Type" to "application/json"),
        body = body.toByteArray(),
    )

    private fun okResponse(): String = """{"subsonic-response":{"status":"ok"}}"""

    private fun searchResponse(): String = """
        {"subsonic-response":{"status":"ok","searchResult3":{"song":[{
          "id":"track-42","title":"Acceptance Song","artist":"Remote Artist","album":"Remote Album",
          "albumArtist":"Remote Album Artist","duration":42,"contentType":"audio/flac","suffix":"flac",
          "coverArt":"cover-42","size":4242,"year":2026,"track":4,"discNumber":1
        }]}}}
    """.trimIndent()

    private fun structuredLyricsResponse(): String = """
        {"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[{"line":[
          {"start":1234,"value":"First remote line"},{"start":5678,"value":"Second remote line"}
        ]}]}}}
    """.trimIndent()

    private class InMemoryCredentialStore : MutableSecureRemoteCredentialStore {
        private val values = linkedMapOf<String, RemoteCredentialSnapshot>()

        override suspend fun resolve(credentialRef: String): RemoteCredentialSnapshot? = values[credentialRef]

        override suspend fun put(
            credentialRef: String,
            material: RemoteCredentialMaterial,
        ): RemoteCredentialSnapshot {
            val snapshot = RemoteCredentialSnapshot(
                credentialRef = credentialRef,
                revision = (values[credentialRef]?.revision ?: 0L) + 1L,
                material = material,
            )
            values[credentialRef] = snapshot
            return snapshot
        }
    }

    private data class TestRequest(
        val target: String,
        val headers: Map<String, String>,
    )

    private data class TestResponse(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    private class TinyNavidromeServer(
        private val handler: (TestRequest) -> TestResponse,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val closed = AtomicBoolean(false)
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val worker = thread(name = "navidrome-mvp-acceptance-http", isDaemon = true) {
            while (!closed.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                sockets += socket
                runCatching { serve(socket) }
                sockets -= socket
                runCatching { socket.close() }
            }
        }

        val baseUrl: String = "http://127.0.0.1:${server.localPort}"

        private fun serve(socket: Socket) {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            val target = requestLine.split(' ').getOrNull(1) ?: "/"
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                }
            }
            val response = handler(TestRequest(target, headers))
            val reason = when (response.status) {
                200 -> "OK"
                206 -> "Partial Content"
                404 -> "Not Found"
                else -> "Status"
            }
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 ${response.status} $reason\r\n".toByteArray(Charsets.US_ASCII))
            response.headers.forEach { (name, value) ->
                output.write("$name: $value\r\n".toByteArray(Charsets.US_ASCII))
            }
            output.write("Content-Length: ${response.body.size}\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(response.body)
            output.flush()
        }

        override fun close() {
            closed.set(true)
            runCatching { server.close() }
            sockets.forEach { socket -> runCatching { socket.close() } }
            worker.join(1_000)
        }
    }
}
