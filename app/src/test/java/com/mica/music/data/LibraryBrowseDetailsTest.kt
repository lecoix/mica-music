package com.mica.music.data

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBrowseDetailsTest {
    @Test
    fun albumDetailSortsSongsByDiscTrackThenTitleAndKeepsUnknownDiscLast() {
        val songs = listOf(
            song("unknown", title = "A", disc = 0, track = 1),
            song("disc2-track1", title = "B", disc = 2, track = 1),
            song("disc1-track2", title = "C", disc = 1, track = 2),
            song("disc1-track1", title = "D", disc = 1, track = 1),
        )

        val detail = LibraryBrowseDetails.albumDetail(songs)

        assertEquals(
            listOf("disc1-track1", "disc1-track2", "disc2-track1", "unknown"),
            detail.orderedSongs.map { it.id },
        )
        assertEquals(listOf(1, 2, null), detail.discSections.map { it.discNumber })
    }

    @Test
    fun albumDetailUsesFirstNonBlankCopyright() {
        val detail = LibraryBrowseDetails.albumDetail(
            listOf(
                song("blank").copy(copyright = " "),
                song("copyright").copy(copyright = "Test copyright"),
            ),
        )

        assertEquals("Test copyright", detail.copyright)
    }

    @Test
    fun artistAlbumSectionsGroupByAlbumAndSortAlbumsWithKnownYearsFirst() {
        val sections = LibraryBrowseDetails.artistAlbumSections(
            listOf(
                song("unknown").copy(album = "", year = 0, trackNumber = 1),
                song("new-2").copy(album = "New", year = 2024, trackNumber = 2),
                song("old").copy(album = "Old", year = 1990, trackNumber = 1),
                song("new-1").copy(album = "New", year = 2024, trackNumber = 1),
            ),
        )

        assertEquals(listOf("New", "Old", "未知专辑"), sections.map { it.title })
        assertEquals(listOf("new-1", "new-2"), sections.first().songs.map { it.id })
    }

    private fun song(
        id: String,
        title: String = id,
        disc: Int = 0,
        track: Int = 0,
    ): Song = SongFixtures.song(id = id, title = title).copy(
        discNumber = disc,
        trackNumber = track,
    )
}
