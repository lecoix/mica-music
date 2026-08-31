package com.mica.music.data.remote.navidrome

/**
 * Whole-library enumerator that preserves compatibility with Subsonic servers that ignore
 * songOffset for an empty search3 query.
 */
class NavidromeCatalogPager(
    private val api: NavidromeCatalogApi,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    suspend fun listSongs(limit: Int = Int.MAX_VALUE): List<NavidromeTrack> {
        val targetCount = limit.takeIf { it > 0 } ?: return emptyList()
        val songs = mutableListOf<NavidromeTrack>()
        val seenSongIds = LinkedHashSet<String>()
        var songOffset = 0
        var searchMayBeTruncated = false

        while (songs.size < targetCount) {
            val requestedPageSize = if (targetCount == Int.MAX_VALUE) {
                pageSize
            } else {
                minOf(pageSize, targetCount - songs.size).coerceAtLeast(1)
            }
            val page = api.searchAllSongsPage(songOffset, requestedPageSize)
            if (page.rawCount == 0) {
                searchMayBeTruncated = songOffset > 0 && songs.size >= pageSize
                break
            }

            val beforeCount = songs.size
            page.songs.forEach { song ->
                if (!seenSongIds.add(song.remoteId)) return@forEach
                songs += song
                if (songs.size >= targetCount) return songs
            }

            if (page.rawCount < requestedPageSize) break
            songOffset += page.rawCount
            if (songs.size == beforeCount) {
                searchMayBeTruncated = songs.size >= pageSize
                break
            }
        }

        if (songs.size < targetCount && searchMayBeTruncated) {
            appendSongsByAlbums(
                targetCount = targetCount,
                seenSongIds = seenSongIds,
                output = songs,
            )
        }
        return songs
    }

    private suspend fun appendSongsByAlbums(
        targetCount: Int,
        seenSongIds: MutableSet<String>,
        output: MutableList<NavidromeTrack>,
    ) {
        var albumOffset = 0
        while (seenSongIds.size < targetCount) {
            val albumIds = api.albumIdsPage(albumOffset, pageSize)
            if (albumIds.isEmpty()) break
            albumIds.forEach { albumId ->
                api.albumSongs(albumId).forEach { song ->
                    if (!seenSongIds.add(song.remoteId)) return@forEach
                    output += song
                    if (seenSongIds.size >= targetCount) return
                }
            }
            if (albumIds.size < pageSize) break
            albumOffset += albumIds.size
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 500
    }
}
