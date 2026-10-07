package com.mica.music.data.remote.smb

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SmbStagedFolderIndexTest {
    @Test fun loaderCancellationKeepsEntriesAndDoesNotPublishLateCompletion() = runBlocking {
        val f = Fixture()
        val release = CountDownLatch(1)
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            f.repo.upsertSource(f.source)
            val reached = CompletableDeferred<Unit>()
            val loader = SmbFolderLibraryLoader(f.repo, f.indexer(RemoteTrackMetadataProbe { _, _ ->
                reached.complete(Unit)
                check(release.await(15, TimeUnit.SECONDS))
                RemoteTrackMetadata(title = "late")
            }), process)
            val id = loader.start(f.source.id, "Album", false)
            withTimeout(10_000) { reached.await() }
            assertEquals(id, loader.state.value.requestId)
            assertTrue(loader.state.value.entriesReady)
            assertTrue(loader.state.value.active)
            loader.cancel()
            val stopped = loader.state.value
            release.countDown()
            process.coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals(stopped, loader.state.value)
            assertFalse(stopped.active)
            assertEquals(0, f.repo.tracksForSource(f.source.id).single().metadataProbeRevision)
        } finally { release.countDown(); process.cancel(); f.db.close() }
    }

    @Test fun loaderCompletesWithoutKeepingCallerAlive() = runBlocking {
        val f = Fixture()
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            f.repo.upsertSource(f.source)
            val loader = SmbFolderLibraryLoader(f.repo, f.indexer(RemoteTrackMetadataProbe { _, _ ->
                RemoteTrackMetadata(title = "complete")
            }), process)
            val id = async { loader.start(f.source.id, "Album", false) }.await()
            val final = withTimeout(10_000) { loader.state.first { it.requestId == id && !it.active } }
            assertTrue(final.entriesReady)
            assertEquals("complete", f.repo.tracksForSource(f.source.id).single().title)
        } finally { process.cancel(); f.db.close() }
    }

    @Test fun removedScopeCannotBeRecreatedByOldProbe() = interleave("remove")
    @Test fun cancelledRequestCannotFillPublishedEntries() = interleave("cancel")
    @Test fun newerRequestWinsOverOldProbe() = interleave("replace")
    @Test fun deletedSourceCannotBeRecreatedByOldProbe() = interleave("delete")

    private fun interleave(action: String) = runBlocking {
        val f = Fixture()
        val release = CountDownLatch(1)
        try {
            f.repo.upsertSource(f.source)
            val request = checkNotNull(f.repo.beginSmbFolderIndex(f.source.id))
            val reached = CompletableDeferred<Unit>()
            val indexer = f.indexer(RemoteTrackMetadataProbe { _, _ ->
                reached.complete(Unit)
                check(release.await(15, TimeUnit.SECONDS))
                RemoteTrackMetadata(title = "old")
            })
            val old = async(Dispatchers.IO) {
                runCatching { indexer.index(request, "Album", false) { count -> assertEquals(1, count) } }
            }
            try {
                withTimeout(10_000) { reached.await() }
                val light = f.repo.tracksForSource(f.source.id).single()
                assertEquals("Song.flac", light.fileName)
                assertEquals(0, light.metadataProbeRevision)
                when (action) {
                    "remove" -> assertTrue(f.repo.removeSmbFolderScope(f.source.id, f.repo.smbFolderScopes(f.source.id).single().id))
                    "delete" -> assertTrue(f.repo.deleteSource(f.source.id))
                    "cancel" -> f.repo.cancelSmbFolderIndex(request)
                    "replace" -> assertTrue(f.indexer(RemoteTrackMetadataProbe { _, _ -> RemoteTrackMetadata(title = "new") }).index(f.source.id, "Album", false).published)
                }
            } finally { release.countDown() }
            val result = old.await()
            assertTrue(result.isFailure || result.getOrNull()?.published == false)
            val stored = f.repo.tracksForSource(f.source.id)
            when (action) {
                "remove", "delete" -> assertTrue(stored.isEmpty())
                "cancel" -> assertEquals(0, stored.single().metadataProbeRevision)
                "replace" -> assertEquals("new", stored.single().title)
            }
        } finally { release.countDown(); f.db.close() }
    }

    @Test fun earlyPublicationDoesNotEraseReusableTagsAndFailedProbeCanRetry() = runBlocking {
        val f = Fixture()
        try {
            f.repo.upsertSource(f.source)
            var probes = 0
            val good = f.indexer(RemoteTrackMetadataProbe { _, _ -> probes++; RemoteTrackMetadata(title = "Tagged") })
            assertTrue(good.index(f.source.id, "Album", false).published)
            var callbacks = 0
            good.indexStaged(f.source.id, "Album", false) {
                callbacks++
                assertEquals("Tagged", f.repo.tracksForSource(f.source.id).single().title)
            }
            assertEquals(1, callbacks)
            assertEquals(1, probes)
            f.revision = "changed"
            val failed = f.indexer(RemoteTrackMetadataProbe { _, _ -> null }).indexStaged(f.source.id, "Album", false) {}
            assertTrue(failed.published)
            assertEquals(1, failed.metadataFailedCount)
            assertEquals(0, f.repo.tracksForSource(f.source.id).single().metadataProbeRevision)
            assertTrue(good.indexStaged(f.source.id, "Album", false) {}.published)
            assertEquals("Tagged", f.repo.tracksForSource(f.source.id).single().title)
        } finally { f.db.close() }
    }

    private class Fixture {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MicaDatabase::class.java).allowMainThreadQueries().build()
        val repo = RemoteCatalogRepository(db)
        val source = RemoteSourceInstance(id = "staged", type = RemoteSourceType.SMB, displayName = "Test", endpoint = "smb://host/share", credentialRef = "credential")
        var revision = "v1"
        fun indexer(probe: RemoteTrackMetadataProbe) = SmbFolderLibraryIndexer(repo,
            SecureRemoteCredentialStore { RemoteCredentialSnapshot(it, 1, RemoteCredentialMaterial.Anonymous) },
            SmbSessionFactory { _, _ -> object : SmbSessionHandle {
                override fun list(serverPath: String) = listOf(SmbDirectoryEntry("Song.flac", false, 1000, revision))
                override fun openFile(serverPath: String) = object : SmbRandomAccessFile {
                    override val length = 1000L
                    override fun read(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int) = -1
                    override fun close() = Unit
                }
                override fun close() = Unit
            } }, probe)
    }
}
