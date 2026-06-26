package com.mica.music.data

import com.mica.music.testutil.SongFixtures
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScaleTest {

    private val songs = List(10_000) { index ->
        val artist = when {
            index % 100 == 0 -> "Featured Artist / Guest ${index % 7}"
            index % 17 == 0 -> ""
            else -> "Artist ${index % 250}"
        }
        SongFixtures.song(
            id = "song-$index",
            title = "Track ${index.toString().padStart(5, '0')}",
            container = when (index % 4) {
                0 -> "FLAC"
                1 -> "MP3"
                2 -> "ALAC"
                else -> "DSD"
            },
            queueOrder = index,
        ).copy(
            artist = artist,
            album = if (index % 31 == 0) "" else "Album ${index % 400}",
            fileName = "library-track-$index.audio",
            folderPath = "Music/${index % 50}",
            playCount = index % 20,
            totalListenSeconds = (index % 12).toLong() * 60L,
            lastPlayedAtMs = index.toLong() * 1_000,
            year = 1990 + index % 35,
        )
    }

    @Test(timeout = 5_000)
    fun tenThousandSongsCanBeSearchedGroupedAndResolved() {
        val featured = LibraryBrowse.search(songs, "featured artist")
        val artists = LibraryBrowse.groupByArtist(songs)
        val albums = LibraryBrowse.groupByAlbum(songs)

        assertEquals(100, featured.size)
        assertEquals(259, artists.size)
        assertEquals(401, albums.size)
        assertEquals(583, LibraryBrowse.songsForArtist(songs, "未知艺术家").size)
        assertEquals(323, LibraryBrowse.songsForAlbum(songs, "未知专辑").size)
        assertTrue(artists.zipWithNext().all { (a, b) ->
            a.title.lowercase(Locale.CHINA) <= b.title.lowercase(Locale.CHINA)
        })
    }

    @Test(timeout = 8_000)
    fun everySortFieldPreservesAllSongsAndIsDeterministic() {
        SongSortField.entries
            .filterNot { it == SongSortField.CUSTOM }
            .forEach { field ->
                val ascending = SongSorter.sort(songs, field, SortDirection.ASC)
                val descending = SongSorter.sort(songs, field, SortDirection.DESC)

                assertEquals("field=$field", 10_000, ascending.size)
                assertEquals("field=$field", songs.map { it.id }.toSet(), ascending.map { it.id }.toSet())
                assertEquals("field=$field", ascending.map { it.id }.reversed(), descending.map { it.id })
                assertEquals(
                    "field=$field",
                    ascending.map { it.id },
                    SongSorter.sort(songs, field, SortDirection.ASC).map { it.id },
                )
            }
    }

    @Test(timeout = 5_000)
    fun analysisHandlesLargeLibraryAndNegativeStatisticsSafely() {
        val withBadStats = songs.mapIndexed { index, song ->
            if (index == 0) song.copy(sizeBytes = -1, playCount = -5) else song
        }

        val analysis = LibraryAnalyzer.analyze(withBadStats)

        assertEquals(10_000, analysis.totalSongs)
        assertEquals(9_500, analysis.playedSongCount)
        assertEquals(95_000, analysis.totalPlayCount)
        assertEquals(9_166, analysis.listenedSongCount)
        assertEquals(3_299_040L, analysis.totalListenSeconds)
        assertEquals(4, analysis.formatBreakdown.size)
        assertEquals(10_000, analysis.formatBreakdown.sumOf { it.count })
        assertEquals(10_000, analysis.qualityTierBreakdown.sumOf { it.count })
        assertTrue(analysis.totalSizeBytes >= 0)
    }

    @Test
    fun recentSongsKeepRequestedOrderAndIgnoreMissingOrDuplicateIdsPredictably() {
        val recent = LibraryBrowse.recentSongs(
            songs.take(4),
            listOf("song-3", "missing", "song-1", "song-3"),
        )

        assertEquals(listOf("song-3", "song-1", "song-3"), recent.map { it.id })
    }
}
