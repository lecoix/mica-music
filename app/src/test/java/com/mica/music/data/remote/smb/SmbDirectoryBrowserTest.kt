package com.mica.music.data.remote.smb

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmbDirectoryBrowserTest {
    @Test fun tenThousandSongsBrowseOneDirectoryWithoutOpeningAnyFileOrPublishingCatalog() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MicaDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = RemoteCatalogRepository(db)
            val source = RemoteSourceInstance("smb", RemoteSourceType.SMB, "Router", "smb://router/share/root", "credential")
            repository.upsertSource(source)
            var visits = 0
            var closed = false
            val factory = SmbSessionFactory { _, login ->
                assertTrue(login.anonymous)
                object : SmbSessionHandle {
                    override fun list(serverPath: String): List<SmbDirectoryEntry> = error("Must use streaming enumeration")
                    override fun openFile(serverPath: String): SmbRandomAccessFile = error("Browsing must not open files")
                    override fun close() { closed = true }
                    override fun visit(serverPath: String, consume: (SmbDirectoryEntry) -> Boolean) {
                        assertEquals("root\\Album", serverPath)
                        visits++
                        assertTrue(consume(SmbDirectoryEntry("Child", true, 0)))
                        for (index in 10_000 downTo 1) {
                            assertTrue(consume(SmbDirectoryEntry("Song$index.flac", false, 1_000_000, "v$index")))
                            // Large word-timed sidecars for every song must remain unopened descriptors.
                            assertTrue(consume(SmbDirectoryEntry("Song$index.ttml", false, 500_000)))
                        }
                        assertTrue(consume(SmbDirectoryEntry("Song1.jpg", false, 500_000, "art1")))
                    }
                }
            }
            val browser = SmbDirectoryBrowser(repository,
                SecureRemoteCredentialStore { RemoteCredentialSnapshot(it, 1, RemoteCredentialMaterial.Anonymous) }, factory)
            val snapshot = browser.list(source.id, "Album")
            assertEquals(1, visits)
            assertTrue(closed)
            assertTrue(snapshot.complete)
            assertEquals(10_001, snapshot.entries.size)
            assertEquals("Child", snapshot.entries.first().name)
            assertEquals("Song2.flac", snapshot.entries[2].name)
            assertEquals("Song10000.flac", snapshot.entries.last().name)
            assertTrue(snapshot.entries[1].track!!.artworkOpaqueId.isNotBlank())
            assertTrue(snapshot.entries[1].track!!.lyricsRevision.isNotBlank())
            assertTrue(repository.tracksForSource(source.id).isEmpty())
            assertEquals(0L, repository.sourceStatus(source.id)!!.lastSyncAtMs)
        } finally { db.close() }
    }

    @Test fun oversizedDirectoryStopsEnumerationAndReportsPartial() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MicaDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = RemoteCatalogRepository(db)
            val source = RemoteSourceInstance("smb", RemoteSourceType.SMB, "Router", "smb://router/share", "credential")
            repo.upsertSource(source)
            var consumed = 0
            val factory = SmbSessionFactory { _, _ -> object : SmbSessionHandle {
                override fun list(serverPath: String): List<SmbDirectoryEntry> = error("No full list")
                override fun openFile(serverPath: String): SmbRandomAccessFile = error("No file reads")
                override fun close() = Unit
                override fun visit(serverPath: String, consume: (SmbDirectoryEntry) -> Boolean) {
                    while (consumed < 100_000) {
                        consumed++
                        if (!consume(SmbDirectoryEntry("$consumed.flac", false, 100))) break
                    }
                }
            } }
            val result = SmbDirectoryBrowser(repo, SecureRemoteCredentialStore {
                RemoteCredentialSnapshot(it, 1, RemoteCredentialMaterial.Anonymous)
            }, factory).list(source.id, "")
            assertFalse(result.complete)
            assertTrue(consumed <= SmbDirectoryBrowser.MAX_ENTRIES + 1)
            assertTrue(repo.tracksForSource(source.id).isEmpty())
        } finally { db.close() }
    }
}
