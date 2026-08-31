package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteHttpArtworkRequest
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavidromeArtworkHttpStreamerTest {
    @Test
    fun `same-origin redirect preserves authenticated query while streaming bytes`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val observedTarget = AtomicReference<String>()
        TinyHttpServer { request ->
            when {
                request.target.startsWith("/start") -> TestResponse(302, mapOf("Location" to "/image"))
                request.target.startsWith("/image") -> {
                    observedTarget.set(request.target)
                    TestResponse(200, mapOf("Content-Type" to "image/jpeg"), payload)
                }
                else -> TestResponse(404)
            }
        }.use { server ->
            val output = ByteArrayOutputStream()

            NavidromeArtworkHttpStreamer().stream(
                request("${server.baseUrl}/start?u=alice&t=secret&s=salt"),
                output,
            )

            assertArrayEquals(payload, output.toByteArray())
            assertEquals("/image?u=alice&t=secret&s=salt", observedTarget.get())
        }
    }

    @Test
    fun `cross-origin redirect is rejected before credentials leave original origin`() {
        TinyHttpServer { request ->
            if (request.target.startsWith("/start")) {
                TestResponse(302, mapOf("Location" to "http://localhost:${request.serverPort}/image"))
            } else {
                TestResponse(200)
            }
        }.use { server ->
            val failure = runCatching {
                NavidromeArtworkHttpStreamer().stream(
                    request("${server.baseUrl}/start?u=alice&t=secret&s=salt"),
                    ByteArrayOutputStream(),
                )
            }.exceptionOrNull()

            assertTrue(failure is NavidromeException)
            assertEquals(NavidromeFailureKind.REDIRECT_ORIGIN, (failure as NavidromeException).kind)
        }
    }

    @Test
    fun `declared artwork larger than limit is rejected without buffering it`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        TinyHttpServer { TestResponse(200, body = payload) }.use { server ->
            val failure = runCatching {
                NavidromeArtworkHttpStreamer(maxBytes = 4).stream(
                    request("${server.baseUrl}/image?u=alice&t=secret&s=salt"),
                    ByteArrayOutputStream(),
                )
            }.exceptionOrNull()

            assertTrue(failure is NavidromeException)
            assertEquals(NavidromeFailureKind.INVALID_RESPONSE, (failure as NavidromeException).kind)
        }
    }

    private fun request(url: String) = RemoteHttpArtworkRequest(
        url = url,
        sourceInstanceId = "nav-1",
        sourceConfigRevision = 2,
        credentialRevision = 3,
    )

    private data class TestRequest(
        val target: String,
        val serverPort: Int,
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
        private val worker = thread(name = "remote-artwork-test-http", isDaemon = true) {
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
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val response = handler(TestRequest(target = target, serverPort = server.localPort))
            val reason = when (response.status) {
                200 -> "OK"
                302 -> "Found"
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
