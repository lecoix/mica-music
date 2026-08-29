package com.mica.music.data.remote.smb

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmbSourceSyncTest {
    private lateinit var database: MicaDatabase
    private lateinit var repository: RemoteCatalogRepository
    private val credential = RemoteCredentialSnapshot(
        credentialRef = "cred/smb-1",
        revision = 1L,
        material = RemoteCredentialMaterial.UsernamePassword("HOME\\alice", "secret"),
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RemoteCatalogRepository(database) { 12345L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testAndRecursiveSyncUseConfiguredRootAndStableRelativeIds() = runTest {
        val source = source()
        repository.upsertSource(source)
        val handle = FakeSessionHandle(
            mapOf(
                "Library" to listOf(
                    SmbDirectoryEntry(".", true, 0),
                    SmbDirectoryEntry("..", true, 0),
                    SmbDirectoryEntry("Album", true, 0),
                    SmbDirectoryEntry("Root.wav", false, 4000),
                    SmbDirectoryEntry("notes.txt", false, 20),
                ),
                "Library\\Album" to listOf(
                    SmbDirectoryEntry("Track.flac", false, 9000),
                ),
            ),
        )
        var openedEndpoint: SmbEndpoint? = null
        var openedLogin: SmbLogin? = null
        val factory = SmbSessionFactory { endpoint, login ->
            openedEndpoint = endpoint
            openedLogin = login
            handle
        }
        val sync = SmbSourceSync(repository, credentials(), factory)

        sync.testConnection(source.id)
        val result = sync.sync(source.id)

        assertEquals(2, result.trackCount)
        assertEquals("Music", openedEndpoint?.share)
        assertEquals("Library", openedEndpoint?.rootPath)
        assertEquals("HOME", openedLogin?.domain)
        assertEquals("alice", openedLogin?.username)
        assertEquals(
            listOf("Album/Track.flac", "Root.wav"),
            repository.tracksForSource(source.id).map { it.ref.opaqueTrackId }.sorted(),
        )
        assertEquals(1L, repository.sourceStatus(source.id)?.catalogRevision)
        assertEquals(12345L, repository.sourceStatus(source.id)?.lastSyncAtMs)
        assertTrue(handle.listedPaths.contains("Library"))
        assertTrue(handle.listedPaths.contains("Library\\Album"))
    }

    @Test
    fun staleGenerationCannotReplacePublishedCatalog() = runTest {
        val source = source()
        repository.upsertSource(source)
        val initial = repository.beginOperation(source.id)!!.token
        assertTrue(
            repository.publishCatalogIfCurrent(
                initial,
                listOf(RemoteTrackSummary(RemoteTrackRef(source.id, "old.flac"), title = "Old")),
            ),
        )
        val owner = repository.sourceOwner(source.id)!!
        val handle = FakeSessionHandle(
            entries = mapOf("Library" to listOf(SmbDirectoryEntry("new.flac", false, 10))),
            beforeFirstListReturn = { owner.invalidateOperations() },
        )
        val sync = SmbSourceSync(repository, credentials(), SmbSessionFactory { _, _ -> handle })

        val failure = runCatching { sync.sync(source.id) }.exceptionOrNull()

        assertTrue(failure is SmbException)
        assertEquals(SmbFailureKind.STALE_OPERATION, (failure as SmbException).kind)
        assertEquals(listOf("old.flac"), repository.tracksForSource(source.id).map { it.ref.opaqueTrackId })
        assertFalse(handle.open)
    }

    private fun source() = RemoteSourceInstance(
        id = "smb-1",
        type = RemoteSourceType.SMB,
        displayName = "NAS",
        endpoint = "smb://nas.local/Music/Library",
        credentialRef = credential.credentialRef,
    )

    private fun credentials() = SecureRemoteCredentialStore { ref ->
        credential.takeIf { it.credentialRef == ref }
    }

    private class FakeSessionHandle(
        private val entries: Map<String, List<SmbDirectoryEntry>>,
        private val beforeFirstListReturn: (() -> Unit)? = null,
    ) : SmbSessionHandle {
        val listedPaths = mutableListOf<String>()
        var open = true
        private var first = true

        override fun list(serverPath: String): List<SmbDirectoryEntry> {
            listedPaths += serverPath
            val result = entries[serverPath].orEmpty()
            if (first) {
                first = false
                beforeFirstListReturn?.invoke()
            }
            return result
        }

        override fun openFile(serverPath: String): SmbRandomAccessFile = error("not used")

        override fun close() {
            open = false
        }
    }
}
