package com.mica.music.data.preferences

import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.SortDirection
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class LibraryBrowsePreferencesRobolectricTest {

    private val context = PreferencesTestFixtures.context()

    @Before
    fun clearPreferences() {
        PreferencesTestFixtures.clearMicaSettings(context)
    }

    @Test
    fun browseDisplaySettingsRoundTrip() {
        assertEquals(AlbumBrowseSortField.TITLE, LibraryBrowseSettings.albumBrowseSortField(context))
        assertEquals(SortDirection.ASC, LibraryBrowseSettings.albumBrowseSortDirection(context))
        assertEquals(1, LibraryBrowseSettings.albumBrowseGridColumns(context))
        assertEquals(SortDirection.ASC, LibraryBrowseSettings.artistBrowseSortDirection(context))
        assertEquals(1, LibraryBrowseSettings.artistBrowseGridColumns(context))

        LibraryBrowseSettings.setAlbumBrowseSort(context, AlbumBrowseSortField.ARTIST, SortDirection.DESC)
        LibraryBrowseSettings.setAlbumBrowseGridColumns(context, 3)
        LibraryBrowseSettings.setArtistBrowseSortDirection(context, SortDirection.DESC)
        LibraryBrowseSettings.setArtistBrowseGridColumns(context, 4)

        assertEquals(AlbumBrowseSortField.ARTIST, LibraryBrowseSettings.albumBrowseSortField(context))
        assertEquals(SortDirection.DESC, LibraryBrowseSettings.albumBrowseSortDirection(context))
        assertEquals(3, LibraryBrowseSettings.albumBrowseGridColumns(context))
        assertEquals(SortDirection.DESC, LibraryBrowseSettings.artistBrowseSortDirection(context))
        assertEquals(4, LibraryBrowseSettings.artistBrowseGridColumns(context))

        LibraryBrowseSettings.setAlbumBrowseGridColumns(context, 99)
        LibraryBrowseSettings.setArtistBrowseGridColumns(context, 0)

        assertEquals(4, LibraryBrowseSettings.albumBrowseGridColumns(context))
        assertEquals(1, LibraryBrowseSettings.artistBrowseGridColumns(context))
    }
}
