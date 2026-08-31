package com.mica.music.ui.screens.home

import com.mica.music.data.Song
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNavigationTest {
    private fun snapshot(
        section: HomeSection = HomeSection.Songs,
        searchOpen: Boolean = false,
        searchQuery: String = "",
        browseDestination: BrowseDestination = BrowseDestination.Root,
        browseStack: List<BrowseStackFrame> = emptyList(),
        returnSection: HomeSection = HomeSection.Songs,
        activePlaylistId: String? = null,
        songMultiSelectActive: Boolean = false,
    ) = HomeNavigationSnapshot(
        section = section,
        searchOpen = searchOpen,
        searchQuery = searchQuery,
        browseDestination = browseDestination,
        browseStack = browseStack,
        returnSection = returnSection,
        activePlaylistId = activePlaylistId,
        songMultiSelectActive = songMultiSelectActive,
    )

    @Test
    fun canNavigateBackWhenSearchOpen() {
        assertTrue(canNavigateBack(snapshot(searchOpen = true)))
    }

    @Test
    fun canNavigateBackFromArtistDetail() {
        assertTrue(
            canNavigateBack(
                snapshot(
                    section = HomeSection.Artists,
                    browseDestination = BrowseDestination.Artist("Artist"),
                ),
            ),
        )
    }

    @Test
    fun navigateBackClosesSearchAndClearsQuery() {
        val result = navigateBack(
            snapshot(searchOpen = true).copy(searchQuery = "hello"),
        )

        assertFalse(result.snapshot.searchOpen)
        assertEquals("", result.snapshot.searchQuery)
        assertTrue(result.hideKeyboard)
    }

    @Test
    fun navigateBackFromFolderDepthStepsUp() {
        val result = navigateBack(
            snapshot(
                section = HomeSection.Folders,
                browseDestination = BrowseDestination.Folder(
                    depth = 2,
                    scopePathSegments = listOf("Music", "Rock", "2024"),
                ),
            ),
        )

        assertEquals(
            BrowseDestination.Folder(depth = 1, scopePathSegments = listOf("Music")),
            result.snapshot.browseDestination,
        )
    }

    @Test
    fun navigateBackFromFolderRootReturnsToBrowseRoot() {
        val result = navigateBack(
            snapshot(
                section = HomeSection.Folders,
                browseDestination = BrowseDestination.Folder(depth = 0),
            ),
        )

        assertEquals(BrowseDestination.Root, result.snapshot.browseDestination)
    }

    @Test
    fun navigateBackFromMusicFolderDetailReturnsToBrowseRoot() {
        val result = navigateBack(
            snapshot(
                section = HomeSection.Folders,
                browseDestination = BrowseDestination.Folder(
                    depth = 0,
                    scopePathSegments = listOf("Music", "Rock", "Queen"),
                ),
            ),
        )

        assertEquals(BrowseDestination.Root, result.snapshot.browseDestination)
    }

    @Test
    fun navigateBackFromRecentRestoresReturnSection() {
        val result = navigateBack(
            snapshot(
                section = HomeSection.Recent,
                returnSection = HomeSection.Albums,
            ),
        )

        assertEquals(HomeSection.Albums, result.snapshot.section)
        assertEquals(null, result.snapshot.activePlaylistId)
    }

    @Test
    fun consumeNavigationIntentResetsSearchClearsStackAndAppliesTarget() {
        val updated = consumeNavigationIntent(
            snapshot(
                searchOpen = true,
                searchQuery = "x",
                activePlaylistId = "p1",
                browseStack = listOf(
                    BrowseStackFrame(
                        section = HomeSection.Artists,
                        browseDestination = BrowseDestination.Artist("Old"),
                    ),
                ),
            ),
            HomeNavigationIntent(
                section = HomeSection.Albums,
                browseDestination = BrowseDestination.Album("Album A"),
            ),
        )

        assertFalse(updated.searchOpen)
        assertEquals("", updated.searchQuery)
        assertEquals(null, updated.activePlaylistId)
        assertEquals(emptyList<BrowseStackFrame>(), updated.browseStack)
        assertEquals(HomeSection.Albums, updated.section)
        assertEquals(BrowseDestination.Album("Album A"), updated.browseDestination)
    }

    @Test
    fun resolveHomePaneKeyUsesSearchWhenSearchOpen() {
        val key = resolveHomePaneKey(
            searchOpen = true,
            section = HomeSection.Songs,
            activePlaylistId = null,
            browseDestination = BrowseDestination.Root,
        )

        assertEquals(HomePaneKey.Search, key)
    }

    @Test
    fun resolveHomePaneKeyUsesDedicatedRemotePane() {
        val key = resolveHomePaneKey(
            searchOpen = false,
            section = HomeSection.Remote,
            activePlaylistId = null,
            browseDestination = BrowseDestination.Root,
        )

        assertEquals(HomePaneKey.Remote, key)
        assertTrue(homePaneDepth(HomePaneKey.Folders) < homePaneDepth(key))
        assertTrue(homePaneDepth(key) < homePaneDepth(HomePaneKey.Browse(HomeSection.Recent, BrowseDestination.Root)))
    }

    @Test
    fun remoteRootTitleIsExplicit() {
        assertEquals(
            "远程曲库",
            resolveTopBarTitle(
                appName = "Mica",
                section = HomeSection.Remote,
                playlistName = null,
                searchOpen = false,
                browseDestination = BrowseDestination.Root,
            ),
        )
    }

    @Test
    fun homePaneDepthOrdersBrowseSections() {
        val artistBrowse = HomePaneKey.Browse(
            section = HomeSection.Artists,
            destination = BrowseDestination.Artist("A"),
        )
        val albumBrowse = HomePaneKey.Browse(
            section = HomeSection.Albums,
            destination = BrowseDestination.Album("B"),
        )

        assertTrue(homePaneDepth(artistBrowse) < homePaneDepth(albumBrowse))
    }

    @Test
    fun playlistDetailIsDeeperThanPlaylistOverview() {
        assertTrue(
            homePaneDepth(HomePaneKey.Playlist("p1")) >
                homePaneDepth(HomePaneKey.PlaylistOverview),
        )
    }

    @Test
    fun overviewPlaylistDetailPushesOverviewAndBackRestoresIt() {
        val overview = snapshot(section = HomeSection.Playlist)
        val detail = navigateToPlaylistFromOverview(overview, "p1")

        assertEquals(HomeSection.Playlist, detail.section)
        assertEquals("p1", detail.activePlaylistId)
        assertEquals(
            listOf(BrowseStackFrame(section = HomeSection.Playlist)),
            detail.browseStack,
        )
        assertTrue(canNavigateBack(detail))

        val back = navigateBack(detail).snapshot
        assertEquals(HomeSection.Playlist, back.section)
        assertEquals(null, back.activePlaylistId)
        assertEquals(emptyList<BrowseStackFrame>(), back.browseStack)
    }

    @Test
    fun flatPlaylistDetailDoesNotCreateBackDestination() {
        val detail = snapshot(
            section = HomeSection.Playlist,
            activePlaylistId = "p1",
        )

        assertFalse(canNavigateBack(detail))
        assertEquals(detail, navigateBack(detail).snapshot)
    }

    @Test
    fun pushBrowseDestinationKeepsFolderOffStack() {
        val updated = pushBrowseDestination(
            snapshot(
                section = HomeSection.Folders,
                browseDestination = BrowseDestination.Folder(depth = 0),
            ),
            BrowseDestination.Folder(depth = 1, scopePathSegments = listOf("Music")),
            section = HomeSection.Folders,
        )

        assertEquals(emptyList<BrowseStackFrame>(), updated.browseStack)
        assertEquals(
            BrowseDestination.Folder(depth = 1, scopePathSegments = listOf("Music")),
            updated.browseDestination,
        )
    }

    @Test
    fun artistToAlbumPushesArtistAndBackRestoresIt() {
        val artist = snapshot(
            section = HomeSection.Artists,
            browseDestination = BrowseDestination.Artist("Ado"),
        )
        val album = navigateToAlbum(artist, "Kyogen")

        assertEquals(HomeSection.Albums, album.section)
        assertEquals(BrowseDestination.Album("Kyogen"), album.browseDestination)
        assertEquals(
            listOf(
                BrowseStackFrame(
                    section = HomeSection.Artists,
                    browseDestination = BrowseDestination.Artist("Ado"),
                ),
            ),
            album.browseStack,
        )

        val back = navigateBack(album).snapshot
        assertEquals(HomeSection.Artists, back.section)
        assertEquals(BrowseDestination.Artist("Ado"), back.browseDestination)
        assertEquals(emptyList<BrowseStackFrame>(), back.browseStack)
    }

    @Test
    fun songMenuArtistPushRestoresSearchOnBack() {
        val fromSearch = snapshot(
            section = HomeSection.Songs,
            searchOpen = true,
            searchQuery = "ado",
        )
        val artist = navigateToArtist(fromSearch, "Ado")

        assertEquals(HomeSection.Artists, artist.section)
        assertFalse(artist.searchOpen)
        assertEquals("", artist.searchQuery)
        assertEquals(
            listOf(
                BrowseStackFrame(
                    section = HomeSection.Songs,
                    searchOpen = true,
                    searchQuery = "ado",
                ),
            ),
            artist.browseStack,
        )

        val back = navigateBack(artist)
        assertTrue(back.snapshot.searchOpen)
        assertEquals("ado", back.snapshot.searchQuery)
        assertEquals(HomeSection.Songs, back.snapshot.section)
        assertFalse(back.hideKeyboard)
    }

    @Test
    fun multiSelectDataSourceMatchesCurrentLibrarySection() {
        val local = listOf(SongFixtures.song(id = "local", title = "Local"))
        val remote = listOf(SongFixtures.song(id = "remote", title = "Remote"))

        assertEquals(local, songsForMultiSelect(HomeSection.Songs, local, remote))
        assertEquals(remote, songsForMultiSelect(HomeSection.Remote, local, remote))
        assertEquals(emptyList<Song>(), songsForMultiSelect(HomeSection.Artists, local, remote))
    }

    @Test
    fun locateCurrentSongTargetsItsOwningLibrarySection() {
        val local = listOf(SongFixtures.song(id = "local", title = "Local"))
        val remote = listOf(SongFixtures.song(id = "remote", title = "Remote"))

        assertEquals(HomeSection.Songs, locateSectionForSong("local", local, remote))
        assertEquals(HomeSection.Remote, locateSectionForSong("remote", local, remote))
        assertEquals(null, locateSectionForSong("missing", local, remote))
        assertEquals(
            HomeSection.Songs,
            locateSectionForSong(
                "same",
                listOf(SongFixtures.song(id = "same")),
                listOf(SongFixtures.song(id = "same")),
            ),
        )
    }

    @Test
    fun multiSelectIsScopedToItsOwningSectionAndSearchClosesIt() {
        assertFalse(shouldClearSongMultiSelect(HomeSection.Songs, HomeSection.Songs, searchOpen = false))
        assertFalse(shouldClearSongMultiSelect(HomeSection.Remote, HomeSection.Remote, searchOpen = false))
        assertTrue(shouldClearSongMultiSelect(HomeSection.Remote, HomeSection.Songs, searchOpen = false))
        assertTrue(shouldClearSongMultiSelect(HomeSection.Songs, HomeSection.Remote, searchOpen = false))
        assertTrue(shouldClearSongMultiSelect(HomeSection.Remote, HomeSection.Remote, searchOpen = true))
        assertFalse(shouldClearSongMultiSelect(null, HomeSection.Artists, searchOpen = false))
    }
    @Test
    fun rootToArtistPushesRootThenBackReturnsRoot() {
        val root = snapshot(section = HomeSection.Artists)
        val artist = pushBrowseDestination(root, BrowseDestination.Artist("Ado"))
        assertEquals(
            listOf(BrowseStackFrame(section = HomeSection.Artists)),
            artist.browseStack,
        )

        val back = navigateBack(artist).snapshot
        assertEquals(BrowseDestination.Root, back.browseDestination)
        assertEquals(emptyList<BrowseStackFrame>(), back.browseStack)
    }
}
