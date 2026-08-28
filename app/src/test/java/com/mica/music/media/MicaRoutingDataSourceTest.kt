package com.mica.music.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.remote.RemoteHttpPlaybackRequest
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemotePlaybackUriCodec
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.RemoteTrackSummaryLookup
import com.mica.music.data.remote.toPlaybackSong
import com.mica.music.playback.toMediaItem
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class MicaRoutingDataSourceTest {
    @Test
    fun remoteSeekResolvesJustInTimeAndDelegatesExactRangeToMedia3OkHttp() {
        val payload = "0123456789".toByteArray()
        val requests = CopyOnWriteArrayList<TestRequest>()
        TinyHttpServer { request ->
            requests += request
            val range = request.headers["range"]
            if (range == "bytes=4-") {
                TestResponse(
                    status = 206,
                    headers = mapOf(
                        "Content-Range" to "bytes 4-9/10",
                        "Accept-Ranges" to "bytes",
                        "Content-Type" to "audio/flac",
                    ),
                    body = payload.copyOfRange(4, payload.size),
                )
            } else {
                TestResponse(status = 400)
            }
        }.use { server ->
            var resolverReads = 0
            val mediaId = remoteMediaId()
            val dataSource = factory(
                RemoteHttpPlaybackRequestResolver { requestedId ->
                    resolverReads += 1
                    assertEquals(mediaId, requestedId)
                    RemoteHttpPlaybackRequest(
                        url = "${server.baseUrl}/audio?t=secret&s=salt",
                        sourceInstanceId = "nav-1",
                        sourceConfigRevision = 3,
                        credentialRevision = 7,
                    )
                },
            ).createDataSource()
            val stableUri = RemotePlaybackUriCodec.encode(mediaId)
            val dataSpec = DataSpec.Builder()
                .setUri(Uri.parse(stableUri))
                .setPosition(4)
                .build()

            val openedLength = dataSource.open(dataSpec)
            val received = readAll(dataSource)
            dataSource.close()

            assertEquals(1, resolverReads)
            assertEquals(6L, openedLength)
            assertArrayEquals(payload.copyOfRange(4, payload.size), received)
            assertEquals("bytes=4-", requests.single().headers["range"])
            assertTrue(requests.single().target.contains("t=secret"))
            assertEquals(stableUri, dataSpec.uri.toString())
        }
    }

    @Test
    fun remoteLibrarySongQueueProjectionReachesTrustedJitPlaybackWithoutPersistingAuthUrl() = runTest {
        val payload = "remote-audio".toByteArray()
        val requests = CopyOnWriteArrayList<TestRequest>()
        TinyHttpServer { request ->
            requests += request
            TestResponse(
                status = 200,
                headers = mapOf("Content-Type" to "audio/flac"),
                body = payload,
            )
        }.use { server ->
            val ref = RemoteTrackRef("nav-acceptance", "track-42")
            val summary = RemoteTrackSummary(
                ref = ref,
                title = "Acceptance Song",
                artist = "Remote Artist",
                album = "Remote Album",
                durationSec = 42,
                mimeTypeHint = "audio/flac",
                fileName = "track-42.flac",
                sizeBytes = 4242L,
                year = 2026,
                trackNumber = 4,
            )
            val mediaId = RemoteMediaIdCodec.encode(ref)
            val stableUri = RemotePlaybackUriCodec.encode(mediaId)
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()

            // This is the exact projection and MediaItem conversion used by the Home queue path.
            val remoteSong = summary.toPlaybackSong()
            val controllerItem = remoteSong.toMediaItem(context)
            assertEquals(mediaId, remoteSong.id)
            assertEquals(stableUri, remoteSong.mediaUri)
            assertEquals(stableUri, controllerItem.localConfiguration?.uri?.toString())
            assertFalse(controllerItem.toString().contains("credential"))
            assertFalse(controllerItem.toString().contains("token"))

            // The service boundary discards caller-owned URI/metadata and rebuilds by stable remote ID.
            val provider = TrustedRemoteMediaItemProvider(
                RemoteTrackSummaryLookup { refs ->
                    assertEquals(listOf(ref), refs)
                    mapOf(ref to summary)
                },
            )
            val trustedResolver = TrustedMediaItemResolver(
                transientSongById = { null },
                librarySongsById = { emptyMap() },
                remoteMediaItemsById = provider::resolve,
            )
            val trustedItem = trustedResolver.resolve(listOf(controllerItem)).mediaItems.single()
            assertEquals(mediaId, trustedItem.mediaId)
            assertEquals(stableUri, trustedItem.localConfiguration?.uri?.toString())

            var jitResolvedId: String? = null
            val dataSource = factory(
                RemoteHttpPlaybackRequestResolver { requestedId ->
                    jitResolvedId = requestedId
                    RemoteHttpPlaybackRequest(
                        url = "${server.baseUrl}/audio?u=alice&t=secret-token&s=secret-salt",
                        sourceInstanceId = ref.sourceInstanceId,
                        sourceConfigRevision = 3,
                        credentialRevision = 9,
                    )
                },
            ).createDataSource()
            val playbackSpec = DataSpec(trustedItem.localConfiguration!!.uri)

            dataSource.open(playbackSpec)
            val received = readAll(dataSource)
            dataSource.close()

            assertEquals(mediaId, jitResolvedId)
            assertArrayEquals(payload, received)
            assertEquals(stableUri, playbackSpec.uri.toString())
            assertTrue(requests.single().target.contains("secret-token"))
            assertFalse(trustedItem.toString().contains("secret-token"))
            assertFalse(controllerItem.toString().contains("secret-token"))
        }
    }

    @Test
    fun reopenOfSameRemoteDataSourcePerformsFreshJitResolution() {
        val targets = CopyOnWriteArrayList<String>()
        TinyHttpServer { request ->
            targets += request.target
            TestResponse(status = 200, body = "ok".toByteArray())
        }.use { server ->
            var revision = 0
            val mediaId = remoteMediaId()
            val dataSource = factory(
                RemoteHttpPlaybackRequestResolver {
                    revision += 1
                    RemoteHttpPlaybackRequest(
                        url = "${server.baseUrl}/audio?credentialRevision=$revision",
                        sourceInstanceId = "nav-1",
                        sourceConfigRevision = 1,
                        credentialRevision = revision.toLong(),
                    )
                },
            ).createDataSource()
            val spec = DataSpec(Uri.parse(RemotePlaybackUriCodec.encode(mediaId)))

            repeat(2) {
                dataSource.open(spec)
                assertEquals("ok", readAll(dataSource).toString(Charsets.UTF_8))
                dataSource.close()
            }

            assertEquals(2, revision)
            assertEquals(2, targets.size)
            assertTrue(targets[0].contains("credentialRevision=1"))
            assertTrue(targets[1].contains("credentialRevision=2"))
        }
    }

    @Test
    fun remoteRedirectIsNotFollowedAndNeverContactsRedirectTarget() {
        val targetHit = AtomicBoolean(false)
        TinyHttpServer {
            targetHit.set(true)
            TestResponse(status = 200, body = "stolen".toByteArray())
        }.use { target ->
            TinyHttpServer {
                TestResponse(
                    status = 302,
                    headers = mapOf("Location" to "${target.baseUrl}/steal"),
                )
            }.use { source ->
                val mediaId = remoteMediaId()
                val dataSource = factory(
                    RemoteHttpPlaybackRequestResolver {
                        RemoteHttpPlaybackRequest(
                            url = "${source.baseUrl}/audio?t=secret&s=salt",
                            sourceInstanceId = "nav-1",
                            sourceConfigRevision = 1,
                            credentialRevision = 1,
                        )
                    },
                ).createDataSource()

                val failure = runCatching {
                    dataSource.open(DataSpec(Uri.parse(RemotePlaybackUriCodec.encode(mediaId))))
                }.exceptionOrNull()
                runCatching { dataSource.close() }

                assertTrue(failure is HttpDataSource.InvalidResponseCodeException)
                assertFalse(targetHit.get())
            }
        }
    }

    @Test
    fun missingRemoteResolutionFailsAsIoInsteadOfNaturalEof() {
        val dataSource = factory(RemoteHttpPlaybackRequestResolver { null }).createDataSource()
        val failure = runCatching {
            dataSource.open(DataSpec(Uri.parse(RemotePlaybackUriCodec.encode(remoteMediaId()))))
        }.exceptionOrNull()

        assertTrue("missing remote request must fail open as IO, not clean EOF", failure is IOException)
    }

    @Test
    fun ordinaryDataUriBypassesRemoteResolver() {
        var resolverRead = false
        val dataSource = factory(
            RemoteHttpPlaybackRequestResolver {
                resolverRead = true
                null
            },
        ).createDataSource()
        val encoded = android.util.Base64.encodeToString("local".toByteArray(), android.util.Base64.NO_WRAP)

        dataSource.open(DataSpec(Uri.parse("data:text/plain;base64,$encoded")))
        val result = readAll(dataSource)
        dataSource.close()

        assertEquals("local", result.toString(Charsets.UTF_8))
        assertFalse(resolverRead)
    }

    @Test
    fun remoteRequestToStringRedactsAuthenticatedUrl() {
        val request = RemoteHttpPlaybackRequest(
            url = "https://music.example/rest/stream?u=alice&t=super-secret&s=salt",
            sourceInstanceId = "nav-1",
            sourceConfigRevision = 2,
            credentialRevision = 4,
        )

        val text = request.toString()
        assertFalse(text.contains("alice"))
        assertFalse(text.contains("super-secret"))
        assertFalse(text.contains("salt"))
        assertTrue(text.contains("url=<redacted>"))
    }

    private fun factory(resolver: RemoteHttpPlaybackRequestResolver): MicaRoutingDataSourceFactory =
        MicaRoutingDataSourceFactory(
            context = ApplicationProvider.getApplicationContext(),
            remoteResolver = resolver,
        )

    private fun remoteMediaId(): String =
        RemoteMediaIdCodec.encode(RemoteTrackRef("nav-1", "track-9"))

    private fun readAll(dataSource: DataSource): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4)
        while (true) {
            val read = dataSource.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
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
        private val worker = thread(name = "remote-media3-test-http", isDaemon = true) {
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
                400 -> "Bad Request"
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
