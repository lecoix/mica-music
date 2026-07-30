package com.mica.music.data

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class SongSorterTest {

    @Test
    fun dateSortHandlesFullDatesYearOnlyAndUnknownInBothDirections() {
        val songs = listOf(
            SongFixtures.song(id = "year-2024").copy(year = 2024, releaseDate = ""),
            SongFixtures.song(id = "late-2024").copy(year = 2024, releaseDate = "2024-08-16"),
            SongFixtures.song(id = "early-2024").copy(year = 2024, releaseDate = "2024-01-05"),
            SongFixtures.song(id = "year-2023").copy(year = 2023, releaseDate = ""),
            SongFixtures.song(id = "date-2023").copy(year = 2023, releaseDate = "2023-12-20"),
            SongFixtures.song(id = "unknown").copy(year = 0, releaseDate = ""),
        )

        assertEquals(
            listOf("date-2023", "year-2023", "early-2024", "late-2024", "year-2024", "unknown"),
            SongSorter.sort(songs, SongSortField.YEAR, SortDirection.ASC).map { it.id },
        )
        assertEquals(
            listOf("late-2024", "early-2024", "year-2024", "date-2023", "year-2023", "unknown"),
            SongSorter.sort(songs, SongSortField.YEAR, SortDirection.DESC).map { it.id },
        )
    }

    @Test
    fun titleSortMixesChineseByPinyinInitial() {
        val songs = listOf(
            SongFixtures.song(id = "adu", title = "阿杜"),
            SongFixtures.song(id = "beatles", title = "Beatles"),
            SongFixtures.song(id = "zoo", title = "Zoo"),
        )

        assertEquals(
            listOf("adu", "beatles", "zoo"),
            SongSorter.sort(songs, SongSortField.TITLE, SortDirection.ASC).map { it.id },
        )
    }
    @Test
    fun textSortKeepsSymbolAndJapaneseInitialsInHashSection() {
        val songs = listOf(
            SongFixtures.song(id = "hash").copy(artist = "+\u03b1/\u3042\u308b\u3075\u3041\u304d\u3085\u3093"),
            SongFixtures.song(id = "able").copy(artist = "Able"),
            SongFixtures.song(id = "zoo").copy(artist = "Zoo"),
        )

        assertEquals(
            listOf("able", "zoo", "hash"),
            SongSorter.sort(songs, SongSortField.ARTIST, SortDirection.ASC).map { it.id },
        )
        assertEquals(
            listOf("hash", "zoo", "able"),
            SongSorter.sort(songs, SongSortField.ARTIST, SortDirection.DESC).map { it.id },
        )
        assertEquals("#", AlphabeticalText.sectionFor("+\u03b1/\u3042\u308b\u3075\u3041\u304d\u3085\u3093"))
    }

    @Test
    fun customOrderKeepsSavedSongsFirstAndAppendsNewSongs() {
        val songs = listOf(
            SongFixtures.song(id = "a"),
            SongFixtures.song(id = "b"),
            SongFixtures.song(id = "c"),
        )

        assertEquals(
            listOf("c", "a", "b"),
            SongSorter.customOrder(songs, listOf("missing", "c", "a", "c")).map { it.id },
        )
    }
}
