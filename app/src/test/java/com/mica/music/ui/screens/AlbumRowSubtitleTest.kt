package com.mica.music.ui.screens

import com.mica.music.data.BrowseGroup
import com.mica.music.data.BrowseListInfoVisibility
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumRowSubtitleTest {
    private val album = BrowseGroup(
        title = "Album",
        subtitle = "Artist",
        artist = "Artist",
        year = 2024,
        releaseDate = "2024-06-07",
        songCount = 12,
    )

    @Test
    fun `default album subtitle keeps current artist date and count`() {
        assertEquals(
            "Artist · 2024-06-07 · 12 首",
            albumRowSubtitle(album, BrowseListInfoVisibility()),
        )
    }

    @Test
    fun `album subtitle fields can be selected independently`() {
        assertEquals(
            "Artist · 12 首",
            albumRowSubtitle(
                album,
                BrowseListInfoVisibility(showAlbumSubtitleReleaseDate = false),
            ),
        )
        assertEquals(
            "2024-06-07",
            albumRowSubtitle(
                album,
                BrowseListInfoVisibility(
                    showAlbumSubtitleArtist = false,
                    showAlbumSubtitleSongCount = false,
                ),
            ),
        )
        assertEquals(
            "",
            albumRowSubtitle(
                album,
                BrowseListInfoVisibility(
                    showAlbumSubtitleArtist = false,
                    showAlbumSubtitleReleaseDate = false,
                    showAlbumSubtitleSongCount = false,
                ),
            ),
        )
    }
}
