package com.mica.music.data.remote.navidrome

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.SecureRemoteCredentialStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavidromeSourceSyncTest {
    private lateinit var database: MicaDatabase
    private lateinit var repository: RemoteCatalogRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RemoteCatalogRepository(database) { 5000L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun wholeSyncUsesOneCredentialSnapshotAndPublishesOneSourceSnapshot() = runTest {
        val source = source()
        repository.upsertSource(source)
        var credentialReads = 0
        val credentialStore = SecureRemoteCredentialStore {
            credentialReads += 1
            RemoteCredentialSnapshot(
                credentialRef = source.credentialRef,
                revision = 7,
                material = RemoteCredentialMaterial.UsernamePassword("alice", "secret"),
            )
        }
        val requests = mutableListOf<String>()
        val executor = NavidromeHttpExecutor { request ->
            requests += request.url
            when {
                request.url.contains("songOffset=0") -> searchResponse(
                    songJson("1", "One", path = "Album/01.flac"),
                    songJson("2", "Two", path = "Album/02.flac"),
                )
                request.url.contains("songOffset=2") -> searchResponse(
                    songJson("3", "Three", path = "Album/03.flac"),
                )
                else -> error("Unexpected request ${request.url}")
            }
        }
        val sync = NavidromeSourceSync(
            catalogRepository = repository,
            credentialStore = credentialStore,
            executor = executor,
            requestFactory = NavidromeRequestFactory(saltProvider = { "fixedsalt" }),
            pageSize = 2,
        )

        val result = sync.sync(source.id)

        assertEquals(1, credentialReads)
        assertEquals(3, result.trackCount)
        assertEquals(1L, result.configRevision)
        assertEquals(listOf("1", "2", "3"), repository.tracksForSource(source.id).map { it.ref.opaqueTrackId })
        assertEquals(listOf("01.flac", "02.flac", "03.flac"), repository.tracksForSource(source.id).map { it.fileName })
        assertTrue(requests.all { it.contains("u=alice") && it.contains("s=fixedsalt") })
        assertEquals(1L, database.remoteSourceDao().getById(source.id)!!.catalogRevision)
    }

    @Test
    fun sourceEditDuringNetworkRequestRejectsWholeSyncAndKeepsPreviousCatalog() = runTest {
        val source = source()
        repository.upsertSource(source)
        val initialOperation = repository.beginOperation(source.id)!!.token
        assertTrue(
            repository.publishCatalogIfCurrent(
                initialOperation,
                listOf(RemoteTrackSummary(RemoteTrackRef(source.id, "existing"), "Existing")),
            ),
        )
        val credentialStore = SecureRemoteCredentialStore {
            RemoteCredentialSnapshot(
                credentialRef = source.credentialRef,
                revision = 1,
                material = RemoteCredentialMaterial.UsernamePassword("alice", "old-password"),
            )
        }
        val executor = NavidromeHttpExecutor {
            repository.upsertSource(source.copy(endpoint = "https://changed.example"))
            searchResponse(songJson("new", "New"))
        }
        val sync = NavidromeSourceSync(
            catalogRepository = repository,
            credentialStore = credentialStore,
            executor = executor,
            requestFactory = NavidromeRequestFactory(saltProvider = { "fixedsalt" }),
            pageSize = 2,
        )

        val failure = try {
            sync.sync(source.id)
            null
        } catch (caught: NavidromeException) {
            caught
        }

        requireNotNull(failure)
        assertEquals(NavidromeFailureKind.STALE_OPERATION, failure.kind)
        assertEquals(listOf("existing"), repository.tracksForSource(source.id).map { it.ref.opaqueTrackId })
        assertEquals("https://changed.example", repository.source(source.id)?.endpoint)
    }

    private fun source() = RemoteSourceInstance(
        id = "nav-1",
        type = RemoteSourceType.NAVIDROME,
        displayName = "Home",
        endpoint = "https://music.example",
        credentialRef = "credential/nav-1",
    )

    private fun searchResponse(vararg songs: String): String =
        """{"subsonic-response":{"status":"ok","searchResult3":{"song":[${songs.joinToString(",")}]}}}"""

    private fun songJson(id: String, title: String, path: String = "$title.flac"): String =
        """{"id":"$id","title":"$title","artist":"Artist","album":"Album","albumArtist":"Album Artist","duration":120,"contentType":"audio/flac","suffix":"flac","coverArt":"cover-$id","albumId":"album-1","artistId":"artist-1","size":1234,"year":2026,"track":1,"discNumber":1,"path":"$path"}"""
}
