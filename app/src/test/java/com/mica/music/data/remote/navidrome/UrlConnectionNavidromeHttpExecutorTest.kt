package com.mica.music.data.remote.navidrome

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UrlConnectionNavidromeHttpExecutorTest {
    @Test
    fun `same origin redirect keeps authenticated query`() = runTest {
        var receivedQuery: String? = null
        TinyHttpServer { target ->
            when {
                target.startsWith("/start") -> Response(302, headers = mapOf("Location" to "/target"))
                target.startsWith("/target") -> {
                    receivedQuery = target.substringAfter('?', "")
                    Response(200, body = "ok")
                }
                else -> Response(404)
            }
        }.use { server ->
            val request = request("${server.baseUrl}/start?u=alice&t=token&s=salt")

            val body = UrlConnectionNavidromeHttpExecutor().execute(request)

            assertEquals("ok", body)
            val params = queryMap(requireNotNull(receivedQuery))
            assertEquals("alice", params["u"])
            assertEquals("token", params["t"])
            assertEquals("salt", params["s"])
        }
    }

    @Test
    fun `cross origin redirect is rejected without contacting target`() = runTest {
        val targetHit = AtomicBoolean(false)
        TinyHttpServer {
            targetHit.set(true)
            Response(200, body = "stolen")
        }.use { target ->
            TinyHttpServer {
                Response(302, headers = mapOf("Location" to "${target.baseUrl}/steal"))
            }.use { source ->
                val error = try {
                    UrlConnectionNavidromeHttpExecutor().execute(
                        request("${source.baseUrl}/start?u=alice&t=secret&s=salt"),
                    )
                    null
                } catch (caught: NavidromeException) {
                    caught
                }

                requireNotNull(error)
                assertEquals(NavidromeFailureKind.REDIRECT_ORIGIN, error.kind)
                assertFalse(targetHit.get())
            }
        }
    }

    @Test
    fun `http 401 is typed auth failure`() = runTest {
        TinyHttpServer { Response(401) }.use { server ->
            val error = try {
                UrlConnectionNavidromeHttpExecutor().execute(
                    request("${server.baseUrl}/rest/ping?u=alice&t=secret&s=salt"),
                )
                null
            } catch (caught: NavidromeException) {
                caught
            }

            requireNotNull(error)
            assertEquals(NavidromeFailureKind.AUTH, error.kind)
            assertEquals(401, error.httpStatus)
        }
    }

    private fun request(url: String) = NavidromeRequest(
        method = NavidromeHttpMethod.GET,
        url = url,
        sourceInstanceId = "nav-1",
        sourceConfigRevision = 1,
        credentialRevision = 1,
    )

    private fun queryMap(rawQuery: String): Map<String, String> =
        rawQuery.split('&').associate { pair ->
            val parts = pair.split('=', limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }

    private data class Response(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: String = "",
    )

    private class TinyHttpServer(
        private val handler: (String) -> Response,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val closed = AtomicBoolean(false)
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val worker = thread(name = "navidrome-test-http", isDaemon = true) {
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
            val response = handler(target)
            val body = response.body.toByteArray(Charsets.UTF_8)
            val reason = when (response.status) {
                200 -> "OK"
                302 -> "Found"
                401 -> "Unauthorized"
                404 -> "Not Found"
                else -> "Status"
            }
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 ${response.status} $reason\r\n".toByteArray(Charsets.US_ASCII))
            response.headers.forEach { (name, value) ->
                output.write("$name: $value\r\n".toByteArray(Charsets.US_ASCII))
            }
            output.write("Content-Length: ${body.size}\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(body)
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
