package com.mica.music.data

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class SongSorterTest {

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
