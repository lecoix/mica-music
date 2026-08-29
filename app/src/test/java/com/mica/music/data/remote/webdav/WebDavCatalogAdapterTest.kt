package com.mica.music.data.remote.webdav

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceType
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
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
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
        ): String = response(
            href = href,
            displayName = displayName,
            resourceType = "<D:resourcetype/>",
            contentLength = contentLength,
            contentType = contentType,
        )

        private fun response(
            href: String,
            displayName: String,
            resourceType: String,
            contentLength: Long,
            contentType: String,
        ): String = """
            |<D:response>
            |  <D:href>$href</D:href>
            |  <D:propstat>
            |    <D:prop>
            |      <D:displayname>$displayName</D:displayname>
            |      $resourceType
            |      <D:getcontentlength>$contentLength</D:getcontentlength>
            |      <D:getcontenttype>$contentType</D:getcontenttype>
            |    </D:prop>
            |    <D:status>HTTP/1.1 200 OK</D:status>
            |  </D:propstat>
            |</D:response>
        """.trimMargin()
    }
}