package com.mica.music.data.preferences

import com.mica.music.data.EqCustomProfileStore
import com.mica.music.data.EqSelection
import com.mica.music.data.PlaybackSession
import com.mica.music.data.PlaybackSessionStore
import com.mica.music.data.PlaylistStore
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class PreferencesStorageRobolectricTest {

    private val context = PreferencesTestFixtures.context()

    @Before
    fun clearPreferences() {
        PreferencesTestFixtures.clearAll(context)
    }

    @Test
    fun corruptProfilesAndSessionValuesDoNotEscape() {
        context.getSharedPreferences("mica_eq_profiles", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("profiles_json", "{bad")
            .putString("selection", "system:not-a-number")
            .commit()
        assertTrue(EqCustomProfileStore.listProfiles(context).isEmpty())
        assertEquals(EqSelection.System(0), EqCustomProfileStore.getSelection(context))

        PlaybackSessionStore.save(context, PlaybackSession("song", -5), sync = true)
        assertEquals(PlaybackSession("song", 0), PlaybackSessionStore.load(context))
        PlaybackSessionStore.save(context, PlaybackSession("", 100), sync = true)
        assertNull(PlaybackSessionStore.load(context))

        context.getSharedPreferences("mica_playlists", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("playlists_json", "[{\"id\":")
            .commit()
        assertTrue(PlaylistStore(context).playlists.isEmpty())
    }

    @Test
    fun playlistBatchAppendKeepsDisplayedOrderAndSwitchesToCustomSort() {
        val store = PlaylistStore(context)
        val playlist = store.createPlaylist("Test")
        listOf("a", "b", "c").forEach { store.addSongToPlaylist(playlist.id, it) }
        store.updateSort(playlist.id, SongSortField.TITLE, SortDirection.DESC)

        assertTrue(
            store.appendSongsAsCustomOrder(
                playlistId = playlist.id,
                currentDisplayedSongIds = listOf("c", "b", "a"),
                appendedSongIds = listOf("b", "d", "d", "e"),
            ),
        )

        val updated = store.playlistById(playlist.id)!!
        assertEquals(listOf("c", "b", "a", "d", "e"), updated.songIds)
        assertEquals(SongSortField.CUSTOM, updated.sortField)
        assertEquals(SortDirection.ASC, updated.sortDirection)
    }
}
