package com.mica.music.data.preferences

import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.ArtistSeparator
import com.mica.music.data.ArtistSplitConfig
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
        assertEquals(ArtistBrowseSortField.TITLE, LibraryBrowseSettings.artistBrowseSortField(context))
        assertEquals(SortDirection.ASC, LibraryBrowseSettings.artistBrowseSortDirection(context))
        assertEquals(1, LibraryBrowseSettings.artistBrowseGridColumns(context))

        LibraryBrowseSettings.setAlbumBrowseSort(context, AlbumBrowseSortField.ARTIST, SortDirection.DESC)
        LibraryBrowseSettings.setAlbumBrowseGridColumns(context, 3)
        LibraryBrowseSettings.setArtistBrowseSort(context, ArtistBrowseSortField.SONG_COUNT, SortDirection.DESC)
        LibraryBrowseSettings.setArtistBrowseGridColumns(context, 4)

        assertEquals(AlbumBrowseSortField.ARTIST, LibraryBrowseSettings.albumBrowseSortField(context))
        assertEquals(SortDirection.DESC, LibraryBrowseSettings.albumBrowseSortDirection(context))
        assertEquals(3, LibraryBrowseSettings.albumBrowseGridColumns(context))
        assertEquals(ArtistBrowseSortField.SONG_COUNT, LibraryBrowseSettings.artistBrowseSortField(context))
        assertEquals(SortDirection.DESC, LibraryBrowseSettings.artistBrowseSortDirection(context))
        assertEquals(4, LibraryBrowseSettings.artistBrowseGridColumns(context))

        LibraryBrowseSettings.setAlbumBrowseGridColumns(context, 99)
        LibraryBrowseSettings.setArtistBrowseGridColumns(context, 0)

        assertEquals(4, LibraryBrowseSettings.albumBrowseGridColumns(context))
        assertEquals(1, LibraryBrowseSettings.artistBrowseGridColumns(context))
    }

    @Test
    fun customSongOrderRoundTrip() {
        assertEquals(emptyList<String>(), LibraryBrowseSettings.customSongOrderIds(context))
        assertEquals(false, LibraryBrowseSettings.customSongOrderLocked(context))

        LibraryBrowseSettings.setCustomSongOrderIds(context, listOf("c", "a", "b"))
        LibraryBrowseSettings.setCustomSongOrderLocked(context, true)

        assertEquals(listOf("c", "a", "b"), LibraryBrowseSettings.customSongOrderIds(context))
        assertEquals(true, LibraryBrowseSettings.customSongOrderLocked(context))
    }

    @Test
    fun artistSplitConfigRoundTrip() {
        assertEquals(ArtistSeparator.defaults, LibraryBrowseSettings.artistSplitConfig(context).enabledSeparators)

        val config = ArtistSplitConfig(
            enabledSeparators = setOf(ArtistSeparator.COMMA, ArtistSeparator.FEAT),
            whitelist = listOf("AC/DC", "Earth, Wind & Fire"),
        )
        LibraryBrowseSettings.setArtistSplitConfig(context, config)

        assertEquals(config, LibraryBrowseSettings.artistSplitConfig(context))
    }
}
