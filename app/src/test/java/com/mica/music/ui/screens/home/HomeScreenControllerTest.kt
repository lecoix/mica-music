package com.mica.music.ui.screens.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlaylistStore
import com.mica.music.testutil.SongFixtures
import com.mica.music.ui.components.SongMenuAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeScreenControllerTest {
    private lateinit var context: Context
    private lateinit var playlistStore: PlaylistStore
    private lateinit var library: MusicLibrary
    private lateinit var controller: HomeScreenController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        playlistStore = PlaylistStore(context)
        library = MusicLibrary(context)
        controller = HomeScreenController(library, playlistStore)
    }

    @Test
    fun openAndDismissActionMenu() {
        val song = SongFixtures.song("s1")
        var overlay = HomeOverlayState()

        overlay = controller.openActionMenu(overlay, song, playlistId = "pl_1")
        assertEquals(song, overlay.actionMenuSong)
        assertEquals("pl_1", overlay.actionMenuPlaylistId)

        overlay = controller.dismissActionMenu(overlay)
        assertNull(overlay.actionMenuSong)
        assertNull(overlay.actionMenuPlaylistId)
    }

    @Test
    fun deleteMenuActionSetsPendingDeleteSong() {
        val song = SongFixtures.song("s1")
        val overlay = controller.openActionMenu(HomeOverlayState(), song)

        val outcome = controller.handleSongMenuAction(
            context = context,
            overlay = overlay,
            action = SongMenuAction.Delete,
            song = song,
        )

        assertNull(outcome.overlay?.actionMenuSong)
        assertEquals(song, outcome.overlay?.pendingDeleteSong)
    }

    @Test
    fun addToPlaylistMenuActionOpensAddSheet() {
        val song = SongFixtures.song("s1")
        val overlay = controller.openActionMenu(HomeOverlayState(), song)

        val outcome = controller.handleSongMenuAction(
            context = context,
            overlay = overlay,
            action = SongMenuAction.AddToPlaylist,
            song = song,
        )

        assertEquals(listOf(song), outcome.overlay?.addToPlaylistSongs)
    }

    @Test
    fun songInfoMenuActionOpensDetail() {
        val song = SongFixtures.song("s1")
        val overlay = controller.openActionMenu(HomeOverlayState(), song)

        val outcome = controller.handleSongMenuAction(
            context = context,
            overlay = overlay,
            action = SongMenuAction.SongInfo,
            song = song,
        )

        assertEquals("s1", outcome.openSongDetailId)
        assertNull(outcome.overlay?.actionMenuSong)
    }

    @Test
    fun removeFromPlaylistRequiresPlaylistContext() {
        val song = SongFixtures.song("s1")
        val playlist = playlistStore.createPlaylist("Test")
        playlistStore.addSongToPlaylist(playlist.id, song.id)
        val overlay = controller.openActionMenu(HomeOverlayState(), song, playlist.id)

        val outcome = controller.handleSongMenuAction(
            context = context,
            overlay = overlay,
            action = SongMenuAction.RemoveFromPlaylist,
            song = song,
        )

        assertEquals("已从歌单移除", outcome.snackbarMessage)
        assertFalse(playlistStore.playlistById(playlist.id)!!.songIds.contains(song.id))
    }

    @Test
    fun deleteActivePlaylistNavigatesToSongs() {
        val playlist = playlistStore.createPlaylist("Mine")

        val outcome = controller.deletePlaylist(
            playlistId = playlist.id,
            section = HomeSection.Playlist,
            activePlaylistId = playlist.id,
        )

        assertEquals(HomeSection.Songs, outcome.section)
        assertNull(outcome.activePlaylistId)
        assertTrue(outcome.snackbarMessage.contains("Mine"))
        assertNull(playlistStore.playlistById(playlist.id))
    }

    @Test
    fun deleteInactivePlaylistKeepsNavigation() {
        val playlist = playlistStore.createPlaylist("Other")

        val outcome = controller.deletePlaylist(
            playlistId = playlist.id,
            section = HomeSection.Songs,
            activePlaylistId = null,
        )

        assertNull(outcome.section)
        assertNull(outcome.activePlaylistId)
        assertNull(playlistStore.playlistById(playlist.id))
    }

    @Test
    fun createPlaylistSuccess() {
        val outcome = controller.createPlaylist("  New List  ")

        assertEquals(HomeSection.Playlist, outcome.section)
        assertEquals("New List", playlistStore.playlistById(outcome.activePlaylistId!!)?.name)
        assertNull(outcome.snackbarMessage)
    }

    @Test
    fun createPlaylistEmptyNameFails() {
        val outcome = controller.createPlaylist("   ")

        assertNull(outcome.section)
        assertNull(outcome.activePlaylistId)
        assertEquals("歌单名不能为空", outcome.snackbarMessage)
    }
}
