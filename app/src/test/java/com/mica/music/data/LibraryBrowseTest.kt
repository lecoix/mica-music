package com.mica.music.data

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBrowseTest {

    @Test
    fun albumGroupsExposeArtworkYearAndArtistSummary() {
        val songs = listOf(
            SongFixtures.song(id = "late", queueOrder = 4).copy(
                album = "Kind of Blue",
                artist = "Miles Davis",
                year = 1960,
                releaseDate = "1960-05-01",
                albumArtUri = null,
            ),
            SongFixtures.song(id = "early", queueOrder = 1).copy(
                album = "Kind of Blue",
                artist = "Miles Davis",
                year = 1959,
                releaseDate = "1959-08-17",
                albumArtUri = "file:///kind-of-blue.jpg",
                coverColorArgb = 0xFF010203.toInt(),
            ),
        )

        val album = LibraryBrowse.groupByAlbum(songs).single()

        assertEquals("Kind of Blue", album.title)
        assertEquals("Miles Davis", album.artist)
        assertEquals(1959, album.year)
        assertEquals("1959-08-17", album.releaseDate)
        assertEquals("file:///kind-of-blue.jpg", album.albumArtUri)
        assertEquals(0xFF010203.toInt(), album.coverColorArgb)
        assertEquals(2, album.songCount)
    }

    @Test
    fun albumDateSortKeepsFullDatesBeforeYearOnlyAndUnknownLast() {
        val groups = LibraryBrowse.groupByAlbum(
            listOf(
                SongFixtures.song("year").copy(album = "Year", year = 2024, releaseDate = ""),
                SongFixtures.song("late").copy(album = "Late", year = 2024, releaseDate = "2024-08-16"),
                SongFixtures.song("early").copy(album = "Early", year = 2024, releaseDate = "2024-01-05"),
                SongFixtures.song("unknown").copy(album = "Unknown", year = 0, releaseDate = ""),
            ),
        )

        assertEquals(
            listOf("Early", "Late", "Year", "Unknown"),
            LibraryBrowse.sortAlbumGroups(groups, AlbumBrowseSortField.YEAR, SortDirection.ASC)
                .map { it.title },
        )
        assertEquals(
            listOf("Late", "Early", "Year", "Unknown"),
            LibraryBrowse.sortAlbumGroups(groups, AlbumBrowseSortField.YEAR, SortDirection.DESC)
                .map { it.title },
        )
    }

    @Test
    fun albumGroupsSortByRequestedFieldAndDirection() {
        val groups = LibraryBrowse.groupByAlbum(
            listOf(
                SongFixtures.song(id = "b1").copy(album = "Beta", artist = "Zoo", year = 2020),
                SongFixtures.song(id = "b2").copy(album = "Beta", artist = "Zoo", year = 2020),
                SongFixtures.song(id = "a1").copy(album = "Alpha", artist = "Able", year = 1990),
                SongFixtures.song(id = "u1").copy(album = "Unknown Year", artist = "Able", year = 0),
            ),
        )

        assertEquals(
            listOf("Beta", "Alpha", "Unknown Year"),
            LibraryBrowse.sortAlbumGroups(groups, AlbumBrowseSortField.YEAR, SortDirection.DESC)
                .map { it.title },
        )
        assertEquals(
            listOf("Alpha", "Unknown Year", "Beta"),
            LibraryBrowse.sortAlbumGroups(groups, AlbumBrowseSortField.ARTIST, SortDirection.ASC)
                .map { it.title },
        )
        assertEquals(
            listOf("Beta", "Alpha", "Unknown Year"),
            LibraryBrowse.sortAlbumGroups(groups, AlbumBrowseSortField.SONG_COUNT, SortDirection.DESC)
                .map { it.title },
        )
    }

    @Test
    fun albumArtistSortMixesChineseByPinyinInitial() {
        val groups = LibraryBrowse.groupByAlbum(
            listOf(
                SongFixtures.song(id = "a").copy(album = "A Album", artist = "阿杜"),
                SongFixtures.song(id = "b").copy(album = "B Album", artist = "Beatles"),
                SongFixtures.song(id = "z").copy(album = "Z Album", artist = "Zoo"),
            ),
        )

        assertEquals(
            listOf("A Album", "B Album", "Z Album"),
            LibraryBrowse.sortAlbumGroups(groups, AlbumBrowseSortField.ARTIST, SortDirection.ASC)
                .map { it.title },
        )
    }

    @Test
    fun artistGroupsSortByRequestedFieldAndDirection() {
        val groups = LibraryBrowse.groupByArtist(
            listOf(
                SongFixtures.song(id = "a").copy(artist = "Alpha"),
                SongFixtures.song(id = "b").copy(artist = "Beta"),
                SongFixtures.song(id = "b2").copy(artist = "Beta"),
            ),
        )

        assertEquals(
            listOf("Beta", "Alpha"),
            LibraryBrowse.sortArtistGroups(groups, ArtistBrowseSortField.TITLE, SortDirection.DESC)
                .map { it.title },
        )
        assertEquals(
            listOf("Beta", "Alpha"),
            LibraryBrowse.sortArtistGroups(groups, ArtistBrowseSortField.SONG_COUNT, SortDirection.DESC)
                .map { it.title },
        )
    }

    @Test
    fun browseGroupPresentationPrecomputesFastScrollIndexWhenAlphabetical() {
        val songs = listOf(
            SongFixtures.song(id = "a").copy(album = "Alpha", artist = "Artist A"),
            SongFixtures.song(id = "b").copy(album = "Beta", artist = "Artist B"),
        )

        val albums = LibraryBrowse.albumGroupPresentation(
            songs,
            AlbumBrowseSortField.TITLE,
            SortDirection.ASC,
        )
        val artists = LibraryBrowse.artistGroupPresentation(
            songs,
            ArtistBrowseSortField.TITLE,
            SortDirection.ASC,
        )

        assertEquals(listOf("Alpha", "Beta"), albums.fastScrollIndex?.labels)
        assertEquals(mapOf("A" to 0, "B" to 1), albums.fastScrollIndex?.sectionTargets)
        assertEquals(listOf("Artist A", "Artist B"), artists.fastScrollIndex?.labels)
        assertEquals(mapOf("A" to 0), artists.fastScrollIndex?.sectionTargets)
    }

    @Test
    fun browseGroupPresentationSkipsFastScrollIndexForCountSorts() {
        val songs = listOf(
            SongFixtures.song(id = "a").copy(album = "Alpha", artist = "Artist A"),
            SongFixtures.song(id = "b").copy(album = "Beta", artist = "Artist B"),
        )

        assertEquals(
            null,
            LibraryBrowse.albumGroupPresentation(
                songs,
                AlbumBrowseSortField.SONG_COUNT,
                SortDirection.ASC,
            ).fastScrollIndex,
        )
        assertEquals(
            null,
            LibraryBrowse.artistGroupPresentation(
                songs,
                ArtistBrowseSortField.SONG_COUNT,
                SortDirection.ASC,
            ).fastScrollIndex,
        )
    }

    @Test
    fun folderGroupsAggregateOneDepthAtATime() {
        val songs = listOf(
            song("queen-1", "Music/Rock/Queen"),
            song("queen-2", "Music/Rock/Queen"),
            song("miles", "Music/Jazz/Miles Davis"),
            song("loose", "Downloads"),
        )

        val root = LibraryBrowse.folderGroups(songs, emptyList())
        assertEquals(listOf("Downloads", "Music"), root.map { it.title })
        assertEquals(3, root.single { it.title == "Music" }.songCount)

        val music = LibraryBrowse.folderGroups(songs, listOf("Music"))
        assertEquals(listOf("Jazz", "Rock"), music.map { it.title })
        assertEquals(listOf("Music", "Rock"), music.single { it.title == "Rock" }.pathSegments)

        val rock = LibraryBrowse.folderGroups(songs, listOf("Music", "Rock"))
        assertEquals(listOf("Queen"), rock.map { it.title })
        assertEquals(2, rock.single().songCount)
    }

    @Test
    fun folderGroupsAtDepthUnifiesDepthButKeepsSameNamePathsSeparate() {
        val songs = listOf(
            song("music-rock", "Music/Rock/Queen"),
            song("download-rock", "Download/Rock/Live"),
            song("jazz", "Music/Jazz/Miles"),
        )

        val secondLayer = LibraryBrowse.folderGroupsAtDepth(songs, depth = 1)

        assertEquals(
            listOf("Jazz:Music", "Rock:Download", "Rock:Music"),
            secondLayer.map { "${it.title}:${it.pathSegments.dropLast(1).joinToString("/")}" },
        )
    }

    @Test
    fun musicFolderGroupsIncludeOnlyDirectoriesWithDirectSongs() {
        val songs = listOf(
            song("rock-direct", "Music/Rock"),
            song("queen-1", "Music/Rock/Queen"),
            song("queen-2", "Music/Rock/Queen"),
            song("live", "Downloads/Rock"),
            song("missing-path", "", filePath = "missing-path.flac"),
        )

        val groups = LibraryBrowse.musicFolderGroups(songs)

        assertEquals(
            listOf(
                "Music/Rock/Queen:2",
                "Downloads/Rock:1",
                "Music/Rock:1",
            ),
            groups.map { "${it.pathSegments.joinToString("/")}:${it.songCount}" },
        )
        assertEquals(
            listOf("Downloads", "Music"),
            groups.filter { it.title == "Rock" }.map { it.pathSegments.dropLast(1).joinToString("/") },
        )
        assertEquals(false, groups.any { it.pathSegments == listOf("Music") })
    }

    @Test
    fun musicFolderGroupsRecoverExactDirectoryFromLegacyFilePath() {
        val songs = listOf(
            song(
                id = "queen",
                folderPath = "Music",
                filePath = "/storage/emulated/0/Music/Rock/Queen/queen.flac",
            ),
        )

        val group = LibraryBrowse.musicFolderGroups(songs).single()

        assertEquals(listOf("Music", "Rock", "Queen"), group.pathSegments)
        assertEquals("1 首", group.subtitle)
    }

    @Test
    fun songsForFolderIncludesDescendants() {
        val songs = listOf(
            song("rock-direct", "Music/Rock"),
            song("queen-1", "Music/Rock/Queen"),
            song("queen-2", "Music/Rock/Queen"),
            song("miles", "Music/Jazz/Miles Davis"),
        )

        assertEquals(
            listOf("rock-direct", "queen-1", "queen-2"),
            LibraryBrowse.songsForFolder(songs, listOf("Music", "Rock")).map { it.id },
        )
        assertEquals(
            listOf("rock-direct"),
            LibraryBrowse.songsInFolder(songs, listOf("Music", "Rock")).map { it.id },
        )
    }

    @Test
    fun folderBrowseUsesFilePathToRecoverOldMediaStoreFolderPaths() {
        val songs = listOf(
            song(
                id = "queen",
                folderPath = "Music",
                filePath = "Music/Rock/Queen/queen.flac",
            ),
            song(
                id = "absolute",
                folderPath = "Music",
                filePath = "/storage/emulated/0/Music/Jazz/Miles/miles.flac",
            ),
        )

        assertEquals(
            listOf("Music"),
            LibraryBrowse.folderGroups(songs, emptyList()).map { it.title },
        )
        assertEquals(
            listOf("Jazz", "Rock"),
            LibraryBrowse.folderGroups(songs, listOf("Music")).map { it.title },
        )
        assertEquals(
            listOf("queen"),
            LibraryBrowse.songsForFolder(songs, listOf("Music", "Rock")).map { it.id },
        )
    }

    private fun song(
        id: String,
        folderPath: String,
        filePath: String = "$folderPath/$id.flac",
    ): Song = SongFixtures.song(id = id).copy(
        folderPath = folderPath,
        filePath = filePath,
    )
}
