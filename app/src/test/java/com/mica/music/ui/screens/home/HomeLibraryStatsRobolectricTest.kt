package com.mica.music.ui.screens.home

import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.BrowseListInfoVisibility
import com.mica.music.data.MusicLibrary
import com.mica.music.data.FolderBrowseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeLibraryStatsRobolectricTest {
    @Test
    fun artistAndAlbumRootInfoLinesUseIndependentVisibility() {
        val library = MusicLibrary(ApplicationProvider.getApplicationContext())
        val visibility = BrowseListInfoVisibility(
            showArtistCount = false,
            showArtistSortOrder = false,
            showArtistGridColumns = false,
            showArtistLastScanTime = false,
            showArtistCustomText = true,
            artistCustomText = "艺术家自定义",
            showAlbumCount = false,
            showAlbumSortOrder = false,
            showAlbumGridColumns = false,
            showAlbumLastScanTime = false,
            showAlbumCustomText = true,
            albumCustomText = "专辑自定义",
        )

        val artist = resolveLibraryStatsBarModel(
            section = HomeSection.Artists,
            browseDestination = BrowseDestination.Root,
            library = library,
            activePlaylistId = null,
            playlistSongCount = 0,
            playlistSortField = null,
            playlistSortDirection = null,
            browseListInfoVisibility = visibility,
        )
        val album = resolveLibraryStatsBarModel(
            section = HomeSection.Albums,
            browseDestination = BrowseDestination.Root,
            library = library,
            activePlaylistId = null,
            playlistSongCount = 0,
            playlistSortField = null,
            playlistSortDirection = null,
            browseListInfoVisibility = visibility,
        )

        assertEquals(listOf("艺术家自定义"), artist?.segments)
        assertEquals(listOf("专辑自定义"), album?.segments)
    }

    @Test
    fun folderRootShowsPersistedDisplayModeActionAndMatchingCountLabel() {
        val library = MusicLibrary(ApplicationProvider.getApplicationContext())

        val hierarchy = resolveLibraryStatsBarModel(
            section = HomeSection.Folders,
            browseDestination = BrowseDestination.Folder(depth = 0),
            library = library,
            activePlaylistId = null,
            playlistSongCount = 0,
            playlistSortField = null,
            playlistSortDirection = null,
            folderBrowseMode = FolderBrowseMode.HIERARCHY,
        )
        val flat = resolveLibraryStatsBarModel(
            section = HomeSection.Folders,
            browseDestination = BrowseDestination.Folder(depth = 0),
            library = library,
            activePlaylistId = null,
            playlistSongCount = 0,
            playlistSortField = null,
            playlistSortDirection = null,
            folderBrowseMode = FolderBrowseMode.MUSIC_FOLDERS,
        )

        assertEquals(listOf("0 个文件夹", "层级浏览"), hierarchy?.segments)
        assertEquals(true, hierarchy?.showFolderModeAction)
        assertEquals(listOf("0 个文件夹", "扁平浏览"), flat?.segments)
        assertEquals(true, flat?.showFolderModeAction)
    }

    @Test
    fun recentSectionUsesUnifiedPresentationCountWhenProvided() {
        val library = MusicLibrary(ApplicationProvider.getApplicationContext())

        val recent = resolveLibraryStatsBarModel(
            section = HomeSection.Recent,
            browseDestination = BrowseDestination.Root,
            library = library,
            recentSongCount = 7,
            activePlaylistId = null,
            playlistSongCount = 0,
            playlistSortField = null,
            playlistSortDirection = null,
        )

        assertEquals(listOf("7 首", "按最近播放"), recent?.segments)
    }

    @Test
    fun remoteSectionShowsOnlyRemoteCountAndMultiSelectAction() {
        val library = MusicLibrary(ApplicationProvider.getApplicationContext())

        val remote = resolveLibraryStatsBarModel(
            section = HomeSection.Remote,
            browseDestination = BrowseDestination.Root,
            library = library,
            remoteSongCount = 264,
            activePlaylistId = null,
            playlistSongCount = 0,
            playlistSortField = null,
            playlistSortDirection = null,
        )

        assertEquals(listOf("264 首"), remote?.segments)
        assertTrue(remote?.showMultiSelectAction == true)
        assertFalse(remote?.showSortAction == true)
        assertFalse(remote?.showFolderModeAction == true)
        assertFalse(remote?.showRescanAction == true)
        assertFalse(remote?.showDeletePlaylistAction == true)
    }

    @Test
    fun musicFolderDetailUsesDirectSongStatsNotHierarchyGroups() {
        val library = MusicLibrary(ApplicationProvider.getApplicationContext())

        val detail = resolveLibraryStatsBarModel(
            section = HomeSection.Folders,
            browseDestination = BrowseDestination.Folder(
                depth = 0,
                scopePathSegments = listOf("Music", "Rock"),
            ),
            library = library,
            activePlaylistId = null,
            playlistSongCount = 0,
            playlistSortField = null,
            playlistSortDirection = null,
            folderBrowseMode = FolderBrowseMode.MUSIC_FOLDERS,
        )

        assertEquals(listOf("Music / Rock"), detail?.segments)
        assertEquals(false, detail?.showFolderModeAction)
        assertEquals(false, detail?.showSortAction)
        assertEquals(true, detail?.showRescanAction)
    }
}
