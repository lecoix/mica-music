package com.mica.music.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.LyricsDocument
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.navidrome.NavidromeHttpExecutor
import com.mica.music.data.remote.navidrome.NavidromeRequestFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteSourceManagerTest {
    private lateinit var database: MicaDatabase
    private lateinit var repository: RemoteCatalogRepository
    private lateinit var credentials: FakeCredentialStore
    private var credentialCounter = 0

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RemoteCatalogRepository(database) { 9000L }
        credentials = FakeCredentialStore()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createNavidromePersistsOnlyOpaqueCredentialReference() = runTest {
        val manager = manager(executor = NavidromeHttpExecutor { okResponse() })

        val source = manager.createNavidrome(
            displayName = " Home ",
            endpoint = "https://music.example/navidrome/",
            username = " alice ",
            password = "secret-password",
        )

        assertEquals("nav-1", source.id)
        assertEquals("Home", source.displayName)
        assertEquals("https://music.example/navidrome", source.endpoint)
        assertEquals("credential/nav-1/1", source.credentialRef)
        val stored = database.remoteSourceDao().getById(source.id)!!
        assertEquals(source.credentialRef, stored.credentialRef)
        val material = credentials.resolve(source.credentialRef)!!.material as RemoteCredentialMaterial.UsernamePassword
        assertEquals("alice", material.username)
        assertEquals("secret-password", material.password)
        val sourceRow = stored.toString()
        assertFalse(sourceRow.contains("alice"))
        assertFalse(sourceRow.contains("secret-password"))
    }

    @Test
    fun credentialRotationAllocatesNewRefBeforeSwitchingSourcePointer() = runTest {
        val manager = manager(executor = NavidromeHttpExecutor { okResponse() })
        val created = manager.createNavidrome("Home", "https://old.example", "alice", "old")
        val oldRef = created.credentialRef
        var sourceRefObservedDuringPut: String? = null
        credentials.onPut = { _, _ ->
            sourceRefObservedDuringPut = repository.source(created.id)?.credentialRef
        }

        val updated = manager.rotateNavidromeCredentials(created.id, "alice", "new")

        assertEquals(oldRef, sourceRefObservedDuringPut)
        assertNotEquals(oldRef, updated.credentialRef)
        assertEquals(updated.credentialRef, repository.source(created.id)?.credentialRef)
        assertEquals(2L, repository.sourceSnapshot(created.id)?.configRevision)
        assertEquals("new", (credentials.resolve(updated.credentialRef)!!.material as RemoteCredentialMaterial.UsernamePassword).password)
        assertEquals("old", (credentials.resolve(oldRef)!!.material as RemoteCredentialMaterial.UsernamePassword).password)
    }

    @Test
    fun testAndSyncUseConfiguredSourceWithoutPersistingAuthenticatedUrl() = runTest {
        val requests = mutableListOf<String>()
        val executor = NavidromeHttpExecutor { request ->
            requests += request.url
            when {
                request.url.contains("/rest/ping?") -> okResponse()
                request.url.contains("/rest/search3?") -> searchResponse()
                else -> error("Unexpected request ${request.url}")
            }
        }
        val manager = manager(executor)
        val source = manager.createNavidrome("Home", "https://music.example", "alice", "secret")

        manager.testConnection(source.id)
        val sync = manager.syncNavidrome(source.id)

        assertEquals(1, sync.trackCount)
        val statuses = manager.statuses()
        assertEquals(1, statuses.single().trackCount)
        assertEquals(1L, statuses.single().catalogRevision)
        assertEquals(9000L, statuses.single().lastSyncAtMs)
        assertEquals("song-1", repository.tracksForSource(source.id).single().ref.opaqueTrackId)
        assertTrue(requests.any { it.contains("u=alice") && it.contains("t=") && it.contains("s=fixedsalt") })
        val storedTrack = database.remoteTrackDao().getForSource(source.id).single().toString()
        assertFalse(storedTrack.contains("secret"))
        assertFalse(storedTrack.contains("/rest/stream"))
    }

    @Test
    fun successfulSyncInvalidatesRemoteLyricsMemoryForThatSourceIncludingRemovedTracks() = runTest {
        var searchCalls = 0
        val manager = manager(
            NavidromeHttpExecutor { request ->
                when {
                    request.url.contains("/rest/search3?") -> {
                        searchCalls++
                        searchResponse(if (searchCalls == 1) "song-1" else "song-2")
                    }
                    else -> okResponse()
                }
            },
        )
        val source = manager.createNavidrome("Home", "https://music.example", "alice", "secret")
        manager.syncNavidrome(source.id)
        val removedMediaId = repository.tracksForSource(source.id).single().mediaId
        SharedLyricsMemoryCache.load(removedMediaId, "test-revision", 77) { LyricsDocument() }
        assertTrue(SharedLyricsMemoryCache.get(removedMediaId, "test-revision", 77) != null)

        manager.syncNavidrome(source.id)

        assertEquals(null, SharedLyricsMemoryCache.get(removedMediaId, "test-revision", 77))
        assertEquals("song-2", repository.tracksForSource(source.id).single().ref.opaqueTrackId)
    }

    @Test
    fun disablingSourceInvalidatesOldOperationAndBlocksNetworkUse() = runTest {
        var calls = 0
        val manager = manager(NavidromeHttpExecutor { calls += 1; okResponse() })
        val source = manager.createNavidrome("Home", "https://music.example", "alice", "secret")
        val oldToken = repository.beginOperation(source.id)!!.token

        manager.setEnabled(source.id, false)

        assertFalse(repository.publishCatalogIfCurrent(oldToken, emptyList()))
        val error = runCatching { manager.testConnection(source.id) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, calls)
    }

    @Test
    fun webDavSourceUsesIndependentTypeIdentityAndCredentialRotation() = runTest {
        val manager = manager(executor = NavidromeHttpExecutor { okResponse() })
        val source = manager.createWebDav(
            displayName = " Files ",
            endpoint = "https://dav.example/music/",
            username = " alice ",
            password = "old-secret",
        )

        assertEquals(RemoteSourceType.WEBDAV, source.type)
        assertEquals("Files", source.displayName)
        assertEquals("https://dav.example/music", source.endpoint)
        val oldRef = source.credentialRef
        val rotated = manager.rotateWebDavCredentials(source.id, "alice", "new-secret")

        assertNotEquals(oldRef, rotated.credentialRef)
        assertEquals(RemoteSourceType.WEBDAV, repository.source(source.id)?.type)
        assertEquals("new-secret", (credentials.resolve(rotated.credentialRef)!!.material as RemoteCredentialMaterial.UsernamePassword).password)
        assertEquals("old-secret", (credentials.resolve(oldRef)!!.material as RemoteCredentialMaterial.UsernamePassword).password)
    }
    @Test
    fun smbSourceNormalizesShareEndpointAndRotatesOpaqueCredentialRef() = runTest {
        val manager = manager(executor = NavidromeHttpExecutor { okResponse() })
        val source = manager.createSmb(
            displayName = " NAS ",
            endpoint = " SMB://NAS.local:445/Music/My Albums/ ",
            username = " HOME\\alice ",
            password = "old-secret",
        )

        assertEquals(RemoteSourceType.SMB, source.type)
        assertEquals("NAS", source.displayName)
        assertEquals("smb://nas.local/Music/My%20Albums", source.endpoint)
        val oldRef = source.credentialRef
        val rotated = manager.rotateSmbCredentials(source.id, "HOME\\alice", "new-secret")

        assertNotEquals(oldRef, rotated.credentialRef)
        assertEquals("new-secret", (credentials.resolve(rotated.credentialRef)!!.material as RemoteCredentialMaterial.UsernamePassword).password)
        assertEquals("old-secret", (credentials.resolve(oldRef)!!.material as RemoteCredentialMaterial.UsernamePassword).password)
        assertEquals(
            "smb://nas.local/Music/Other",
            manager.updateSourceConfig(source.id, "NAS", "smb://NAS.local/Music/Other/", true).endpoint,
        )
    }
    @Test
    fun endpointValidationRejectsEmbeddedCredentialAndQuery() {
        val embedded = runCatching {
            RemoteSourceManager.normalizeHttpEndpoint("https://alice:secret@music.example")
        }.exceptionOrNull()
        val query = runCatching {
            RemoteSourceManager.normalizeHttpEndpoint("https://music.example?token=secret")
        }.exceptionOrNull()
        val badScheme = runCatching {
            RemoteSourceManager.normalizeHttpEndpoint("smb://music.example/share")
        }.exceptionOrNull()

        assertTrue(embedded is IllegalArgumentException)
        assertTrue(query is IllegalArgumentException)
        assertTrue(badScheme is IllegalArgumentException)
        assertEquals(
            "http://192.168.1.2:4533/navidrome",
            RemoteSourceManager.normalizeHttpEndpoint(" http://192.168.1.2:4533/navidrome/ "),
        )
    }

    private fun manager(executor: NavidromeHttpExecutor): RemoteSourceManager = RemoteSourceManager(
        catalogRepository = repository,
        credentialStore = credentials,
        navidromeExecutor = executor,
        navidromeRequestFactory = NavidromeRequestFactory(saltProvider = { "fixedsalt" }),
        sourceIdProvider = { _ -> "nav-1" },
        credentialRefProvider = { sourceId -> "credential/$sourceId/${++credentialCounter}" },
    )

    private fun okResponse(): String = """{"subsonic-response":{"status":"ok"}}"""

    private fun searchResponse(songId: String = "song-1"): String =
        """{"subsonic-response":{"status":"ok","searchResult3":{"song":[{"id":"$songId","title":"One","artist":"Artist","album":"Album","duration":120,"contentType":"audio/flac","suffix":"flac"}]}}}"""

    private class FakeCredentialStore : MutableSecureRemoteCredentialStore {
        private val values = linkedMapOf<String, RemoteCredentialSnapshot>()
        var onPut: (suspend (String, RemoteCredentialMaterial) -> Unit)? = null

        override suspend fun resolve(credentialRef: String): RemoteCredentialSnapshot? = values[credentialRef]

        override suspend fun put(
            credentialRef: String,
            material: RemoteCredentialMaterial,
        ): RemoteCredentialSnapshot {
            onPut?.invoke(credentialRef, material)
            val next = RemoteCredentialSnapshot(
                credentialRef = credentialRef,
                revision = (values[credentialRef]?.revision ?: 0L) + 1L,
                material = material,
            )
            values[credentialRef] = next
            return next
        }
    }
}
