package com.mica.music.data

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSongSortTest {
    @Test
    fun remoteSortFieldsExcludeLocalFilesystemAndCustomOrdering() {
        assertEquals(
            listOf(
                SongSortField.TITLE,
                SongSortField.ALBUM,
                SongSortField.ARTIST,
                SongSortField.YEAR,
                SongSortField.PLAY_COUNT,
                SongSortField.LAST_PLAYED,
                SongSortField.DURATION,
            ),
            REMOTE_SONG_SORT_FIELDS,
        )
        assertFalse(SongSortField.FOLDER in REMOTE_SONG_SORT_FIELDS)
        assertFalse(SongSortField.DATE_MODIFIED in REMOTE_SONG_SORT_FIELDS)
        assertFalse(SongSortField.DATE_ADDED in REMOTE_SONG_SORT_FIELDS)
        assertFalse(SongSortField.CUSTOM in REMOTE_SONG_SORT_FIELDS)
    }

    @Test
    fun remotePresentationSortUsesSameSongSorterAsPlaybackQueue() {
        val songs = listOf(
            SongFixtures.song(id = "low").copy(playCount = 1),
            SongFixtures.song(id = "high").copy(playCount = 9),
            SongFixtures.song(id = "mid").copy(playCount = 4),
        )

        val sorted = SongSorter.sort(songs, SongSortField.PLAY_COUNT, SortDirection.DESC)

        assertEquals(listOf("high", "mid", "low"), sorted.map { it.id })
        assertTrue(sorted.all { song -> songs.any { it.id == song.id } })
    }
}