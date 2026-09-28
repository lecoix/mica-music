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
import com.mica.music.data.remote.SecureRemoteCredentialStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmbFolderLibraryIndexerTest {
    @Test
    fun lyricSidecarRevisionRefreshesDescriptorWithoutOpeningLyricPayload() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repository = RemoteCatalogRepository(db)
            val source = RemoteSourceInstance(
                id = "smb-lyrics-revision",
                type = RemoteSourceType.SMB,
                displayName = "Router",
                endpoint = "smb://router/share/root",
                credentialRef = "credential",
            )
            repository.upsertSource(source)
            var lyricRevision = "lyrics-v1"
            var payloadOpens = 0
            val indexer = SmbFolderLibraryIndexer(
                repository = repository,
                credentials = SecureRemoteCredentialStore {
                    RemoteCredentialSnapshot(it, 1, RemoteCredentialMaterial.Anonymous)
                },
                sessions = SmbSessionFactory { _, _ ->
                    object : SmbSessionHandle {
                        override fun list(serverPath: String): List<SmbDirectoryEntry> = error("No list materialization")
                        override fun close() = Unit
                        override fun openFile(serverPath: String): SmbRandomAccessFile {
                            payloadOpens++
                            error("Lyrics revision discovery must not open payloads")
                        }
                        override fun visit(serverPath: String, consume: (SmbDirectoryEntry) -> Boolean) {
                            assertTrue(consume(SmbDirectoryEntry("Song.flac", false, 1000, "audio-v1")))
                            assertTrue(consume(SmbDirectoryEntry("Song.lrc", false, 120, lyricRevision)))
                        }
                    }
                },
            )

            assertTrue(indexer.index(source.id, "Album", false).published)
            val first = repository.tracksForSource(source.id).single().lyricsRevision
            lyricRevision = "lyrics-v2"
            assertTrue(indexer.index(source.id, "Album", false).published)
            val second = repository.tracksForSource(source.id).single().lyricsRevision

            assertTrue(first.isNotBlank())
            assertTrue(second.isNotBlank())
            assertTrue(first != second)
            assertEquals(0, payloadOpens)
        } finally {
            db.close()
        }
    }

    @Test
    fun explicitScopeReadsMetadataOnlyInsideScopeAndReusesUnchangedTracks() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repository = RemoteCatalogRepository(db)
            val source = RemoteSourceInstance(
                id = "smb-metadata",
                type = RemoteSourceType.SMB,
                displayName = "Router",
                endpoint = "smb://router/share/root",
                credentialRef = "credential",
            )
            repository.upsertSource(source)

            val opened = mutableListOf<String>()
            val visited = mutableListOf<String>()
            val sessions = SmbSessionFactory { _, _ ->
                object : SmbSessionHandle {
                    override fun list(serverPath: String): List<SmbDirectoryEntry> = error("No list materialization")
                    override fun close() = Unit
                    override fun visit(serverPath: String, consume: (SmbDirectoryEntry) -> Boolean) {
                        visited += serverPath
                        when (serverPath) {
                            "root\\Album" -> {
                                assertTrue(consume(SmbDirectoryEntry("Disc 2", true, 0)))
                                assertTrue(consume(SmbDirectoryEntry("01.flac", false, 1_000, "v1")))
                            }
                            "root\\Album\\Disc 2" ->
                                assertTrue(consume(SmbDirectoryEntry("02.flac", false, 2_000, "v2")))
                            else -> error("Unexpected directory $serverPath")
                        }
                    }
                    override fun openFile(serverPath: String): SmbRandomAccessFile {
                        opened += serverPath
                        return object : SmbRandomAccessFile {
                            override val length: Long = 4_096
                            override fun read(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int = -1
                            override fun close() = Unit
                        }
                    }
                }
            }
            val probe = RemoteTrackMetadataProbe { fileName, _ ->
                RemoteTrackMetadata(
                    title = "Tagged ${fileName.substringBeforeLast('.')}",
                    artist = "Scoped Artist",
                    album = "Scoped Album",
                    durationSec = 123,
                )
            }
            val indexer = SmbFolderLibraryIndexer(
                repository = repository,
                credentials = SecureRemoteCredentialStore {
                    RemoteCredentialSnapshot(it, 1, RemoteCredentialMaterial.Anonymous)
                },
                sessions = sessions,
                metadataProbe = probe,
            )

            val nonRecursive = indexer.index(source.id, "Album", includeSubdirectories = false)
            assertTrue(nonRecursive.published)
            assertEquals(1, nonRecursive.metadataProbedCount)
            assertEquals(0, nonRecursive.metadataReusedCount)
            assertEquals(listOf("root\\Album"), visited)
            assertEquals(listOf("root\\Album\\01.flac"), opened)
            assertEquals("Tagged 01", repository.tracksForSource(source.id).single().title)

            visited.clear()
            val recursive = indexer.index(source.id, "Album", includeSubdirectories = true)
            assertTrue(recursive.published)
            assertEquals(1, recursive.metadataProbedCount)
            assertEquals(1, recursive.metadataReusedCount)
            assertEquals(listOf("root\\Album", "root\\Album\\Disc 2"), visited)
            assertEquals(
                listOf("root\\Album\\01.flac", "root\\Album\\Disc 2\\02.flac"),
                opened,
            )
            assertEquals(
                mapOf("Album/01.flac" to "Tagged 01", "Album/Disc 2/02.flac" to "Tagged 02"),
                repository.tracksForSource(source.id).associate { it.ref.opaqueTrackId to it.title },
            )

            repository.upsertSource(source.copy(endpoint = "smb://router-2/share/root"))
            val afterSourceEdit = indexer.index(source.id, "Album", includeSubdirectories = false)
            assertTrue(afterSourceEdit.published)
            assertEquals(1, afterSourceEdit.metadataProbedCount)
            assertEquals(0, afterSourceEdit.metadataReusedCount)
            assertEquals("root\\Album\\01.flac", opened.last())
        } finally {
            db.close()
        }
    }

    @Test
    fun incompleteDiscoveryNeverOpensMetadataPayloads() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repository = RemoteCatalogRepository(db)
            val source = RemoteSourceInstance(
                id = "smb-incomplete-metadata",
                type = RemoteSourceType.SMB,
                displayName = "Router",
                endpoint = "smb://router/share/root",
                credentialRef = "credential",
            )
            repository.upsertSource(source)
            var payloadOpens = 0
            val indexer = SmbFolderLibraryIndexer(
                repository = repository,
                credentials = SecureRemoteCredentialStore {
                    RemoteCredentialSnapshot(it, 1, RemoteCredentialMaterial.Anonymous)
                },
                sessions = SmbSessionFactory { _, _ ->
                    object : SmbSessionHandle {
                        override fun list(serverPath: String): List<SmbDirectoryEntry> = error("No list materialization")
                        override fun close() = Unit
                        override fun openFile(serverPath: String): SmbRandomAccessFile {
                            payloadOpens++
                            error("Incomplete discovery must not probe metadata")
                        }
                        override fun visit(serverPath: String, consume: (SmbDirectoryEntry) -> Boolean) {
                            assertTrue(consume(SmbDirectoryEntry("01.flac", false, 1_000, "v1")))
                            for (index in 0..SmbFolderLibraryIndexer.MAX_VISITED_ENTRIES) {
                                if (!consume(SmbDirectoryEntry("ignored-$index.txt", false, 1))) break
                            }
                        }
                    }
                },
                metadataProbe = RemoteTrackMetadataProbe { _, _ -> RemoteTrackMetadata(title = "must-not-run") },
            )

            val result = indexer.index(source.id, "Album", includeSubdirectories = false)

            assertEquals(false, result.complete)
            assertEquals(false, result.published)
            assertEquals(0, result.metadataProbedCount)
            assertEquals(0, payloadOpens)
            assertTrue(repository.tracksForSource(source.id).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun partialRefreshNeverDeletesPreviouslyPublishedMembership() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repository = RemoteCatalogRepository(db)
            val source = RemoteSourceInstance(
                id = "smb-partial",
                type = RemoteSourceType.SMB,
                displayName = "Router",
                endpoint = "smb://router/share/root",
                credentialRef = "credential",
            )
            repository.upsertSource(source)
            var attempt = 0
            val sessions = SmbSessionFactory { _, _ ->
                attempt++
                object : SmbSessionHandle {
                    override fun list(serverPath: String): List<SmbDirectoryEntry> = error("No list materialization")
                    override fun openFile(serverPath: String): SmbRandomAccessFile = error("No payload reads")
                    override fun close() = throw java.io.IOException("server rejects graceful session close")
                    override fun visit(serverPath: String, consume: (SmbDirectoryEntry) -> Boolean) {
                        assertEquals("root\\Album", serverPath)
                        assertTrue(consume(SmbDirectoryEntry("A.flac", false, 1_000, "a")))
                        if (attempt == 1) {
                            assertTrue(consume(SmbDirectoryEntry("B.flac", false, 2_000, "b")))
                        } else {
                            for (index in 0..SmbFolderLibraryIndexer.MAX_VISITED_ENTRIES) {
                                if (!consume(SmbDirectoryEntry("ignored-$index.txt", false, 1))) break
                            }
                        }
                    }
                }
            }
            val indexer = SmbFolderLibraryIndexer(
                repository,
                SecureRemoteCredentialStore {
                    RemoteCredentialSnapshot(it, 1, RemoteCredentialMaterial.Anonymous)
                },
                sessions,
            )

            assertTrue(indexer.index(source.id, "Album", includeSubdirectories = false).published)
            val partial = indexer.index(source.id, "Album", includeSubdirectories = false)

            assertEquals(false, partial.complete)
            assertEquals(false, partial.published)
            assertEquals(
                setOf("Album/A.flac", "Album/B.flac"),
                repository.tracksForSource(source.id).map { it.ref.opaqueTrackId }.toSet(),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun overlappingScopesKeepSharedTrackUntilLastMembershipIsRemoved() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repository = RemoteCatalogRepository(db)
            val source = RemoteSourceInstance(
                id = "smb-overlap",
                type = RemoteSourceType.SMB,
                displayName = "Router",
                endpoint = "smb://router/share/root",
                credentialRef = "credential",
            )
            repository.upsertSource(source)
            var parentIncludesChild = true
            val sessions = SmbSessionFactory { _, _ ->
                object : SmbSessionHandle {
                    override fun list(serverPath: String): List<SmbDirectoryEntry> = error("No list materialization")
                    override fun openFile(serverPath: String): SmbRandomAccessFile = error("No payload reads")
                    override fun close() = Unit

                    override fun visit(serverPath: String, consume: (SmbDirectoryEntry) -> Boolean) {
                        when (serverPath) {
                            "root\\Album" -> {
                                if (parentIncludesChild) assertTrue(consume(SmbDirectoryEntry("Disc 2", true, 0)))
                                assertTrue(consume(SmbDirectoryEntry("A.flac", false, 1_000, "a")))
                            }
                            "root\\Album\\Disc 2" ->
                                assertTrue(consume(SmbDirectoryEntry("B.flac", false, 2_000, "b")))
                            else -> error("Unexpected directory $serverPath")
                        }
                    }
                }
            }
            val indexer = SmbFolderLibraryIndexer(
                repository,
                SecureRemoteCredentialStore {
                    RemoteCredentialSnapshot(it, 1, RemoteCredentialMaterial.Anonymous)
                },
                sessions,
            )

            assertTrue(indexer.index(source.id, "Album", includeSubdirectories = true).published)
            assertTrue(indexer.index(source.id, "Album/Disc 2", includeSubdirectories = false).published)
            parentIncludesChild = false
            assertTrue(indexer.index(source.id, "Album", includeSubdirectories = true).published)

            assertEquals(
                setOf("Album/A.flac", "Album/Disc 2/B.flac"),
                repository.tracksForSource(source.id).map { it.ref.opaqueTrackId }.toSet(),
            )

            val b = repository.tracksForSource(source.id).single { it.ref.opaqueTrackId.endsWith("B.flac") }
            val token = repository.beginOperation(source.id)!!.token
            assertTrue(repository.registerSelectedTracks(token, listOf(b)))

            val childScope = repository.smbFolderScopes(source.id)
                .single { it.relativeDirectory == "Album/Disc 2" }
            assertTrue(repository.removeSmbFolderScope(source.id, childScope.id))

            assertEquals(
                listOf("Album/A.flac"),
                repository.tracksForSource(source.id).map { it.ref.opaqueTrackId },
            )
            assertEquals(b.ref, repository.find(listOf(b.ref)).keys.single())
        } finally {
            db.close()
        }
    }

    @Test
    fun nonRecursiveFolderIndexPublishesOnlyCurrentFolderWithoutOpeningPayloads() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MicaDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repository = RemoteCatalogRepository(db)
            val source = RemoteSourceInstance(
                id = "smb",
                type = RemoteSourceType.SMB,
                displayName = "Router",
                endpoint = "smb://router/share/root",
                credentialRef = "credential",
            )
            repository.upsertSource(source)

            val visited = mutableListOf<String>()
            var payloadOpens = 0
            val sessions = SmbSessionFactory { _, _ ->
                object : SmbSessionHandle {
                    override fun list(serverPath: String): List<SmbDirectoryEntry> =
                        error("Folder indexing must use streaming enumeration")

                    override fun openFile(serverPath: String): SmbRandomAccessFile {
                        payloadOpens++
                        error("Lightweight membership discovery must not open payloads")
                    }

                    override fun visit(serverPath: String, consume: (SmbDirectoryEntry) -> Boolean) {
                        visited += serverPath
                        assertEquals("root\\Album", serverPath)
                        assertTrue(consume(SmbDirectoryEntry("Disc 2", true, 0)))
                        assertTrue(consume(SmbDirectoryEntry("01.flac", false, 1_000, "v1")))
                        assertTrue(consume(SmbDirectoryEntry("02.mp3", false, 2_000, "v2")))
                        assertTrue(consume(SmbDirectoryEntry("cover.jpg", false, 20_000, "art")))
                    }

                    override fun close() = Unit
                }
            }
            val indexer = SmbFolderLibraryIndexer(
                repository = repository,
                credentials = SecureRemoteCredentialStore {
                    RemoteCredentialSnapshot(it, 1, RemoteCredentialMaterial.Anonymous)
                },
                sessions = sessions,
            )

            val result = indexer.index(source.id, "Album", includeSubdirectories = false)

            assertTrue(result.complete)
            assertEquals(2, result.trackCount)
            assertEquals(listOf("root\\Album"), visited)
            assertEquals(0, payloadOpens)
            assertEquals(
                listOf("Album/01.flac", "Album/02.mp3"),
                repository.tracksForSource(source.id).map { it.ref.opaqueTrackId },
            )
            val scopes = repository.smbFolderScopes(source.id)
            assertEquals(1, scopes.size)
            assertEquals("Album", scopes.single().relativeDirectory)
            assertEquals(false, scopes.single().includeSubdirectories)
            assertEquals(0L, repository.sourceStatus(source.id)!!.lastSyncAtMs)
        } finally {
            db.close()
        }
    }
}
