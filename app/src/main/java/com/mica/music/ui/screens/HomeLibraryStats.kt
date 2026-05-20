package com.mica.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.formatSortLabel

internal data class LibraryStatsBarModel(
    val segments: List<String>,
    val isScanning: Boolean = false,
    val scanProgressLabel: String? = null,
    val scanError: String? = null,
    val showSortAction: Boolean = false,
    val showRescanAction: Boolean = false,
    val showDeletePlaylistAction: Boolean = false,
)

@Composable
internal fun rememberLibraryStatsBarModel(
    section: HomeSection,
    browseDestination: BrowseDestination,
    library: MusicLibrary,
    activePlaylistId: String?,
    playlistSongCount: Int,
    playlistSortField: SongSortField?,
    playlistSortDirection: SortDirection?,
): LibraryStatsBarModel? {
    val songs = library.songs
    return remember(
        section,
        browseDestination,
        activePlaylistId,
        playlistSongCount,
        playlistSortField,
        playlistSortDirection,
        songs,
        library.favoritesRevision,
        library.totalSizeMb,
        library.lastScanAtMs,
        library.isScanning,
        library.scanProgressLabel,
        library.sortField,
        library.sortDirection,
    ) {
        resolveLibraryStatsBarModel(
            section,
            browseDestination,
            library,
            activePlaylistId,
            playlistSongCount,
            playlistSortField,
            playlistSortDirection,
        )
    }
}

private fun sortSegment(library: MusicLibrary): String = formatSortLabel(library.sortField, library.sortDirection)

internal fun resolveLibraryStatsBarModel(
    section: HomeSection,
    browseDestination: BrowseDestination,
    library: MusicLibrary,
    activePlaylistId: String?,
    playlistSongCount: Int,
    playlistSortField: SongSortField?,
    playlistSortDirection: SortDirection?,
): LibraryStatsBarModel? {
    if (section == HomeSection.Settings || section == HomeSection.LibraryAnalysis) return null

    val scanSegments = libraryScanSegments(library)

    return when (section) {
        HomeSection.Songs -> LibraryStatsBarModel(
            segments = listOfNotNull(
                songCountLabel(library.songs.size, library.lastScanAtMs),
                formatSize(library.totalSizeMb),
                sortSegment(library),
            ) + scanSegments,
            isScanning = library.isScanning,
            scanProgressLabel = library.scanProgressLabel,
            scanError = library.lastScanError,
            showSortAction = true,
            showRescanAction = true,
        )
        HomeSection.Recent -> {
            val recent = library.recentSongs()
            LibraryStatsBarModel(
                segments = listOfNotNull(
                    if (recent.isEmpty()) "暂无播放记录" else "${recent.size} 首",
                ),
            )
        }
        HomeSection.Favorites -> {
            val favorites = library.favoriteSongs()
            LibraryStatsBarModel(
                segments = listOfNotNull(
                    if (favorites.isEmpty()) "暂无收藏" else "${favorites.size} 首收藏",
                    if (favorites.isNotEmpty()) sortSegment(library) else null,
                ),
                showSortAction = favorites.isNotEmpty(),
            )
        }
        HomeSection.Playlist -> {
            if (activePlaylistId == null) return null
            LibraryStatsBarModel(
                segments = listOfNotNull(
                    if (playlistSongCount == 0) "歌单为空" else "$playlistSongCount 首",
                    if (playlistSongCount > 0 && playlistSortField != null && playlistSortDirection != null) {
                        formatSortLabel(playlistSortField, playlistSortDirection)
                    } else {
                        null
                    },
                ),
                showSortAction = playlistSongCount > 0,
                showDeletePlaylistAction = true,
            )
        }
        HomeSection.Artists -> when (browseDestination) {
            BrowseDestination.Root -> LibraryStatsBarModel(
                segments = listOfNotNull(
                    "${library.artistGroups().size} 位歌手",
                ) + scanSegments,
                isScanning = library.isScanning,
                scanProgressLabel = library.scanProgressLabel,
                scanError = library.lastScanError,
                showRescanAction = true,
            )
            is BrowseDestination.Artist -> {
                val subset = library.songsForArtist(browseDestination.name)
                subsetStats(subset, library)
            }
            else -> null
        }
        HomeSection.Albums -> when (browseDestination) {
            BrowseDestination.Root -> LibraryStatsBarModel(
                segments = listOfNotNull(
                    "${library.albumGroups().size} 张专辑",
                ) + scanSegments,
                isScanning = library.isScanning,
                scanProgressLabel = library.scanProgressLabel,
                scanError = library.lastScanError,
                showRescanAction = true,
            )
            is BrowseDestination.Album -> {
                val subset = library.songsForAlbum(browseDestination.title)
                subsetStats(subset, library)
            }
            else -> null
        }
        else -> null
    }
}

private fun subsetStats(songs: List<Song>, library: MusicLibrary): LibraryStatsBarModel {
    val sizeMb = songs.totalSizeMb()
    return LibraryStatsBarModel(
        segments = listOfNotNull(
            "${songs.size} 首歌曲",
            if (sizeMb > 0) formatSize(sizeMb) else null,
            if (songs.isNotEmpty()) sortSegment(library) else null,
        ),
        showSortAction = songs.isNotEmpty(),
    )
}

private fun libraryScanSegments(library: MusicLibrary): List<String> =
    when {
        library.isScanning -> listOf("扫描中…")
        !library.scanProgressLabel.isNullOrBlank() -> listOf(library.scanProgressLabel!!)
        library.lastScanAtMs != null -> listOf(formatLastScan(library.lastScanAtMs))
        else -> emptyList()
    }

private fun songCountLabel(count: Int, lastScanAtMs: Long?): String =
    if (count == 0 && lastScanAtMs == null) "未扫描" else "$count 首歌曲"

private fun List<Song>.totalSizeMb(): Int =
    (sumOf { it.sizeBytes.coerceAtLeast(0L) } / (1024 * 1024)).toInt().coerceAtLeast(0)

private fun formatSize(totalMb: Int): String = when {
    totalMb <= 0 -> "0 MB"
    totalMb < 1024 -> "$totalMb MB"
    else -> "%.1f GB".format(totalMb / 1024f)
}

private fun formatLastScan(scanAtMs: Long?): String {
    if (scanAtMs == null) return "未扫描"
    val mins = (System.currentTimeMillis() - scanAtMs) / 60_000
    return when {
        mins < 1L -> "刚刚扫描"
        mins < 60L -> "${mins} 分钟前"
        mins < 24L * 60L -> "${mins / 60L} 小时前"
        else -> "${mins / (24L * 60L)} 天前"
    }
}
