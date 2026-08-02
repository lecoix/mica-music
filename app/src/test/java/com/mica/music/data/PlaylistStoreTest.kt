package com.mica.music.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
    }

    @Test
    fun exportOmitsCustomCoverPathAndKeepsSongMetadata() {
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
    fun importResolvesChangedSongIdByMetadataAndPreservesCoverSong() {
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
    fun removingCoverSongClearsTheSongCoverReference() {
        val song = SongFixtures.song("cover-song")
        val store = PlaylistStore(context)
        val playlist = store.createPlaylist("Cover")
        store.addSongToPlaylist(playlist.id, song.id)
        store.setCoverSong(playlist.id, song.id)

        store.removeSongFromPlaylist(playlist.id, song.id)

        assertEquals(emptyList<String>(), store.playlistById(playlist.id)?.songIds)
        assertEquals(null, store.playlistById(playlist.id)?.coverSongId)
    }
}
