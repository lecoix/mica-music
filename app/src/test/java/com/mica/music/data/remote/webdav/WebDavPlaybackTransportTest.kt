package com.mica.music.data.remote.webdav

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.remote.RemoteHttpAuthentication
import com.mica.music.data.remote.RemoteHttpPlaybackRequest
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.RemoteHttpRangePolicy
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemotePlaybackUriCodec
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.media.MicaRoutingDataSourceFactory
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebDavPlaybackTransportTest {
    @Test
    fun pathIdentityCanonicalizesEncodedUnicodeAndRejectsForeignOrigin() {
        val endpoint = "https://music.example/dav/library/"
        val href = "/dav/library/Artist/%E9%9F%B3%E4%B9%90%20A.flac"

        val opaque = WebDavPathCodec.opaqueTrackId(endpoint, href)

        assertEquals("Artist/%E9%9F%B3%E4%B9%90%20A.flac", opaque)
        assertEquals(
            "https://music.example/dav/library/Artist/%E9%9F%B3%E4%B9%90%20A.flac",
            WebDavPathCodec.resolveTrackUrl(endpoint, requireNotNull(opaque)).toString(),
        )
        assertEquals("", WebDavPathCodec.opaqueTrackId(endpoint, "/dav/library/"))
        assertEquals(null, WebDavPathCodec.opaqueTrackId(endpoint, "https://evil.example/dav/library/a.flac"))
        assertEquals(null, WebDavPathCodec.resolveTrackUrl(endpoint, "../escape.flac"))
    }

    @Test
    fun basicChallengeAuthenticatesOnceWithoutPreemptiveCredentialLeak() {
        val requests = CopyOnWriteArrayList<TestRequest>()
        TinyHttpServer { request ->
            requests += request
            if (request.headers["authorization"] == BASIC_ALICE_SECRET) {
                TestResponse(status = 200, body = "audio".toByteArray())
            } else {
                TestResponse(
                    status = 401,
                    headers = mapOf("WWW-Authenticate" to "Basic realm=\"dav\""),
                )
            }
        }.use { server ->
            val dataSource = webDavDataSource(server.baseUrl)

            dataSource.open(remoteSpec(position = 0))
            val result = readAll(dataSource)
            dataSource.close()

            assertEquals(2, requests.size)
            assertEquals(null, requests[0].headers["authorization"])
            assertEquals(BASIC_ALICE_SECRET, requests[1].headers["authorization"])
            assertEquals("audio", result.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun digestChallengeAuthenticatesOnceWithoutRetryLoop() {
        val requests = CopyOnWriteArrayList<TestRequest>()
        TinyHttpServer { request ->
            requests += request
            val authorization = request.headers["authorization"]
            if (authorization?.startsWith("Digest ") == true) {
                TestResponse(status = 200, body = "digest-audio".toByteArray())
            } else {
                TestResponse(
                    status = 401,
                    headers = mapOf(
                        "WWW-Authenticate" to
                            "Digest realm=\"dav\", nonce=\"abc123\", qop=\"auth\", algorithm=MD5, opaque=\"opaque1\"",
                    ),
                )
            }
        }.use { server ->
            val dataSource = webDavDataSource(server.baseUrl)

            dataSource.open(remoteSpec(position = 0))
            val result = readAll(dataSource)
            dataSource.close()

            assertEquals(2, requests.size)
            val digest = requests[1].headers["authorization"]
            assertNotNull(digest)
            assertTrue(requireNotNull(digest).startsWith("Digest "))
            assertTrue(digest.contains("username=\"alice\""))
            assertTrue(digest.contains("nonce=\"abc123\""))
            assertTrue(digest.contains("uri=\"/audio.flac\""))
            assertTrue(digest.contains("qop=auth"))
            assertEquals("digest-audio", result.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun strictNonZeroRangeAcceptsOnlyMatching206() {
        val payload = "0123456789".toByteArray()
        TinyHttpServer { request ->
            assertEquals("bytes=4-", request.headers["range"])
            TestResponse(
                status = 206,
                headers = mapOf("Content-Range" to "bytes 4-9/10"),
                body = payload.copyOfRange(4, payload.size),
            )
        }.use { server ->
            val dataSource = webDavDataSource(server.baseUrl)

            val opened = dataSource.open(remoteSpec(position = 4))
            val result = readAll(dataSource)
            dataSource.close()

            assertEquals(6L, opened)
            assertArrayEquals(payload.copyOfRange(4, payload.size), result)
        }
    }

    @Test
    fun strictNonZeroRangeRejectsServerIgnoringRangeWith200() {
        TinyHttpServer {
            TestResponse(status = 200, body = "0123456789".toByteArray())
        }.use { server ->
            val dataSource = webDavDataSource(server.baseUrl)

            val failure = runCatching { dataSource.open(remoteSpec(position = 4)) }.exceptionOrNull()
            runCatching { dataSource.close() }

            assertTrue(failure.hasCause<WebDavRangeException>())
        }
    }

    @Test
    fun strictRangeRejectsMismatchedContentRangeStart() {
        TinyHttpServer {
            TestResponse(
                status = 206,
                headers = mapOf("Content-Range" to "bytes 3-9/10"),
                body = "3456789".toByteArray(),
            )
        }.use { server ->
            val dataSource = webDavDataSource(server.baseUrl)

            val failure = runCatching { dataSource.open(remoteSpec(position = 4)) }.exceptionOrNull()
            runCatching { dataSource.close() }

            assertTrue(failure.hasCause<WebDavRangeException>())
        }
    }

    @Test
    fun authenticatedWebDavRedirectNeverContactsForeignTarget() {
        val targetHit = AtomicBoolean(false)
        TinyHttpServer {
            targetHit.set(true)
            TestResponse(status = 200, body = "stolen".toByteArray())
        }.use { target ->
            TinyHttpServer { request ->
                if (request.headers["authorization"] == BASIC_ALICE_SECRET) {
                    TestResponse(
                        status = 302,
                        headers = mapOf("Location" to "${target.baseUrl}/steal"),
                    )
                } else {
                    TestResponse(
                        status = 401,
                        headers = mapOf("WWW-Authenticate" to "Basic realm=\"dav\""),
                    )
                }
            }.use { source ->
                val dataSource = webDavDataSource(source.baseUrl)

                val failure = runCatching { dataSource.open(remoteSpec(position = 0)) }.exceptionOrNull()
                runCatching { dataSource.close() }

                assertNotNull(failure)
                assertFalse(targetHit.get())
            }
        }
    }

    @Test
    fun range416FailsOpenInsteadOfBecomingNaturalEof() {
        val requests = CopyOnWriteArrayList<TestRequest>()
        TinyHttpServer { request ->
            requests += request
            TestResponse(
                status = 416,
                headers = mapOf("Content-Range" to "bytes */10"),
            )
        }.use { server ->
            val dataSource = webDavDataSource(server.baseUrl)

            val failure = runCatching { dataSource.open(remoteSpec(position = 99)) }.exceptionOrNull()
            runCatching { dataSource.close() }

            assertNotNull(failure)
            assertEquals(1, requests.size)
            assertEquals("bytes=99-", requests.single().headers["range"])
        }
    }

    @Test
    fun failedBasicAuthenticationRetriesOnlyOnceAndFailsOpen() {
        val requests = CopyOnWriteArrayList<TestRequest>()
        TinyHttpServer { request ->
            requests += request
            TestResponse(
                status = 401,
                headers = mapOf("WWW-Authenticate" to "Basic realm=\"dav\""),
            )
        }.use { server ->
            val dataSource = webDavDataSource(server.baseUrl)

            val failure = runCatching { dataSource.open(remoteSpec(position = 0)) }.exceptionOrNull()
            runCatching { dataSource.close() }

            assertNotNull(failure)
            assertEquals(2, requests.size)
            assertEquals(null, requests[0].headers["authorization"])
            assertEquals(BASIC_ALICE_SECRET, requests[1].headers["authorization"])
        }
    }

    private fun webDavDataSource(baseUrl: String): DataSource {
        val mediaId = mediaId()
        return MicaRoutingDataSourceFactory(
            context = ApplicationProvider.getApplicationContext(),
            remoteResolver = RemoteHttpPlaybackRequestResolver { requestedId ->
                assertEquals(mediaId, requestedId)
                RemoteHttpPlaybackRequest(
                    url = "$baseUrl/audio.flac",
                    sourceInstanceId = "webdav-1",
                    sourceConfigRevision = 2,
                    credentialRevision = 3,
                    authentication = RemoteHttpAuthentication.UsernamePassword(
                        origin = baseUrl,
                        username = "alice",
                        password = "secret",
                    ),
                    rangePolicy = RemoteHttpRangePolicy.STRICT_PARTIAL_CONTENT,
                )
            },
        ).createDataSource()
    }

    private fun remoteSpec(position: Long): DataSpec = DataSpec.Builder()
        .setUri(Uri.parse(RemotePlaybackUriCodec.encode(mediaId())))
        .setPosition(position)
        .build()

    private fun mediaId(): String = RemoteMediaIdCodec.encode(RemoteTrackRef("webdav-1", "audio.flac"))

    private fun readAll(dataSource: DataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8)
        while (true) {
            val read = dataSource.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private inline fun <reified T : Throwable> Throwable?.hasCause(): Boolean {
        var current = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
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

    private class TinyHttpServer(
        private val handler: (TestRequest) -> TestResponse,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val closed = AtomicBoolean(false)
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val worker = thread(name = "webdav-playback-test-http", isDaemon = true) {
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
                302 -> "Found"
                401 -> "Unauthorized"
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

    companion object {
        private const val BASIC_ALICE_SECRET = "Basic YWxpY2U6c2VjcmV0"
    }
}
