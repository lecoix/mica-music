package com.mica.music.data.remote.smb

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackMetadata
import com.mica.music.data.remote.RemoteTrackMetadataProbe
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
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
    fun metadataProbeEnrichesBrowseFieldsAndClosesRandomAccessFile() = runTest {
        val source = source()
        repository.upsertSource(source)
        val handle = FakeSessionHandle(
            entries = mapOf("Library" to listOf(SmbDirectoryEntry("Track.flac", false, 4))),
            files = mapOf("Library\\Track.flac" to byteArrayOf(1, 2, 3, 4)),
        )
        val probe = RemoteTrackMetadataProbe { fileName, byteSource ->
            assertEquals("Track.flac", fileName)
            assertEquals(4L, byteSource.sizeBytes)
            RemoteTrackMetadata(
                title = "Tagged title",
                artist = "Tagged artist",
                album = "Tagged album",
                albumArtist = "Tagged album artist",
                durationSec = 187,
                year = 2025,
                trackNumber = 3,
                discNumber = 2,
            )
        }
        val sync = SmbSourceSync(
            repository,
            credentials(),
            SmbSessionFactory { _, _ -> handle },
            metadataProbe = probe,
        )

        val result = sync.sync(source.id)

        assertEquals(1, result.trackCount)
        val track = repository.tracksForSource(source.id).single()
        assertEquals("Tagged title", track.title)
        assertEquals("Tagged artist", track.artist)
        assertEquals("Tagged album", track.album)
        assertEquals("Tagged album artist", track.albumArtist)
        assertEquals(187, track.durationSec)
        assertEquals(2025, track.year)
        assertEquals(3, track.trackNumber)
        assertEquals(2, track.discNumber)
        assertTrue(handle.openedFiles.single().closed)
    }

    @Test
    fun metadataProbeFailureFallsBackToFilenameWithoutAbortingSync() = runTest {
        val source = source()
        repository.upsertSource(source)
        val handle = FakeSessionHandle(
            entries = mapOf("Library" to listOf(SmbDirectoryEntry("Broken.flac", false, 3))),
            files = mapOf("Library\\Broken.flac" to byteArrayOf(1, 2, 3)),
        )
        val sync = SmbSourceSync(
            repository,
            credentials(),
            SmbSessionFactory { _, _ -> handle },
            metadataProbe = RemoteTrackMetadataProbe { _, _ -> error("bad tag") },
        )

        val result = sync.sync(source.id)

        assertEquals(1, result.trackCount)
        val track = repository.tracksForSource(source.id).single()
        assertEquals("Broken", track.title)
        assertEquals("", track.artist)
        assertEquals(0, track.durationSec)
        assertTrue(handle.openedFiles.single().closed)
    }

    @Test
    fun unchangedContentRevisionReusesMetadataWithoutOpeningFileAndChangedRevisionReprobes() = runTest {
        val source = source()
        repository.upsertSource(source)
        var revision = "file-7:1000"
        val handles = mutableListOf<FakeSessionHandle>()
        val factory = SmbSessionFactory { _, _ ->
            FakeSessionHandle(
                entries = mapOf(
                    "Library" to listOf(
                        SmbDirectoryEntry("Track.flac", false, 4, contentRevision = revision),
                    ),
                ),
                files = mapOf("Library\\Track.flac" to byteArrayOf(1, 2, 3, 4)),
            ).also(handles::add)
        }
        var probeCalls = 0
        val sync = SmbSourceSync(
            repository,
            credentials(),
            factory,
            metadataProbe = RemoteTrackMetadataProbe { _, _ ->
                probeCalls++
                RemoteTrackMetadata(title = "Tagged $probeCalls", artist = "Artist", durationSec = 10)
            },
        )

        val first = sync.sync(source.id)
        val second = sync.sync(source.id)
        revision = "file-7:2000"
        val third = sync.sync(source.id)

        assertEquals(1, first.metadataProbedCount)
        assertEquals(0, first.metadataReusedCount)
        assertEquals(0, second.metadataProbedCount)
        assertEquals(1, second.metadataReusedCount)
        assertEquals(1, third.metadataProbedCount)
        assertEquals(0, third.metadataReusedCount)
        assertEquals(2, probeCalls)
        assertEquals(1, handles[0].openedFiles.size)
        assertTrue(handles[1].openedFiles.isEmpty())
        assertEquals(1, handles[2].openedFiles.size)
        assertEquals("Tagged 2", repository.tracksForSource(source.id).single().title)
        assertEquals("file-7:2000", repository.tracksForSource(source.id).single().contentRevision)
    }

    @Test
    fun metadataProbesUseBoundedParallelismAndPreserveCatalogOrder() = runTest {
        val source = source()
        repository.upsertSource(source)
        val entries = (0 until 8).map { index ->
            SmbDirectoryEntry(
                name = "Track$index.flac",
                isDirectory = false,
                sizeBytes = 4,
                contentRevision = "file-$index:1000",
            )
        }
        val files = (0 until 8).associate { index ->
            "Library\\Track$index.flac" to byteArrayOf(1, 2, 3, 4)
        }
        val handle = FakeSessionHandle(
            entries = mapOf("Library" to entries),
            files = files,
        )
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val probe = RemoteTrackMetadataProbe { fileName, _ ->
            val nowActive = active.incrementAndGet()
            maxActive.updateAndGet { current -> maxOf(current, nowActive) }
            try {
                Thread.sleep(80)
                RemoteTrackMetadata(title = "Tagged ${fileName.substringBefore('.')}")
            } finally {
                active.decrementAndGet()
            }
        }
        val sync = SmbSourceSync(
            repository,
            credentials(),
            SmbSessionFactory { _, _ -> handle },
            metadataProbe = probe,
        )

        val result = sync.sync(source.id, allowMetadataReuse = false)

        assertEquals(8, result.metadataProbedCount)
        assertEquals(0, result.metadataReusedCount)
        assertTrue("metadata probing should overlap", maxActive.get() >= 2)
        assertTrue("metadata probing must stay bounded", maxActive.get() <= 4)
        assertEquals(
            (0 until 8).map { "Tagged Track$it" },
            repository.tracksForSource(source.id).map { it.title },
        )
        assertEquals(8, handle.openedFiles.size)
        assertTrue(handle.openedFiles.all { it.closed })
    }

    @Test
    fun generationChangeDuringMetadataProbeCannotPublishEnrichedCatalog() = runTest {
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
            entries = mapOf("Library" to listOf(SmbDirectoryEntry("new.flac", false, 3))),
            files = mapOf("Library\\new.flac" to byteArrayOf(1, 2, 3)),
        )
        val sync = SmbSourceSync(
            repository,
            credentials(),
            SmbSessionFactory { _, _ -> handle },
            metadataProbe = RemoteTrackMetadataProbe { _, _ ->
                owner.invalidateOperations()
                RemoteTrackMetadata(title = "New")
            },
        )

        val failure = runCatching { sync.sync(source.id) }.exceptionOrNull()

        assertTrue(failure is SmbException)
        assertEquals(SmbFailureKind.STALE_OPERATION, (failure as SmbException).kind)
        assertEquals(listOf("old.flac"), repository.tracksForSource(source.id).map { it.ref.opaqueTrackId })
        assertTrue(handle.openedFiles.single().closed)
        assertFalse(handle.open)
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
        private val files: Map<String, ByteArray> = emptyMap(),
    ) : SmbSessionHandle {
        val listedPaths = mutableListOf<String>()
        val openedFiles = Collections.synchronizedList(mutableListOf<FakeRandomAccessFile>())
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

        override fun openFile(serverPath: String): SmbRandomAccessFile {
            val bytes = files[serverPath] ?: error("No fake file for $serverPath")
            return FakeRandomAccessFile(bytes).also(openedFiles::add)
        }

        override fun close() {
            open = false
        }
    }

    private class FakeRandomAccessFile(
        private val bytes: ByteArray,
    ) : SmbRandomAccessFile {
        override val length: Long = bytes.size.toLong()
        var closed = false

        override fun read(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (fileOffset >= bytes.size) return -1
            val count = minOf(length, bytes.size - fileOffset.toInt())
            bytes.copyInto(buffer, offset, fileOffset.toInt(), fileOffset.toInt() + count)
            return count
        }

        override fun close() {
            closed = true
        }
    }
}
