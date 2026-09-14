package com.mica.music.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemotePlaybackUriCodec
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mica_playlists", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        MicaDatabase.resetForTests()
        runBlocking { MicaDatabase.get(context).playlistDao().deleteAll() }
    }

    @Test
    fun exportOmitsCustomCoverPathAndKeepsSongMetadata() = runTest {
        val store = PlaylistStore(context)
        val song = SongFixtures.song("exported")
        val playlist = store.createPlaylist("Export")
        store.addSongToPlaylist(playlist.id, song.id)
        store.setCustomCoverPath(playlist.id, "/private/playlist-cover.jpg")

        val json = store.exportPlaylistJson(playlist.id) { id -> song.takeIf { it.id == id } }

        requireNotNull(json)
        assertTrue(json.contains("\"format\": \"mica-playlist\""))
        assertTrue(json.contains("\"title\": \"exported\""))
        assertFalse(json.contains("customCoverPath"))
    }

    @Test
    fun importResolvesChangedSongIdByMetadataAndPreservesCoverSong() = runTest {
        val sourceSong = SongFixtures.song("old-id", title = "Stable title")
        val librarySong = SongFixtures.song("new-id", title = "Stable title")
        val store = PlaylistStore(context)
        val source = store.createPlaylist("Portable")
        store.addSongToPlaylist(source.id, sourceSong.id)
        store.setCoverSong(source.id, sourceSong.id)
        val json = requireNotNull(store.exportPlaylistJson(source.id) { id ->
            sourceSong.takeIf { it.id == id }
        })

        val imported = store.importPlaylistJson(json, listOf(librarySong))

        assertEquals(listOf(librarySong.id), imported.playlist.songIds)
        assertEquals(librarySong.id, imported.playlist.coverSongId)
        assertEquals(1, imported.importedSongCount)
        assertEquals(0, imported.skippedSongCount)
        assertEquals("Portable (2)", imported.playlist.name)
    }

    @Test
    fun remoteStableIdentitySurvivesPlaylistJsonRoundTrip() = runTest {
        val remoteId = RemoteMediaIdCodec.encode(RemoteTrackRef("source-1", "track-9"))
        val remoteSong = SongFixtures.song(remoteId, title = "Remote Nine").copy(
            source = SongSource.REMOTE,
            mediaUri = RemotePlaybackUriCodec.encode(remoteId),
            filePath = "Album/Remote Nine.flac",
            fileName = "Remote Nine.flac",
        )
        val store = PlaylistStore(context)
        val source = store.createPlaylist("Remote portable")
        store.addSongToPlaylist(source.id, remoteSong.id)
        store.setCoverSong(source.id, remoteSong.id)

        val json = requireNotNull(store.exportPlaylistJson(source.id) { id ->
            remoteSong.takeIf { it.id == id }
        })
        val imported = store.importPlaylistJson(json, listOf(remoteSong))

        assertEquals(listOf(remoteId), imported.playlist.songIds)
        assertEquals(remoteId, imported.playlist.coverSongId)
        assertEquals(1, imported.importedSongCount)
        assertEquals(0, imported.skippedSongCount)
        val exportedMediaUri = JSONObject(json)
            .getJSONArray("songs")
            .getJSONObject(0)
            .getString("mediaUri")
        assertEquals(RemotePlaybackUriCodec.encode(remoteId), exportedMediaUri)
    }

    @Test
    fun setCoverSongRejectsSongOutsidePlaylist() = runTest {
        val memberSong = SongFixtures.song("member")
        val outsideSong = SongFixtures.song("outside")
        val store = PlaylistStore(context)
        val playlist = store.createPlaylist("Cover scope")
        store.addSongToPlaylist(playlist.id, memberSong.id)

        assertFalse(store.setCoverSong(playlist.id, outsideSong.id))
        assertEquals(null, store.playlistById(playlist.id)?.coverSongId)
        assertTrue(store.setCoverSong(playlist.id, memberSong.id))
        assertEquals(memberSong.id, store.playlistById(playlist.id)?.coverSongId)
    }

    @Test
    fun removingCoverSongClearsTheSongCoverReference() = runTest {
        val song = SongFixtures.song("cover-song")
        val store = PlaylistStore(context)
        val playlist = store.createPlaylist("Cover")
        store.addSongToPlaylist(playlist.id, song.id)
        store.setCoverSong(playlist.id, song.id)

        store.removeSongFromPlaylist(playlist.id, song.id)

        assertEquals(emptyList<String>(), store.playlistById(playlist.id)?.songIds)
        assertEquals(null, store.playlistById(playlist.id)?.coverSongId)
    }

    @Test
    fun legacyJsonMigratesToRoomWithoutDeletingRollbackSource() = runTest {
        val raw = """
            [{
              "id":"legacy",
              "name":"Legacy",
              "songs":["b","a"],
              "sortField":"custom",
              "sortDirection":"asc",
              "coverSongId":"b",
              "customCoverPath":"/covers/legacy.jpg"
            }]
        """.trimIndent()
        val preferences = context.getSharedPreferences("mica_playlists", Context.MODE_PRIVATE)
        preferences.edit().putString("playlists_json", raw).commit()

        val migratedStore = PlaylistStore(context)
        migratedStore.awaitReady()
        val migrated = migratedStore.playlistById("legacy")

        assertNotNull(migrated)
        assertEquals(listOf("b", "a"), migrated?.songIds)
        assertEquals("b", migrated?.coverSongId)
        assertEquals("/covers/legacy.jpg", migrated?.customCoverPath)
        assertTrue(preferences.getBoolean("room_migration_complete_v1", false))
        assertEquals(raw, preferences.getString("playlists_json", null))

        val coldStore = PlaylistStore(context)
        coldStore.awaitReady()
        val coldReload = coldStore.playlistById("legacy")
        assertEquals(migrated, coldReload)
    }

    @Test
    fun corruptLegacyJsonDoesNotEraseExistingRoomPlaylistsOrMarkMigrationComplete() = runTest {
        val seeded = PlaylistStore(context).createPlaylist("Room survives")
        val preferences = context.getSharedPreferences("mica_playlists", Context.MODE_PRIVATE)
        preferences.edit()
            .remove("room_migration_complete_v1")
            .putString("playlists_json", "[{\"id\":")
            .commit()

        val reloaded = PlaylistStore(context)
        reloaded.awaitReady()

        assertEquals(seeded, reloaded.playlistById(seeded.id))
        assertFalse(preferences.getBoolean("room_migration_complete_v1", false))
        assertEquals("[{\"id\":", preferences.getString("playlists_json", null))
    }

    @Test
    fun tenThousandOrderedMembersSurviveColdRoomRoundTrip() = runTest {
        val store = PlaylistStore(context)
        val playlist = store.createPlaylist("Large")
        val ids = List(10_000) { index -> "song-$index" }

        assertTrue(store.addSongsToPlaylist(playlist.id, ids))

        val restoredStore = PlaylistStore(context)
        restoredStore.awaitReady()
        val restored = restoredStore.playlistById(playlist.id)
        assertEquals(10_000, restored?.songIds?.size)
        assertEquals("song-0", restored?.songIds?.first())
        assertEquals("song-9999", restored?.songIds?.last())
    }

    @Test
    fun staleReloadCannotOverwriteNewerRoomAndMemoryState() = runTest {
        val store = PlaylistStore(context)
        store.createPlaylist("Before reload")
        val atPublicationBoundary = CompletableDeferred<Unit>()
        val releaseStaleReload = CompletableDeferred<Unit>()

        val staleReload = async(Dispatchers.Default) {
            store.reloadFromStorage {
                atPublicationBoundary.complete(Unit)
                releaseStaleReload.await()
            }
        }
        atPublicationBoundary.await()
        val newest = store.createPlaylist("After reload")
        releaseStaleReload.complete(Unit)
        staleReload.await()

        assertEquals(newest, store.playlistById(newest.id))
        val coldStore = PlaylistStore(context)
        coldStore.awaitReady()
        assertEquals(newest, coldStore.playlistById(newest.id))
    }

    @Test
    fun granularMutationsSurviveColdRoomRoundTrip() = runTest {
        val store = PlaylistStore(context)
        val playlist = store.createPlaylist("Original")
        store.addSongsToPlaylist(playlist.id, listOf("a", "b", "c"))
        store.moveSongInPlaylist(playlist.id, 2, 0)
        store.removeSongFromPlaylist(playlist.id, "b")
        store.renamePlaylist(playlist.id, "Renamed")
        store.updateSort(playlist.id, SongSortField.TITLE, SortDirection.DESC)
        store.setCustomCoverPath(playlist.id, "/covers/custom.jpg")

        val restoredStore = PlaylistStore(context)
        restoredStore.awaitReady()
        val restored = restoredStore.playlistById(playlist.id)

        assertEquals("Renamed", restored?.name)
        assertEquals(listOf("c", "a"), restored?.songIds)
        assertEquals(SongSortField.TITLE, restored?.sortField)
        assertEquals(SortDirection.DESC, restored?.sortDirection)
        assertEquals("/covers/custom.jpg", restored?.customCoverPath)
    }
}
