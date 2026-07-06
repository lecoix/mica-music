package com.mica.music.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun saverRoundTripPreservesNavigationAndBrowsePrefs() {
        val original = HomeUiState(
            section = HomeSection.Albums,
            activePlaylistId = "pl_42",
            searchOpen = true,
            searchQuery = "hello",
            browseDestination = BrowseDestination.Folder(
                depth = 2,
                scopePathSegments = listOf("Music", "Rock", "2024"),
            ),
            returnSection = HomeSection.Artists,
            folderVisibleDepth = 2,
            folderVisibleScope = listOf("Music", "Rock", "2024"),
            browseSort = HomeBrowseSortState(
                albumSortField = com.mica.music.data.AlbumBrowseSortField.YEAR,
                albumSortDirection = com.mica.music.data.SortDirection.DESC,
                albumGridColumns = 3,
                artistSortDirection = com.mica.music.data.SortDirection.DESC,
                artistGridColumns = 4,
            ),
        )

        val restored = restoreHomeUiState(saveHomeUiState(original))

        assertEquals(original, restored)
    }

    @Test
    fun saverDefaultsWhenVersionMissing() {
        val restored = restoreHomeUiState(
            listOf("legacy", HomeSection.Songs.name),
        )

        assertEquals(HomeUiState(), restored)
    }

    @Test
    fun withNavigationSnapshotUpdatesPersistedNavigationFields() {
        val base = HomeUiState(
            section = HomeSection.Songs,
            searchOpen = true,
            searchQuery = "x",
        )
        val snapshot = HomeNavigationSnapshot(
            section = HomeSection.Artists,
            searchOpen = false,
            searchQuery = "",
            browseDestination = BrowseDestination.Artist("A"),
            returnSection = HomeSection.Albums,
            activePlaylistId = "pl_1",
        )

        val updated = base.withNavigationSnapshot(snapshot)

        assertEquals(HomeSection.Artists, updated.section)
        assertEquals(false, updated.searchOpen)
        assertEquals("", updated.searchQuery)
        assertEquals(BrowseDestination.Artist("A"), updated.browseDestination)
        assertEquals(HomeSection.Albums, updated.returnSection)
        assertEquals("pl_1", updated.activePlaylistId)
    }
}
