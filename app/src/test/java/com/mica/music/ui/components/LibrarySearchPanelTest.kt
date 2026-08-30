package com.mica.music.ui.components

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LibrarySearchPanelTest {
    @Test
    fun remoteSearchIndexMatchesTitleArtistAlbumAndFileName() {
        val remote = SongFixtures.song("remote-aizo").copy(
            title = "AIZO",
            artist = "King Gnu",
            album = "AIZO Single",
            fileName = "01. AIZO.flac",
        )
        val index = RemoteSongSearchIndex(listOf(remote), Locale.ROOT)

        assertEquals(listOf("remote-aizo"), index.search("aizo").map { it.id })
        assertEquals(listOf("remote-aizo"), index.search("king").map { it.id })
        assertEquals(listOf("remote-aizo"), index.search("single").map { it.id })
        assertEquals(listOf("remote-aizo"), index.search("01.").map { it.id })
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun mergeSearchResultsKeepsLocalOrderThenRemoteAndDeduplicatesIds() {
        val local = SongFixtures.song("same").copy(title = "local")
        val remoteDuplicate = SongFixtures.song("same").copy(title = "remote duplicate")
        val remoteOnly = SongFixtures.song("remote-only")

        val merged = mergeLibrarySearchResults(
            localResults = listOf(local),
            remoteResults = listOf(remoteDuplicate, remoteOnly),
        )

        assertEquals(listOf("same", "remote-only"), merged.map { it.id })
        assertEquals("local", merged.first().title)
    }
}
