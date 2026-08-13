package com.mica.music.ui.screens.home

import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.FolderBrowseMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun saverRoundTripPreservesNavigationBrowseStackAndBrowsePrefs() {
        val original = HomeUiState(
            section = HomeSection.Albums,
            activePlaylistId = "pl_42",
            searchOpen = true,
            searchQuery = "hello",
            browseDestination = BrowseDestination.Album("Kyogen"),
            browseStack = listOf(
                BrowseStackFrame(
                    section = HomeSection.Artists,
                    browseDestination = BrowseDestination.Artist("Ado"),
                    searchOpen = false,
                    searchQuery = "",
                ),
                BrowseStackFrame(
                    section = HomeSection.Folders,
                    browseDestination = BrowseDestination.Folder(
                        depth = 2,
                        scopePathSegments = listOf("Music", "Rock"),
                    ),
                ),
            ),
            returnSection = HomeSection.Artists,
            folderVisibleDepth = 2,
            folderVisibleScope = listOf("Music", "Rock", "2024"),
            browseSort = HomeBrowseSortState(
                albumSortField = com.mica.music.data.AlbumBrowseSortField.YEAR,
                albumSortDirection = com.mica.music.data.SortDirection.DESC,
                albumGridColumns = 3,
                artistSortField = ArtistBrowseSortField.SONG_COUNT,
                artistSortDirection = com.mica.music.data.SortDirection.DESC,
                artistGridColumns = 4,
                folderBrowseMode = FolderBrowseMode.MUSIC_FOLDERS,
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
    fun saverRestoresLegacyV2WithoutBrowseStack() {
        val restored = restoreHomeUiState(
            listOf(
                "v2",
                HomeSection.Albums.name,
                "",
                "false",
                "",
                HomeSection.Songs.name,
                "0",
                "",
                com.mica.music.data.AlbumBrowseSortField.TITLE.storageValue,
                com.mica.music.data.SortDirection.ASC.storageValue,
                "2",
                ArtistBrowseSortField.TITLE.storageValue,
                com.mica.music.data.SortDirection.ASC.storageValue,
                "2",
                "album",
                "Legacy Album",
            ),
        )

        assertEquals(BrowseDestination.Album("Legacy Album"), restored?.browseDestination)
        assertEquals(emptyList<BrowseStackFrame>(), restored?.browseStack)
        assertEquals(FolderBrowseMode.HIERARCHY, restored?.browseSort?.folderBrowseMode)
    }

    @Test
    fun saverRestoresLegacyV3WithHierarchyFolderMode() {
        val restored = restoreHomeUiState(
            listOf(
                "v3",
                HomeSection.Folders.name,
                "",
                "false",
                "",
                HomeSection.Songs.name,
                "0",
                "",
                com.mica.music.data.AlbumBrowseSortField.TITLE.storageValue,
                com.mica.music.data.SortDirection.ASC.storageValue,
                "2",
                ArtistBrowseSortField.TITLE.storageValue,
                com.mica.music.data.SortDirection.ASC.storageValue,
                "2",
                "2",
                "folder",
                "0",
                "",
                "0",
            ),
        )

        assertEquals(HomeSection.Folders, restored?.section)
        assertEquals(BrowseDestination.Folder(depth = 0), restored?.browseDestination)
        assertEquals(FolderBrowseMode.HIERARCHY, restored?.browseSort?.folderBrowseMode)
    }

    @Test
    fun saverRestoresLegacyArtistSortState() {
        val restored = restoreHomeUiState(
            listOf(
                "v1",
                HomeSection.Artists.name,
                "",
                "false",
                "",
                HomeSection.Songs.name,
                "0",
                "",
                com.mica.music.data.AlbumBrowseSortField.TITLE.storageValue,
                com.mica.music.data.SortDirection.ASC.storageValue,
                "2",
                com.mica.music.data.SortDirection.DESC.storageValue,
                "3",
                "root",
                "",
            ),
        )

        assertEquals(ArtistBrowseSortField.TITLE, restored?.browseSort?.artistSortField)
        assertEquals(com.mica.music.data.SortDirection.DESC, restored?.browseSort?.artistSortDirection)
        assertEquals(3, restored?.browseSort?.artistGridColumns)
        assertEquals(emptyList<BrowseStackFrame>(), restored?.browseStack)
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
            browseStack = listOf(
                BrowseStackFrame(section = HomeSection.Songs, searchOpen = true, searchQuery = "x"),
            ),
            returnSection = HomeSection.Albums,
            activePlaylistId = "pl_1",
        )

        val updated = base.withNavigationSnapshot(snapshot)

        assertEquals(HomeSection.Artists, updated.section)
        assertEquals(false, updated.searchOpen)
        assertEquals("", updated.searchQuery)
        assertEquals(BrowseDestination.Artist("A"), updated.browseDestination)
        assertEquals(snapshot.browseStack, updated.browseStack)
        assertEquals(HomeSection.Albums, updated.returnSection)
        assertEquals("pl_1", updated.activePlaylistId)
    }

    @Test
    fun coldStartLocationRestoresOnlySupportedRootPagesAndValidPlaylist() {
        assertEquals(HomeSection.Artists to null, restoreHomeLocation(HomeSection.Artists.name, null))
        assertEquals(HomeSection.Albums to null, restoreHomeLocation(HomeSection.Albums.name, "ignored"))
        assertEquals(HomeSection.Playlist to "pl_1", restoreHomeLocation(HomeSection.Playlist.name, "pl_1"))
        assertEquals(HomeSection.Playlist to null, restoreHomeLocation(HomeSection.Playlist.name, ""))
        assertEquals(HomeSection.Folders to null, restoreHomeLocation(HomeSection.Folders.name, null))
        assertEquals(HomeSection.Songs to null, restoreHomeLocation("unknown", null))
    }
}
