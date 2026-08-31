package com.mica.music.data.remote.navidrome

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.LyricsFormat
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.SecureRemoteCredentialStore
import com.mica.music.data.remote.toPlaybackSong
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavidromeLyricsLoaderTest {
    private lateinit var database: MicaDatabase
    private lateinit var repository: RemoteCatalogRepository

    private val source = RemoteSourceInstance(
        id = "nav-lyrics",
        type = RemoteSourceType.NAVIDROME,
        displayName = "Lyrics",
        endpoint = "https://music.example",
        credentialRef = "credential-nav-lyrics",
    )
    private val track = RemoteTrackSummary(
        ref = RemoteTrackRef(source.id, "track-9"),
        title = "Song Title",
        artist = "Artist Name",
        durationSec = 120,
    )
    private val credential = RemoteCredentialSnapshot(
        credentialRef = source.credentialRef,
        revision = 3,
        material = RemoteCredentialMaterial.UsernamePassword("alice", "password"),
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RemoteCatalogRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `structured lyrics are preferred without legacy request`() = runTest {
        seedCatalog()
        val requests = mutableListOf<String>()
        val loader = loader { request ->
            requests += request.url
            structuredResponse()
        }

        val document = loader.load(track.toPlaybackSong())

        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("/rest/getLyricsBySongId?"))
        assertEquals(LyricsFormat.LRC, document.format)
        assertEquals(listOf(1234, 5678), document.lines.map { it.startMs })
    }

    @Test
    fun `unsupported structured endpoint falls back to legacy lyrics`() = runTest {
        seedCatalog()
        val requests = mutableListOf<String>()
        val loader = loader { request ->
            requests += request.url
            if (request.url.contains("/rest/getLyricsBySongId?")) {
                failedResponse(code = 70, message = "Not supported")
            } else {
                legacyResponse("[00:02.00]Legacy line")
            }
        }

        val document = loader.load(track.toPlaybackSong())

        assertEquals(2, requests.size)
        assertTrue(requests[1].contains("/rest/getLyrics?"))
        assertTrue(requests[1].contains("artist=Artist%20Name"))
        assertTrue(requests[1].contains("title=Song%20Title"))
        assertEquals(2000, document.lines.single().startMs)
    }

    @Test
    fun `auth failure is not hidden by legacy fallback`() = runTest {
        seedCatalog()
        var calls = 0
        val loader = loader { _ ->
            calls++
            failedResponse(code = 40, message = "Wrong username or password")
        }

        val failure = runCatching { loader.load(track.toPlaybackSong()) }.exceptionOrNull()

        assertTrue(failure is NavidromeException)
        assertEquals(NavidromeFailureKind.AUTH, (failure as NavidromeException).kind)
        assertEquals(1, calls)
    }

    @Test
    fun `source edit while lyrics request is in flight fails stale`() = runTest {
        seedCatalog()
        val loader = loader { _ ->
            repository.upsertSource(source.copy(displayName = "Edited"))
            structuredResponse()
        }

        val failure = runCatching { loader.load(track.toPlaybackSong()) }.exceptionOrNull()

        assertTrue(failure is NavidromeException)
        assertEquals(NavidromeFailureKind.STALE_OPERATION, (failure as NavidromeException).kind)
    }

    private suspend fun seedCatalog() {
        repository.upsertSource(source)
        val operation = requireNotNull(repository.beginOperation(source.id))
        assertTrue(repository.publishCatalogIfCurrent(operation.token, listOf(track)))
    }

    private fun loader(executor: NavidromeHttpExecutor): NavidromeLyricsLoader = NavidromeLyricsLoader(
        catalogRepository = repository,
        credentialStore = SecureRemoteCredentialStore { credential },
        executor = executor,
        requestFactory = NavidromeRequestFactory(saltProvider = { "fixedsalt" }),
    )

    private fun structuredResponse(): String = """
        {"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[{"line":[
          {"start":1234,"value":"First"},{"start":5678,"value":"Second"}
        ]}]}}}
    """.trimIndent()

    private fun legacyResponse(value: String): String =
        """{"subsonic-response":{"status":"ok","lyrics":{"value":${org.json.JSONObject.quote(value)}}}}"""

    private fun failedResponse(code: Int, message: String): String =
        """{"subsonic-response":{"status":"failed","error":{"code":$code,"message":${org.json.JSONObject.quote(message)}}}}"""
}
