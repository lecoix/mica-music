package com.mica.music.data.remote.smb

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmbNowPlayingMetadataTest {
    @Test fun nonCancellableProbeFinishingAfterSourceEditCannotWriteMetadataOrArtwork() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MicaDatabase::class.java).allowMainThreadQueries().build()
        val release = CountDownLatch(1)
        try {
            val repo = RemoteCatalogRepository(db)
            val source = RemoteSourceInstance("smb", RemoteSourceType.SMB, "Home", "smb://router/share", "cred")
            repo.upsertSource(source)
            val track = RemoteTrackSummary(RemoteTrackRef(source.id, "song.flac"), "Filename", fileName = "song.flac", sizeBytes = 100, contentRevision = "v1")
            repo.registerSelectedTracks(repo.beginOperation(source.id)!!.token, listOf(track))
            val entered = CountDownLatch(1)
            var closed = false
            val loader = SmbNowPlayingMetadata(repo,
                SecureRemoteCredentialStore { RemoteCredentialSnapshot(it, 1, RemoteCredentialMaterial.Anonymous) },
                RemoteTrackMetadataProbe { _, _ ->
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    RemoteTrackMetadata(title = "Stale title", hasEmbeddedArtwork = true)
                },
                SmbSessionFactory { _, _ -> object : SmbSessionHandle {
                    override fun list(serverPath: String): List<SmbDirectoryEntry> = error("No traversal for metadata")
                    override fun close() { closed = true }
                    override fun openFile(serverPath: String): SmbRandomAccessFile = object : SmbRandomAccessFile {
                        override val length = 100L
                        override fun close() = Unit
                        override fun read(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int) = error("Probe controls reads")
                    }
                } },
            )
            val old = async(Dispatchers.Default) { loader.load(track.mediaId) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            repo.upsertSource(source.copy(credentialRef = "new"))
            release.countDown()
            assertNull(old.await())
            assertTrue(closed)
            assertEquals(track, repo.find(listOf(track.ref))[track.ref])
        } finally { release.countDown(); db.close() }
    }
}
