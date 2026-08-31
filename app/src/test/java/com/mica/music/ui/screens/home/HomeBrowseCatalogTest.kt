package com.mica.music.ui.screens.home

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBrowseCatalogTest {
    @Test
    fun mergedBrowseSongsKeepsLocalFirstAndDeduplicatesStableIds() {
        val localSame = SongFixtures.song(id = "same", title = "Local Same")
        val localOnly = SongFixtures.song(id = "local", title = "Local")
        val remoteSame = SongFixtures.song(id = "same", title = "Remote Same")
        val remoteOnly = SongFixtures.song(id = "remote", title = "Remote")

        val merged = mergedBrowseSongs(
            localSongs = listOf(localSame, localOnly),
            remoteSongs = listOf(remoteSame, remoteOnly),
        )

        assertEquals(listOf("same", "local", "remote"), merged.map { it.id })
        assertSame(localSame, merged.first())
    }

    @Test
    fun mergedBrowseSongsReturnsLocalListDirectlyWhenRemoteCatalogIsEmpty() {
        val local = listOf(SongFixtures.song(id = "local"))
        assertSame(local, mergedBrowseSongs(local, emptyList()))
    }

    @Test
    fun remoteBrowseContentAllowsArtistAndAlbumRootsButNotFolders() {
        val remote = listOf(
            SongFixtures.song(id = "remote").copy(artist = "Remote Artist", album = "Remote Album"),
        )

        assertTrue(hasRemoteBrowseContent(HomeSection.Artists, BrowseDestination.Root, remote))
        assertTrue(hasRemoteBrowseContent(HomeSection.Albums, BrowseDestination.Root, remote))
        assertTrue(
            hasRemoteBrowseContent(
                HomeSection.Artists,
                BrowseDestination.Artist("Remote Artist"),
                remote,
            ),
        )
        assertTrue(
            hasRemoteBrowseContent(
                HomeSection.Albums,
                BrowseDestination.Album("Remote Album"),
                remote,
            ),
        )
        assertFalse(
            hasRemoteBrowseContent(
                HomeSection.Artists,
                BrowseDestination.Artist("Missing"),
                remote,
            ),
        )
        assertFalse(hasRemoteBrowseContent(HomeSection.Folders, BrowseDestination.Root, remote))
        assertFalse(hasRemoteBrowseContent(HomeSection.Artists, BrowseDestination.Root, emptyList()))
    }
}