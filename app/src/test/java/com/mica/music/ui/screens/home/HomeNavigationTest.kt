package com.mica.music.ui.screens.home

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
        returnSection: HomeSection = HomeSection.Songs,
        activePlaylistId: String? = null,
        songMultiSelectActive: Boolean = false,
    ) = HomeNavigationSnapshot(
        section = section,
        searchOpen = searchOpen,
        searchQuery = searchQuery,
        browseDestination = browseDestination,
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
    fun consumeNavigationIntentResetsSearchAndAppliesTarget() {
        val updated = consumeNavigationIntent(
            snapshot(searchOpen = true, searchQuery = "x", activePlaylistId = "p1"),
            HomeNavigationIntent(
                section = HomeSection.Albums,
                browseDestination = BrowseDestination.Album("Album A"),
            ),
        )

        assertFalse(updated.searchOpen)
        assertEquals("", updated.searchQuery)
        assertEquals(null, updated.activePlaylistId)
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
}
