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
                albumArtUri = null,
            ),
            SongFixtures.song(id = "early", queueOrder = 1).copy(
                album = "Kind of Blue",
                artist = "Miles Davis",
                year = 1959,
                albumArtUri = "file:///kind-of-blue.jpg",
                coverColorArgb = 0xFF010203.toInt(),
            ),
        )

        val album = LibraryBrowse.groupByAlbum(songs).single()

        assertEquals("Kind of Blue", album.title)
        assertEquals("Miles Davis", album.artist)
        assertEquals(1959, album.year)
        assertEquals("file:///kind-of-blue.jpg", album.albumArtUri)
        assertEquals(0xFF010203.toInt(), album.coverColorArgb)
        assertEquals(2, album.songCount)
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
    fun artistGroupsSortByDirection() {
        val groups = LibraryBrowse.groupByArtist(
            listOf(
                SongFixtures.song(id = "a").copy(artist = "Alpha"),
                SongFixtures.song(id = "b").copy(artist = "Beta"),
            ),
        )

        assertEquals(
            listOf("Beta", "Alpha"),
            LibraryBrowse.sortArtistGroups(groups, SortDirection.DESC).map { it.title },
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
