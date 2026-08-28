package com.mica.music.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.navidrome.NavidromeHttpExecutor
import com.mica.music.data.remote.navidrome.NavidromeRequestFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteLyricsRepositoryTest {
    private lateinit var database: MicaDatabase
    private lateinit var catalog: RemoteCatalogRepository

    private val source = RemoteSourceInstance(
        id = "nav-cache",
        type = RemoteSourceType.NAVIDROME,
        displayName = "Cache",
        endpoint = "https://music.example",
        credentialRef = "credential-nav-cache",
    )
    private val track = RemoteTrackSummary(
        ref = RemoteTrackRef(source.id, "song-cache"),
        title = "Cache Song",
        artist = "Artist",
        durationSec = 100,
        sizeBytes = 1234,
    )
    private val credential = RemoteCredentialSnapshot(
        credentialRef = source.credentialRef,
        revision = 1,
        material = RemoteCredentialMaterial.UsernamePassword("alice", "password"),
    )

    @Before
    fun setUp() {
        SharedLyricsMemoryCache.clear()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        catalog = RemoteCatalogRepository(database)
    }

    @After
    fun tearDown() {
        SharedLyricsMemoryCache.clear()
        database.close()
    }

    @Test
    fun `same catalog revision reuses shared lyrics cache and new catalog revision reloads`() = runTest {
        publishCatalog()
        var calls = 0
        val repository = repository(
            NavidromeHttpExecutor {
                calls++
                structuredResponse("Line $calls")
            },
        )
        val song = track.toPlaybackSong()

        val first = repository.songWithLyrics(song)
        val second = repository.songWithLyrics(song)

        assertEquals(1, calls)
        assertTrue(first.lyricsLoaded)
        assertEquals("Line 1", first.lyricsDocument.lines.single().parts.single().text)
        assertEquals(first.lyricsDocument, second.lyricsDocument)

        publishCatalog()
        val third = repository.songWithLyrics(song)

        assertEquals(2, calls)
        assertEquals("Line 2", third.lyricsDocument.lines.single().parts.single().text)
    }

    @Test
    fun `network or auth failure is not cached as permanent no-lyrics result`() = runTest {
        publishCatalog()
        var calls = 0
        val repository = repository(
            NavidromeHttpExecutor {
                calls++
                """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Auth failed"}}}"""
            },
        )
        val song = track.toPlaybackSong()

        val first = repository.songWithLyrics(song)
        val second = repository.songWithLyrics(song)

        assertEquals(2, calls)
        assertFalse(first.lyricsLoaded)
        assertFalse(second.lyricsLoaded)
        assertTrue(first.lyricsDocument.lines.isEmpty())
    }

    private suspend fun publishCatalog() {
        if (catalog.source(source.id) == null) catalog.upsertSource(source)
        val operation = requireNotNull(catalog.beginOperation(source.id))
        assertTrue(catalog.publishCatalogIfCurrent(operation.token, listOf(track)))
    }

    private fun repository(executor: NavidromeHttpExecutor): RemoteLyricsRepository = RemoteLyricsRepository(
        catalogRepository = catalog,
        credentialStore = SecureRemoteCredentialStore { credential },
        navidromeExecutor = executor,
        navidromeRequestFactory = NavidromeRequestFactory(saltProvider = { "fixedsalt" }),
    )

    private fun structuredResponse(value: String): String =
        """{"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[{"line":[{"start":1000,"value":"$value"}]}]}}}"""
}
