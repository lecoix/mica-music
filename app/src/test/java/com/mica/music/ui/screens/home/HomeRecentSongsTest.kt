package com.mica.music.ui.screens.home

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecentSongsTest {
    @Test
    fun combinesLocalAndRemoteSongsByLastPlayedTime() {
        val local = SongFixtures.song("local").copy(lastPlayedAtMs = 100L)
        val remote = SongFixtures.song("remote").copy(lastPlayedAtMs = 300L)
        val unplayed = SongFixtures.song("unplayed")

        val result = recentSongsForPresentation(
            localSongs = listOf(local, unplayed),
            remoteSongs = listOf(remote),
        )

        assertEquals(listOf("remote", "local"), result.map { it.id })
    }

    @Test
    fun duplicateIdsPreferFirstLocalProjection() {
        val local = SongFixtures.song("same").copy(title = "local", lastPlayedAtMs = 200L)
        val remote = SongFixtures.song("same").copy(title = "remote", lastPlayedAtMs = 300L)

        val result = recentSongsForPresentation(
            localSongs = listOf(local),
            remoteSongs = listOf(remote),
        )

        assertEquals(1, result.size)
        assertEquals("local", result.single().title)
    }

    @Test
    fun appliesPresentationLimitAfterSorting() {
        val songs = (0 until 510).map { index ->
            SongFixtures.song("song-$index").copy(lastPlayedAtMs = index.toLong() + 1L)
        }

        val result = recentSongsForPresentation(
            localSongs = songs,
            remoteSongs = emptyList(),
        )

        assertEquals(RECENT_SONG_PRESENTATION_LIMIT, result.size)
        assertEquals("song-509", result.first().id)
        assertTrue(result.none { it.id == "song-0" })
    }

    @Test
    fun zeroLimitReturnsEmptyList() {
        val song = SongFixtures.song("song").copy(lastPlayedAtMs = 1L)
        assertTrue(recentSongsForPresentation(listOf(song), emptyList(), limit = 0).isEmpty())
    }
}
