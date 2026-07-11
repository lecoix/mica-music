package com.mica.music.ui.screens.home

import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.BrowseListInfoVisibility
import com.mica.music.data.MusicLibrary
import org.junit.Assert.assertEquals
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
}
