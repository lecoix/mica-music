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
            releaseDate = if (index % 3 == 0) {
                "${1990 + index % 35}-${(index % 12 + 1).toString().padStart(2, '0')}-${
                    (index % 28 + 1).toString().padStart(2, '0')
                }"
            } else {
                ""
            },
        )
    }

    @Test(timeout = 5_000)
    fun tenThousandSongsCanBeSearchedGroupedAndResolved() {
        ArtistNames.configure(ArtistSplitConfig())
        val featured = LibraryBrowse.search(songs, "featured artist")
        val artists = LibraryBrowse.groupByArtist(songs)
        val albums = LibraryBrowse.groupByAlbum(songs)

        assertEquals(100, featured.size)
        assertEquals(259, artists.size)
        assertEquals(401, albums.size)
        assertEquals(583, LibraryBrowse.songsForArtist(songs, "未知艺术家").size)
        assertEquals(323, LibraryBrowse.songsForAlbum(songs, AlbumBrowseKey("未知专辑", "Album Artist")).size)
        assertTrue(artists.zipWithNext().all { (a, b) ->
            a.title.lowercase(Locale.CHINA) <= b.title.lowercase(Locale.CHINA)
        })
    }

    @Test(timeout = 5_000)
    fun configurableArtistSplittingStaysBoundedAtTenThousandSongs() {
        ArtistNames.configure(
            ArtistSplitConfig(
                enabledSeparators = ArtistSeparator.entries.toSet(),
                whitelist = listOf("Featured Artist / Guest 1"),
            ),
        )

        val artists = LibraryBrowse.groupByArtist(songs)

        assertTrue(artists.isNotEmpty())
        assertTrue(artists.sumOf { it.songCount } <= songs.size * ArtistNames.MAX_ARTISTS_PER_TAG)
        ArtistNames.configure(ArtistSplitConfig())
    }

    @Test(timeout = 5_000)
    fun exactMusicFolderGroupingStaysBoundedAtTenThousandSongs() {
        val oneFolderPerSong = songs.mapIndexed { index, song ->
            song.copy(
                folderPath = "Music/Collection ${index / 100}/Album $index",
                filePath = "Music/Collection ${index / 100}/Album $index/${song.fileName}",
            )
        }

        val groups = LibraryBrowse.musicFolderGroups(oneFolderPerSong)
        val retainedTextBytes = groups.sumOf { group ->
            group.title.toByteArray().size.toLong() +
                group.subtitle.toByteArray().size +
                group.pathSegments.sumOf { it.toByteArray().size }
        }

        assertEquals(10_000, groups.size)
        assertEquals(10_000, groups.sumOf { it.songCount })
        assertTrue(retainedTextBytes < 2_000_000L)
        assertTrue(groups.all { it.pathSegments.size == 3 })
    }

    @Test(timeout = 5_000)
    fun persistedBrowseProjectionStaysSmallAndNeverIncludesLyricsAtTenThousandSongs() {
        ArtistNames.configure(ArtistSplitConfig())
        val groups = LibraryBrowse.groupByArtist(songs) + LibraryBrowse.groupByAlbum(songs)
        val rawTextBytes = groups.sumOf { group ->
            listOf(group.title, group.subtitle, group.artist, group.releaseDate, group.albumArtUri.orEmpty())
                .sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        }
        val fixedWidthBytes = groups.size * (Int.SIZE_BYTES * 5L)

        assertEquals(660, groups.size)
        assertTrue(rawTextBytes + fixedWidthBytes < 1_000_000L)
        assertTrue(songs.all { it.lyricsDocument.lines.isNotEmpty() })
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
                if (field != SongSortField.YEAR) {
                    assertEquals("field=$field", ascending.map { it.id }.reversed(), descending.map { it.id })
                }
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
