package com.mica.music.data.remote.navidrome

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NavidromeCatalogPagerTest {
    @Test
    fun `normal search paging enumerates full library without album fallback`() = runTest {
        val api = FakeApi(
            searchPages = mapOf(
                0 to page(3, "1", "2", "3"),
                3 to page(3, "4", "5", "6"),
                6 to page(1, "7"),
            ),
        )

        val songs = NavidromeCatalogPager(api, pageSize = 3).listSongs()

        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7"), songs.map { it.remoteId })
        assertEquals(listOf(0 to 3, 3 to 3, 6 to 3), api.searchRequests)
        assertFalse(api.albumIdsRequested)
    }

    @Test
    fun `repeated search page triggers album fallback and dedupes songs`() = runTest {
        val api = FakeApi(
            searchPages = mapOf(
                0 to page(3, "1", "2", "3"),
                3 to page(3, "1", "2", "3"),
            ),
            albumIdPages = mapOf(0 to listOf("album-a", "album-b")),
            songsByAlbum = mapOf(
                "album-a" to tracks("1", "2", "4"),
                "album-b" to tracks("4", "5", "6"),
            ),
        )

        val songs = NavidromeCatalogPager(api, pageSize = 3).listSongs()

        assertEquals(listOf("1", "2", "3", "4", "5", "6"), songs.map { it.remoteId })
        assertEquals(listOf(0 to 3, 3 to 3), api.searchRequests)
        assertEquals(listOf(0 to 3), api.albumIdRequests)
    }

    @Test
    fun `empty page after one full page is treated as possibly truncated`() = runTest {
        val api = FakeApi(
            searchPages = mapOf(
                0 to page(3, "1", "2", "3"),
                3 to page(0),
            ),
            albumIdPages = mapOf(0 to listOf("album-a")),
            songsByAlbum = mapOf("album-a" to tracks("1", "2", "3", "4")),
        )

        val songs = NavidromeCatalogPager(api, pageSize = 3).listSongs()

        assertEquals(listOf("1", "2", "3", "4"), songs.map { it.remoteId })
        assertEquals(listOf(0 to 3), api.albumIdRequests)
    }

    @Test
    fun `duplicate filtered page does not masquerade as end of search`() = runTest {
        val api = FakeApi(
            searchPages = mapOf(
                0 to page(3, "1", "2", "3"),
                3 to page(3, "3", "4", "4"),
                6 to page(1, "5"),
            ),
        )

        val songs = NavidromeCatalogPager(api, pageSize = 3).listSongs()

        assertEquals(listOf("1", "2", "3", "4", "5"), songs.map { it.remoteId })
        assertEquals(listOf(0 to 3, 3 to 3, 6 to 3), api.searchRequests)
        assertFalse(api.albumIdsRequested)
    }

    @Test
    fun `limit bounds search page size and avoids unnecessary fallback`() = runTest {
        val api = FakeApi(
            searchPages = mapOf(
                0 to page(3, "1", "2", "3"),
                3 to page(1, "4"),
            ),
        )

        val songs = NavidromeCatalogPager(api, pageSize = 3).listSongs(limit = 4)

        assertEquals(listOf("1", "2", "3", "4"), songs.map { it.remoteId })
        assertEquals(listOf(0 to 3, 3 to 1), api.searchRequests)
        assertFalse(api.albumIdsRequested)
    }

    private fun page(rawCount: Int, vararg ids: String) =
        NavidromeSongPage(tracks(*ids), rawCount)

    private fun tracks(vararg ids: String) = ids.map { NavidromeTrack(remoteId = it, title = "Track $it") }

    private class FakeApi(
        private val searchPages: Map<Int, NavidromeSongPage>,
        private val albumIdPages: Map<Int, List<String>> = emptyMap(),
        private val songsByAlbum: Map<String, List<NavidromeTrack>> = emptyMap(),
    ) : NavidromeCatalogApi {
        val searchRequests = mutableListOf<Pair<Int, Int>>()
        val albumIdRequests = mutableListOf<Pair<Int, Int>>()
        val albumIdsRequested: Boolean get() = albumIdRequests.isNotEmpty()

        override suspend fun searchAllSongsPage(offset: Int, count: Int): NavidromeSongPage {
            searchRequests += offset to count
            return searchPages[offset] ?: NavidromeSongPage(emptyList(), 0)
        }

        override suspend fun albumIdsPage(offset: Int, count: Int): List<String> {
            albumIdRequests += offset to count
            return albumIdPages[offset].orEmpty()
        }

        override suspend fun albumSongs(albumId: String): List<NavidromeTrack> = songsByAlbum[albumId].orEmpty()
    }
}
