package com.mica.music.data.remote.webdav

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.RemoteArtworkRef
import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.RemoteFileArtworkIdCodec
import com.mica.music.data.remote.REMOTE_METADATA_PROBE_REVISION
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackMetadata
import com.mica.music.data.remote.RemoteTrackMetadataProbe
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebDavCatalogAdapterTest {
    private lateinit var database: MicaDatabase
    private lateinit var catalog: RemoteCatalogRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        catalog = RemoteCatalogRepository(database) { 12345L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun depthZeroAndRecursiveDepthOneFilterSelfByIdentityAndCanonicalizePaths() = runBlocking {
        val requests = CopyOnWriteArrayList<TestRequest>()
        TinyWebDavServer { request ->
            requests += request
            when (request.target) {
                "/music/" -> if (request.headers["depth"] == "0") {
                    multistatus(
                        collection("/music/", "music"),
                    )
                } else {
                    multistatus(
                        file("/music/Root%20Song.flac", "Root Song.flac", 101, "audio/flac"),
                        collection("/music/", "music"),
                        collection("sub/", "sub"),
                        file("/outside/Escape.mp3", "Escape.mp3", 5, "audio/mpeg"),
                        file("http://evil.example/music/Evil.mp3", "Evil.mp3", 5, "audio/mpeg"),
                    )
                }
                "/music/sub/" -> multistatus(
                    file("%E9%9F%B3%E4%B9%90%20A.flac", "音乐 A.flac", 202, "audio/flac"),
                    collection("/music/sub/", "sub"),
                )
                else -> TestResponse(status = 404)
            }
        }.use { server ->
            val sourceId = createSource("${server.baseUrl}/music")
            val sync = WebDavSourceSync(catalog, credentialStore())

            sync.testConnection(sourceId)
            val result = sync.sync(sourceId)
            val tracks = catalog.tracksForSource(sourceId)

            assertEquals(2, result.trackCount)
            assertEquals(
                listOf("Root%20Song.flac", "sub/%E9%9F%B3%E4%B9%90%20A.flac"),
                tracks.map { it.ref.opaqueTrackId },
            )
            assertEquals(listOf("Root Song", "音乐 A"), tracks.map { it.title })
            assertEquals(listOf(101L, 202L), tracks.map { it.sizeBytes })
            assertTrue(requests.any { it.target == "/music/" && it.headers["depth"] == "0" })
            assertTrue(requests.any { it.target == "/music/" && it.headers["depth"] == "1" })
            assertTrue(requests.any { it.target == "/music/sub/" && it.headers["depth"] == "1" })
            assertFalse(requests.any { it.target.contains("outside", ignoreCase = true) })
        }
    }

    @Test
    fun sidecarArtworkIsDiscoveredWithoutDownloadingImageBytes() = runBlocking {
        val requests = CopyOnWriteArrayList<TestRequest>()
        TinyWebDavServer { request ->
            requests += request
            when (request.target) {
                "/music/" -> multistatus(
                    collection("/music/", "music"),
                    file("/music/Song.flac", "Song.flac", 100, "audio/flac", etag = "audio-1"),
                    file("/music/Song.jpg", "Song.jpg", 50, "image/jpeg", etag = "art-1"),
                )
                else -> TestResponse(status = 404)
            }
        }.use { server ->
            val sourceId = createSource("${server.baseUrl}/music")
            val sync = WebDavSourceSync(
                catalog,
                credentialStore(),
                metadataProbe = RemoteTrackMetadataProbe { _, _ ->
                    RemoteTrackMetadata(hasEmbeddedArtwork = true)
                },
            )

            val result = sync.sync(sourceId)
            val artwork = RemoteFileArtworkIdCodec.decode(
                catalog.tracksForSource(sourceId).single().artworkOpaqueId,
            )

            assertEquals(1, result.trackCount)
            assertEquals("Song.jpg", artwork?.resourceId)
            assertEquals("etag=art-1;mtime=", artwork?.contentRevision)
            assertTrue(requests.all { it.target == "/music/" })
        }
    }

    @Test
    fun embeddedArtworkHintPublishesTrackScopedArtworkAndProbeRevisionIsReusable() = runBlocking {
        val requests = CopyOnWriteArrayList<TestRequest>()
        TinyWebDavServer { request ->
            requests += request
            when (request.target) {
                "/music/" -> multistatus(
                    collection("/music/", "music"),
                    file("/music/Song.flac", "Song.flac", 100, "audio/flac", etag = "audio-1"),
                )
                else -> TestResponse(status = 404)
            }
        }.use { server ->
            val sourceId = createSource("${server.baseUrl}/music")
            var probeCalls = 0
            val sync = WebDavSourceSync(
                catalog,
                credentialStore(),
                metadataProbe = RemoteTrackMetadataProbe { _, _ ->
                    probeCalls++
                    RemoteTrackMetadata(title = "Tagged", hasEmbeddedArtwork = true)
                },
            )

            val first = sync.sync(sourceId)
            val firstTrack = catalog.tracksForSource(sourceId).single()
            val embedded = RemoteEmbeddedArtworkIdCodec.decode(firstTrack.artworkOpaqueId)
            val second = sync.sync(sourceId)
            val secondTrack = catalog.tracksForSource(sourceId).single()

            assertEquals(1, first.metadataProbedCount)
            assertEquals(1, second.metadataReusedCount)
            assertEquals(1, probeCalls)
            assertEquals("Song.flac", embedded?.resourceId)
            assertEquals("etag=audio-1;mtime=", embedded?.contentRevision)
            assertEquals(100L, embedded?.sizeBytes)
            assertEquals(REMOTE_METADATA_PROBE_REVISION, firstTrack.metadataProbeRevision)
            assertEquals(firstTrack.artworkOpaqueId, secondTrack.artworkOpaqueId)
            assertTrue(requests.all { it.target == "/music/" })
        }
    }

    @Test
    fun artworkResolverAndLoaderFetchCanonicalSidecarJustInTime() = runBlocking {
        val image = byteArrayOf(9, 8, 7, 6)
        val requests = CopyOnWriteArrayList<TestRequest>()
        TinyWebDavServer { request ->
            requests += request
            when (request.target) {
                "/music/cover.jpg" -> TestResponse(
                    status = 200,
                    headers = mapOf("Content-Type" to "image/jpeg"),
                    body = image,
                )
                else -> TestResponse(status = 404)
            }
        }.use { server ->
            val sourceId = createSource("${server.baseUrl}/music")
            val ref = RemoteArtworkRef(
                sourceId,
                RemoteFileArtworkIdCodec.encode("cover.jpg", "etag=art-1;mtime="),
            )
            val resolver = WebDavArtworkRequestResolver(
                sourceOwnerById = { id -> catalog.sourceOwner(id) },
                credentialStore = credentialStore(),
            )

            val request = requireNotNull(resolver.resolve(ref))
            val loaded = withContext(Dispatchers.IO) { WebDavArtworkByteLoader().load(request) }

            assertArrayEquals(image, loaded)
            assertEquals("/music/cover.jpg", requests.single().target)
            assertFalse(request.toString().contains("secret"))
        }
    }

    @Test
    fun embeddedArtworkResolverKeepsTrackUrlEphemeralAndSourceScoped() = runBlocking {
        TinyWebDavServer { TestResponse(status = 404) }.use { server ->
            val sourceId = createSource("${server.baseUrl}/music")
            val ref = RemoteArtworkRef(
                sourceId,
                RemoteEmbeddedArtworkIdCodec.encode("Album/Song.flac", "etag=audio-1;mtime=", 1234),
            )
            val resolver = WebDavEmbeddedArtworkRequestResolver(
                sourceOwnerById = { id -> catalog.sourceOwner(id) },
                credentialStore = credentialStore(),
            )

            val request = requireNotNull(resolver.resolve(ref))

            assertEquals("/music/Album/Song.flac", request.url.encodedPath)
            assertEquals(1234L, request.sizeBytes)
            assertEquals(1L, request.sourceConfigRevision)
            assertFalse(request.toString().contains("secret"))
        }
    }

    @Test
    fun xmlBody400FallsBackOnceToEmptyBodyAndStillUsesSardineParser() {
        val requests = CopyOnWriteArrayList<TestRequest>()
        TinyWebDavServer { request ->
            requests += request
            if (request.bodyLength > 0) {
                TestResponse(status = 400)
            } else {
                multistatus(file("/music/A.flac", "A.flac", 9, "audio/flac"))
            }
        }.use { server ->
            val adapter = WebDavCatalogAdapter(
                OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
            )

            val resources = adapter.list("${server.baseUrl}/music/", depth = 1)

            assertEquals(1, resources.size)
            assertEquals("A.flac", resources.single().name)
            assertEquals(2, requests.size)
            assertEquals("1", requests[0].headers["depth"])
            assertTrue(requests[0].bodyLength > 0)
            assertEquals(0, requests[1].bodyLength)
            assertEquals("1", requests[1].headers["depth"])
        }
    }

    @Test
    fun metadataRangeSourceRequiresMatchingPartialContent() {
        val bytes = ByteArray(16) { it.toByte() }
        TinyWebDavServer { request ->
            val range = request.headers["range"] ?: return@TinyWebDavServer TestResponse(status = 400)
            val start = range.substringAfter('=').substringBefore('-').toLong()
            val end = range.substringAfter('-').toLong()
            TestResponse(
                status = 206,
                headers = mapOf(
                    "Content-Range" to "bytes $start-$end/${bytes.size}",
                    "Content-Type" to "audio/flac",
                ),
                body = bytes.copyOfRange(start.toInt(), end.toInt() + 1),
            )
        }.use { server ->
            val source = WebDavSeekableByteSource(
                client = OkHttpClient(),
                url = "${server.baseUrl}/music/A.flac".toHttpUrl(),
                sizeBytes = bytes.size.toLong(),
            )
            val output = ByteArray(4)

            assertEquals(4, source.readAt(3, output, 0, output.size))
            assertEquals(listOf<Byte>(3, 4, 5, 6), output.toList())
        }
    }

    @Test
    fun metadataRangeSourceRejectsServerThatIgnoresRange() {
        TinyWebDavServer {
            TestResponse(
                status = 200,
                body = ByteArray(32) { it.toByte() },
            )
        }.use { server ->
            val source = WebDavSeekableByteSource(
                client = OkHttpClient(),
                url = "${server.baseUrl}/music/A.flac".toHttpUrl(),
                sizeBytes = 32,
            )

            val failure = runCatching { source.readAt(4, ByteArray(4), 0, 4) }.exceptionOrNull()
            assertTrue(failure is WebDavException)
            assertEquals(WebDavFailureKind.PROTOCOL, (failure as WebDavException).kind)
        }
    }

    @Test
    fun unchangedEtagReusesMetadataWithoutGetAndChangedEtagReprobes() = runBlocking {
        val requests = CopyOnWriteArrayList<TestRequest>()
        val bytes = byteArrayOf(10, 20, 30, 40)
        var etag = "v1"
        TinyWebDavServer { request ->
            requests += request
            when {
                request.target == "/music/" && request.headers["depth"] == "1" -> multistatus(
                    collection("/music/", "music"),
                    file("/music/A.flac", "A.flac", bytes.size.toLong(), "audio/flac", etag = etag),
                )
                request.target == "/music/A.flac" && request.headers["range"] != null -> {
                    val range = checkNotNull(request.headers["range"])
                    val start = range.substringAfter('=').substringBefore('-').toLong()
                    val end = range.substringAfter('-').toLong()
                    TestResponse(
                        status = 206,
                        headers = mapOf(
                            "Content-Range" to "bytes $start-$end/${bytes.size}",
                            "Content-Type" to "audio/flac",
                        ),
                        body = bytes.copyOfRange(start.toInt(), end.toInt() + 1),
                    )
                }
                else -> TestResponse(status = 404)
            }
        }.use { server ->
            val sourceId = createSource("${server.baseUrl}/music")
            var probeCalls = 0
            val sync = WebDavSourceSync(
                catalogRepository = catalog,
                credentialStore = credentialStore(),
                metadataProbe = RemoteTrackMetadataProbe { _, source ->
                    val header = ByteArray(4)
                    assertEquals(4, source.readAt(0, header, 0, header.size))
                    probeCalls++
                    RemoteTrackMetadata(
                        title = "Tagged $probeCalls",
                        artist = "Artist",
                        album = "Album",
                        durationSec = 12,
                    )
                },
            )

            val first = sync.sync(sourceId)
            val getCountAfterFirst = requests.count { it.headers.containsKey("range") }
            val second = sync.sync(sourceId)
            val getCountAfterSecond = requests.count { it.headers.containsKey("range") }
            etag = "v2"
            val third = sync.sync(sourceId)
            val getCountAfterThird = requests.count { it.headers.containsKey("range") }

            assertEquals(1, first.metadataProbedCount)
            assertEquals(0, first.metadataReusedCount)
            assertEquals(0, second.metadataProbedCount)
            assertEquals(1, second.metadataReusedCount)
            assertEquals(1, third.metadataProbedCount)
            assertEquals(0, third.metadataReusedCount)
            assertEquals(2, probeCalls)
            assertEquals(1, getCountAfterFirst)
            assertEquals(1, getCountAfterSecond)
            assertEquals(2, getCountAfterThird)
            val track = catalog.tracksForSource(sourceId).single()
            assertEquals("Tagged 2", track.title)
            assertEquals("etag=v2;mtime=", track.contentRevision)
        }
    }

    @Test
    fun metadataProbeFailureDoesNotBecomeReusableAndRetriesNextSync() = runBlocking {
        TinyWebDavServer { request ->
            when (request.target) {
                "/music/" -> multistatus(
                    collection("/music/", "music"),
                    file("/music/A.flac", "A.flac", 4, "audio/flac", etag = "v1"),
                )
                else -> TestResponse(status = 404)
            }
        }.use { server ->
            val sourceId = createSource("${server.baseUrl}/music")
            var probeCalls = 0
            val sync = WebDavSourceSync(
                catalogRepository = catalog,
                credentialStore = credentialStore(),
                metadataProbe = RemoteTrackMetadataProbe { _, _ ->
                    probeCalls++
                    if (probeCalls == 1) error("transient tag read")
                    RemoteTrackMetadata(title = "Recovered", artist = "Artist", album = "Album", durationSec = 9)
                },
            )

            val first = sync.sync(sourceId)
            val fallback = catalog.tracksForSource(sourceId).single()
            val second = sync.sync(sourceId)
            val recovered = catalog.tracksForSource(sourceId).single()

            assertEquals(1, first.metadataProbedCount)
            assertEquals("A", fallback.title)
            assertEquals(0, fallback.metadataProbeRevision)
            assertEquals(1, second.metadataProbedCount)
            assertEquals(0, second.metadataReusedCount)
            assertEquals(2, probeCalls)
            assertEquals("Recovered", recovered.title)
            assertEquals("Artist", recovered.artist)
            assertEquals("Album", recovered.album)
            assertEquals(9, recovered.durationSec)
            assertEquals(REMOTE_METADATA_PROBE_REVISION, recovered.metadataProbeRevision)
        }
    }

    @Test
    fun sourceEditWhileListingPreventsLateCatalogPublication() = runBlocking {
        val requestArrived = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        TinyWebDavServer { request ->
            if (request.target == "/music/" && request.headers["depth"] == "1") {
                requestArrived.countDown()
                assertTrue(allowResponse.await(5, TimeUnit.SECONDS))
            }
            multistatus(
                collection("/music/", "music"),
                file("/music/Old.flac", "Old.flac", 10, "audio/flac"),
            )
        }.use { server ->
            val sourceId = createSource("${server.baseUrl}/music")
            val sync = WebDavSourceSync(catalog, credentialStore())
            val operation = async(Dispatchers.IO) { runCatching { sync.sync(sourceId) } }
            assertTrue(requestArrived.await(5, TimeUnit.SECONDS))

            val current = requireNotNull(catalog.source(sourceId))
            catalog.upsertSource(current.copy(displayName = "Edited"))
            allowResponse.countDown()

            val result = withTimeout(5_000) { operation.await() }
            val failure = result.exceptionOrNull()
            assertTrue(failure is WebDavException)
            assertEquals(WebDavFailureKind.STALE_OPERATION, (failure as WebDavException).kind)
            assertTrue(catalog.tracksForSource(sourceId).isEmpty())
        }
    }

    private suspend fun createSource(endpoint: String): String {
        val source = RemoteSourceInstance(
            id = "webdav-1",
            type = RemoteSourceType.WEBDAV,
            displayName = "Files",
            endpoint = endpoint,
            credentialRef = "credential/webdav-1/1",
        )
        catalog.upsertSource(source)
        return source.id
    }

    private fun credentialStore(): SecureRemoteCredentialStore = SecureRemoteCredentialStore { ref ->
        RemoteCredentialSnapshot(
            credentialRef = ref,
            revision = 1,
            material = RemoteCredentialMaterial.UsernamePassword("alice", "secret"),
        )
    }

    private data class TestRequest(
        val target: String,
        val headers: Map<String, String>,
        val bodyLength: Int,
    )

    private data class TestResponse(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    private class TinyWebDavServer(
        private val handler: (TestRequest) -> TestResponse,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val closed = AtomicBoolean(false)
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val worker = thread(name = "webdav-catalog-test-http", isDaemon = true) {
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
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
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
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            var remaining = contentLength
            val buffer = CharArray(1024)
            while (remaining > 0) {
                val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                if (read <= 0) break
                remaining -= read
            }
            val response = handler(TestRequest(target, headers, contentLength))
            val reason = when (response.status) {
                207 -> "Multi-Status"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                else -> "Status"
            }
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 ${response.status} $reason\r\n".toByteArray(Charsets.US_ASCII))
            val responseHeaders = buildMap {
                putAll(response.headers)
                if (response.body.isNotEmpty()) putIfAbsent("Content-Type", "application/xml; charset=utf-8")
            }
            responseHeaders.forEach { (name, value) ->
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

    companion object {
        private fun multistatus(vararg resources: String): TestResponse = TestResponse(
            status = 207,
            body = """<?xml version="1.0" encoding="utf-8"?>
                |<D:multistatus xmlns:D="DAV:">
                |${resources.joinToString("\n")}
                |</D:multistatus>
            """.trimMargin().toByteArray(Charsets.UTF_8),
        )

        private fun collection(href: String, displayName: String): String = response(
            href = href,
            displayName = displayName,
            resourceType = "<D:resourcetype><D:collection/></D:resourcetype>",
            contentLength = 0,
            contentType = "httpd/unix-directory",
        )

        private fun file(
            href: String,
            displayName: String,
            contentLength: Long,
            contentType: String,
            etag: String? = null,
        ): String = response(
            href = href,
            displayName = displayName,
            resourceType = "<D:resourcetype/>",
            contentLength = contentLength,
            contentType = contentType,
            etag = etag,
        )

        private fun response(
            href: String,
            displayName: String,
            resourceType: String,
            contentLength: Long,
            contentType: String,
            etag: String? = null,
        ): String = """
            |<D:response>
            |  <D:href>$href</D:href>
            |  <D:propstat>
            |    <D:prop>
            |      <D:displayname>$displayName</D:displayname>
            |      $resourceType
            |      <D:getcontentlength>$contentLength</D:getcontentlength>
            |      <D:getcontenttype>$contentType</D:getcontenttype>
            |      ${etag?.let { "<D:getetag>$it</D:getetag>" }.orEmpty()}
            |    </D:prop>
            |    <D:status>HTTP/1.1 200 OK</D:status>
            |  </D:propstat>
            |</D:response>
        """.trimMargin()
    }
}
