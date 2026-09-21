package com.mica.music.ui.screens.home

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.MicaApp
import com.mica.music.data.remote.smb.SmbFolderLibraryIndexer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Focused physical-device gate for SMB phase 2 folder-scoped library membership. */
@RunWith(AndroidJUnit4::class)
class SmbFolderLibraryRealDeviceContractTest {
    @Test
    fun addRefreshRemoveFolderScopeOverRealSmb() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MicaApp>()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the isolated SMB QA package", app.packageName == "com.mica.music.smbqa")
        assumeTrue("Explicit device gate required", args.getString("smbGate") == "true")

        val endpoint = args.getString("smbEndpoint") ?: "smb://127.0.0.1:1445/music"
        val source = app.remoteSourceManager.createSmb(
            displayName = "SMB phase2 gate",
            endpoint = endpoint,
            username = args.getString("smbUsername").orEmpty(),
            password = args.getString("smbPassword").orEmpty(),
        )
        val repo = app.remoteCatalogRepository
        val indexer = SmbFolderLibraryIndexer(repo, app.remoteCredentialStore)

        val first = indexer.index(
            sourceId = source.id,
            directory = "Album",
            includeSubdirectories = false,
        )
        assertTrue(first.complete)
        assertTrue(first.published)
        assertEquals(2, first.trackCount)

        val firstTracks = repo.tracksForSource(source.id)
        assertEquals(
            setOf("Album/tone1.wav", "Album/tone2.flac"),
            firstTracks.map { it.ref.opaqueTrackId }.toSet(),
        )
        val scopes = repo.smbFolderScopes(source.id)
        assertEquals(1, scopes.size)
        assertEquals("Album", scopes.single().relativeDirectory)
        assertFalse(scopes.single().includeSubdirectories)
        assertEquals(0L, repo.sourceStatus(source.id)!!.lastSyncAtMs)

        val refreshed = indexer.index(
            sourceId = source.id,
            directory = "Album",
            includeSubdirectories = false,
        )
        assertTrue(refreshed.complete)
        assertTrue(refreshed.published)
        assertEquals(2, refreshed.trackCount)
        assertEquals(2, repo.tracksForSource(source.id).size)

        assertTrue(repo.removeSmbFolderScope(source.id, repo.smbFolderScopes(source.id).single().id))
        assertTrue(repo.smbFolderScopes(source.id).isEmpty())
        assertTrue(repo.tracksForSource(source.id).isEmpty())
        assertEquals(0L, repo.sourceStatus(source.id)!!.lastSyncAtMs)
    }
}
