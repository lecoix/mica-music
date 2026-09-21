package com.mica.music.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.*
import com.mica.music.media.TrustedRemoteMediaItemProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmbPlaylistFlowTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        MicaDatabase.resetForTests()
        context.deleteDatabase(MicaDatabase.DATABASE_NAME)
        context.getSharedPreferences("mica_playlists", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { MicaDatabase.resetForTests() }

    @Test fun tenThousandSelectionsPersistWithoutCatalogAndRestoreMediaItemsOffline() = runTest {
        val store = PlaylistStore(context)
        store.awaitReady()
        val playlist = store.createPlaylist("SMB")
        val repo = RemoteCatalogRepository(context)
        val source = source()
        repo.upsertSource(source)
        val token = repo.beginOperation(source.id)!!.token
        val tracks = (1..10_000).map { track("$it.flac") }
        assertTrue(store.addRemoteSongsToPlaylist(playlist.id, tracks, token, repo, store.revision))
        assertTrue(repo.tracksForEnabledSources().isEmpty())
        val cold = PlaylistStore(context)
        cold.awaitReady()
        assertEquals(tracks.map { it.mediaId }, cold.playlistById(playlist.id)!!.songIds)
        val restored = RemoteCatalogRepository(context).find(tracks.map { it.ref })
        assertEquals(10_000, restored.size)
        assertEquals(10_000, TrustedRemoteMediaItemProvider(repo).resolve(tracks.map { it.mediaId }).size)
        assertTrue(repo.deleteSource(source.id))
        assertEquals("1.flac", repo.find(listOf(tracks.first().ref))[tracks.first().ref]!!.title)
        val songsById = restored.values.associate { it.mediaId to it.toPlaybackSong() }
        assertEquals(10_000, cold.songsForPlaylist(playlist.id) { id ->
            songsById[id]
        }.size)
    }

    @Test fun oldSelectionCannotRestoreDeletedPlaylistOrWriteOrphanDescriptions() = runTest {
        val store = PlaylistStore(context)
        store.awaitReady()
        val playlist = store.createPlaylist("Old")
        val repo = RemoteCatalogRepository(context)
        repo.upsertSource(source())
        val token = repo.beginOperation("smb")!!.token
        val revision = store.revision
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val late = async {
            waiting.complete(Unit); release.await()
            store.addRemoteSongsToPlaylist(playlist.id, listOf(track("late.flac")), token, repo, revision)
        }
        waiting.await()
        assertTrue(store.deletePlaylist(playlist.id))
        release.complete(Unit)
        assertFalse(late.await())
        val cold = PlaylistStore(context)
        cold.awaitReady()
        assertNull(cold.playlistById(playlist.id))
        assertTrue(repo.find(listOf(track("late.flac").ref)).isEmpty())
    }

    @Test fun failedPlaylistWriteRollsBackDescriptionsAsWell() = runTest {
        val store = PlaylistStore(context)
        store.awaitReady()
        val playlist = store.createPlaylist("Keep")
        val repo = RemoteCatalogRepository(context)
        repo.upsertSource(source())
        val token = repo.beginOperation("smb")!!.token
        val db = MicaDatabase.get(context)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_smb_add BEFORE INSERT ON playlist_songs BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try {
            assertTrue(runCatching {
                store.addRemoteSongsToPlaylist(playlist.id, listOf(track("fail.flac")), token, repo, store.revision)
            }.isFailure)
            assertTrue(repo.find(listOf(track("fail.flac").ref)).isEmpty())
            assertTrue(store.playlistById(playlist.id)!!.songIds.isEmpty())
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_smb_add") }
    }

    private fun source() = RemoteSourceInstance("smb", RemoteSourceType.SMB, "Router", "smb://router/share", "credential")
    private fun track(name: String) = RemoteTrackSummary(RemoteTrackRef("smb", name), name, fileName = name, contentRevision = "v1")
}
