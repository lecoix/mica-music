package com.mica.music.data.scanner

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDirectoryCandidatesTest {
    @Test
    fun emptyLibraryReturnsNoCandidates() {
        assertTrue(scanDirectoryCandidates(emptyList()).isEmpty())
    }

    @Test
    fun buildsPrefixPathsFromSongFolders() {
        val songs = listOf(
            SongFixtures.song(id = "a").copy(folderPath = "Music/Rock"),
            SongFixtures.song(id = "b").copy(folderPath = "Music/Jazz"),
        )

        assertEquals(
            listOf("Music", "Music/Rock", "Music/Jazz"),
            scanDirectoryCandidates(songs),
        )
    }

    @Test
    fun ignoresBlankFolderPaths() {
        val songs = listOf(
            SongFixtures.song(id = "a").copy(folderPath = ""),
            SongFixtures.song(id = "b").copy(folderPath = "Music/Live"),
        )

        assertEquals(
            listOf("Music", "Music/Live"),
            scanDirectoryCandidates(songs),
        )
    }
}
