package com.mica.music.data.scanner

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCoverMatcherTest {
    @Test
    fun exactNameWinsOverNormalizedNameInSameFolder() {
        val song = SongFixtures.song("one").copy(album = "Album Name", folderPath = "Artist/Album")
        val result = attachVideoCovers(
            listOf(song),
            listOf(
                VideoCoverFile("content://normalized", "Artist/Album", "  album   name "),
                VideoCoverFile("content://exact", "Artist/Album", "Album Name"),
            ),
        )

        assertEquals("content://exact", result.single().videoCoverUri)
    }

    @Test
    fun normalizedNameMatchesUnicodeWhitespaceAndCase() {
        val song = SongFixtures.song("one").copy(album = "ＡＬＢＵＭ  Name", folderPath = "Album")
        val result = attachVideoCovers(
            listOf(song),
            listOf(VideoCoverFile("content://video", "Album", "album name")),
        )

        assertEquals("content://video", result.single().videoCoverUri)
    }

    @Test
    fun differentFolderDuplicateAndAmbiguousCandidatesDoNotMatch() {
        val song = SongFixtures.song("one").copy(album = "Album", folderPath = "Wanted")
        val differentFolder = attachVideoCovers(
            listOf(song),
            listOf(VideoCoverFile("content://other", "Other", "Album")),
        )
        val ambiguous = attachVideoCovers(
            listOf(song),
            listOf(
                VideoCoverFile("content://one", "Wanted", " album "),
                VideoCoverFile("content://two", "Wanted", "ALBUM"),
            ),
        )

        assertNull(differentFolder.single().videoCoverUri)
        assertNull(ambiguous.single().videoCoverUri)
    }

    @Test
    fun unknownAlbumNeverMatches() {
        val song = SongFixtures.song("one").copy(album = "未知专辑", folderPath = "Album")

        assertNull(
            attachVideoCovers(
                listOf(song),
                listOf(VideoCoverFile("content://video", "Album", "未知专辑")),
            ).single().videoCoverUri,
        )
    }
}
