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

    @Test fun checkpointedProbedMetadataSurvivesCancellationAndIsReusedOnNextRefresh() = runBlocking {
        val f = MultiTrackFixture(trackCount = 4)
        val checkpointCommitted = CompletableDeferred<Unit>()
        val releaseRemaining = CountDownLatch(1)
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            f.repo.upsertSource(f.source)
            val probe = RemoteTrackMetadataProbe { fileName, _ ->
                if (fileName == "Track_03.flac" || fileName == "Track_04.flac") {
                    check(releaseRemaining.await(15, TimeUnit.SECONDS))
                }
                RemoteTrackMetadata(title = "Tagged $fileName")
            }
            val loader = SmbFolderLibraryLoader(
                repository = f.repo,
                indexer = f.indexer(probe, checkpointBatchSize = 2),
                scope = process,
            )
            loader.start(f.source.id, "Album", false)
            withTimeout(10_000) {
                loader.state.first { it.entriesReady && it.message.contains("（") }
                checkpointCommitted.complete(Unit)
            }
            checkpointCommitted.await()

            // Cancel while Track_03 and Track_04 are still blocked in probing.
            loader.cancel()
            releaseRemaining.countDown()
            process.coroutineContext[Job]!!.children.toList().joinAll()

            val persistedAfterCancel = f.repo.tracksForSource(f.source.id)
            assertEquals(4, persistedAfterCancel.size)
            val persistedProbed = persistedAfterCancel.filter { it.metadataProbeRevision == REMOTE_METADATA_PROBE_REVISION }
            assertEquals(2, persistedProbed.size)
            assertEquals(setOf("Tagged Track_01.flac", "Tagged Track_02.flac"), persistedProbed.map { it.title }.toSet())

            // Next refresh must reuse the 2 checkpointed tracks and only probe the remaining 2.
            val probedNames = java.util.Collections.synchronizedList(mutableListOf<String>())
            val refreshResult = f.indexer(
                RemoteTrackMetadataProbe { fileName, _ ->
                    probedNames += fileName
                    RemoteTrackMetadata(title = "Refreshed $fileName")
                },
                checkpointBatchSize = 2,
            ).indexStaged(f.source.id, "Album", false) {}
            assertTrue(refreshResult.complete && refreshResult.published)
            assertEquals(2, refreshResult.metadataReusedCount)
            assertEquals(2, refreshResult.metadataProbedCount)
            assertEquals(setOf("Track_03.flac", "Track_04.flac"), probedNames.toSet())
        } finally {
            releaseRemaining.countDown()
            process.cancel()
            f.db.close()
        }
    }

    @Test fun staleCheckpointCannotOverwriteNewerRequestOrRemovedScope() = runBlocking {
        for (action in listOf("replace", "remove")) {
            val f = MultiTrackFixture(trackCount = 4)
            val reachedCheckpointBoundary = CompletableDeferred<Unit>()
            val releaseOld = CountDownLatch(1)
            try {
                f.repo.upsertSource(f.source)
                val oldRequest = checkNotNull(f.repo.beginSmbFolderIndex(f.source.id))
                val oldProbe = RemoteTrackMetadataProbe { fileName, _ ->
                    if (fileName != "Track_01.flac") {
                        // Hold Track_02..04 so checkpoint #1 (batch=2) cannot commit until releaseOld fires.
                        reachedCheckpointBoundary.complete(Unit)
                        check(releaseOld.await(15, TimeUnit.SECONDS))
                    }
                    RemoteTrackMetadata(title = "Stale $fileName")
                }
                val oldIndexer = f.indexer(oldProbe, checkpointBatchSize = 2)
                val oldJob = async(Dispatchers.IO) {
                    runCatching { oldIndexer.index(oldRequest, "Album", false, onEntriesPublished = {}) }
                }
                try {
                    withTimeout(10_000) { reachedCheckpointBoundary.await() }
                    when (action) {
                        "replace" -> {
                            val newer = f.indexer(
                                RemoteTrackMetadataProbe { fileName, _ -> RemoteTrackMetadata(title = "Fresh $fileName") },
                                checkpointBatchSize = 2,
                            ).indexStaged(f.source.id, "Album", false) {}
                            assertTrue(newer.published)
                        }
                        "remove" -> {
                            val scopeId = f.repo.smbFolderScopes(f.source.id).single().id
                            assertTrue(f.repo.removeSmbFolderScope(f.source.id, scopeId))
                        }
                    }
                } finally {
                    releaseOld.countDown()
                }
                val oldOutcome = oldJob.await()
                assertTrue("Old request should fail or not publish for $action", oldOutcome.isFailure || oldOutcome.getOrNull()?.published == false)
                val finalRows = f.repo.tracksForSource(f.source.id)
                when (action) {
                    "replace" -> {
                        assertEquals(4, finalRows.size)
                        assertTrue(finalRows.all { it.title.startsWith("Fresh ") })
                    }
                    "remove" -> assertTrue(finalRows.isEmpty())
                }
            } finally {
                releaseOld.countDown()
                f.db.close()
            }
        }
    }

    @Test fun indexedSidecarLookupsMatchLinearOverloads() {
        val lyrics = listOf(
            RemoteLyricsSidecarCandidate("Song_A.LRC", "Album/Song_A.LRC", "rev1", 100),
            RemoteLyricsSidecarCandidate("song_a.ttml", "Album/song_a.ttml", "rev2", 200),
            RemoteLyricsSidecarCandidate("Song_B.lrc", "Album/Song_B.lrc", "rev3", 150),
        )
        val lyricsIndex = RemoteLyricsSidecarIndex.from(lyrics)
        for (name in listOf("Song_A.flac", "SONG_B.mp3", "Song_C.m4a")) {
            val expected = remoteTrackLyricsRevision(name, "Album/$name", "fileRev", 1024L, lyrics)
            val actual = remoteTrackLyricsRevision(name, "Album/$name", "fileRev", 1024L, lyricsIndex)
            assertEquals("Lyrics revision mismatch for $name", expected, actual)
        }

        val artworks = listOf(
            RemoteSidecarArtworkCandidate("Song_A.png", "Album/Song_A.png", "revP", 500),
            RemoteSidecarArtworkCandidate("song_a.JPG", "Album/song_a.JPG", "revJ", 400),
            RemoteSidecarArtworkCandidate("Song_B.webp", "Album/Song_B.webp", "revW", 300),
        )
        val artIndex = RemoteSidecarArtworkIndex.from(artworks)
        for (name in listOf("Song_A.flac", "SONG_B.mp3", "Song_C.m4a")) {
            assertEquals(
                selectRemoteTrackSidecarArtwork(name, artworks),
                artIndex.selectForTrack(name),
            )
        }
    }

    private class MultiTrackFixture(private val trackCount: Int) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MicaDatabase::class.java).allowMainThreadQueries().build()
        val repo = RemoteCatalogRepository(db)
        val source = RemoteSourceInstance(id = "staged-multi", type = RemoteSourceType.SMB, displayName = "Multi", endpoint = "smb://host/share", credentialRef = "credential")
        fun indexer(probe: RemoteTrackMetadataProbe, checkpointBatchSize: Int = SmbFolderLibraryIndexer.DEFAULT_PROBE_CHECKPOINT_BATCH_SIZE) =
            SmbFolderLibraryIndexer(
                repository = repo,
                credentials = SecureRemoteCredentialStore { RemoteCredentialSnapshot(it, 1, RemoteCredentialMaterial.Anonymous) },
                sessions = SmbSessionFactory { _, _ ->
                    object : SmbSessionHandle {
                        override fun list(serverPath: String) = (1..trackCount).map { i ->
                            SmbDirectoryEntry("Track_${i.toString().padStart(2, '0')}.flac", false, 1000L + i, "rev-$i")
                        }
                        override fun openFile(serverPath: String) = object : SmbRandomAccessFile {
                            override val length = 1000L
                            override fun read(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int) = -1
                            override fun close() = Unit
                        }
                        override fun close() = Unit
                    }
                },
                metadataProbe = probe,
                probeCheckpointBatchSize = checkpointBatchSize,
            )
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
