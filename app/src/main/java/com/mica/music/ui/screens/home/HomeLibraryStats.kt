package com.mica.music.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.SongListInfoVisibility
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
    albumSortField: AlbumBrowseSortField = AlbumBrowseSortField.TITLE,
    albumSortDirection: SortDirection = SortDirection.ASC,
    albumGridColumns: Int = 1,
    artistSortField: ArtistBrowseSortField = ArtistBrowseSortField.TITLE,
    artistSortDirection: SortDirection = SortDirection.ASC,
    artistGridColumns: Int = 1,
    songListInfoVisibility: SongListInfoVisibility = SongListInfoVisibility(),
): LibraryStatsBarModel? {
    val songs = library.songs
    return remember(
        section,
        browseDestination,
        activePlaylistId,
        playlistSongCount,
        playlistSortField,
        playlistSortDirection,
        albumSortField,
        albumSortDirection,
        albumGridColumns,
        artistSortField,
        artistSortDirection,
        artistGridColumns,
        songListInfoVisibility,
        songs,
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
            albumSortField,
            albumSortDirection,
            albumGridColumns,
            artistSortField,
            artistSortDirection,
            artistGridColumns,
            songListInfoVisibility,
        )
    }
}

private fun sortSegment(library: MusicLibrary): String =
    formatSortLabel(library.sortField, library.sortDirection)

internal fun resolveLibraryStatsBarModel(
    section: HomeSection,
    browseDestination: BrowseDestination,
    library: MusicLibrary,
    activePlaylistId: String?,
    playlistSongCount: Int,
    playlistSortField: SongSortField?,
    playlistSortDirection: SortDirection?,
    albumSortField: AlbumBrowseSortField = AlbumBrowseSortField.TITLE,
    albumSortDirection: SortDirection = SortDirection.ASC,
    albumGridColumns: Int = 1,
    artistSortField: ArtistBrowseSortField = ArtistBrowseSortField.TITLE,
    artistSortDirection: SortDirection = SortDirection.ASC,
    artistGridColumns: Int = 1,
    songListInfoVisibility: SongListInfoVisibility = SongListInfoVisibility(),
): LibraryStatsBarModel? {
    if (section == HomeSection.Settings) {
        return null
    }

    val scanSegments = libraryScanSegments(library)

    return when (section) {
        HomeSection.Songs -> LibraryStatsBarModel(
            segments = buildSongListSegments(library, songListInfoVisibility),
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
                    if (recent.isNotEmpty()) "按最近播放" else null,
                ),
            )
        }
        HomeSection.LibraryAnalysis -> {
            val count = library.songs.size
            LibraryStatsBarModel(
                segments = when {
                    count == 0 -> listOf("暂无曲库数据")
                    else -> listOfNotNull(
                        songCountLabel(count, library.lastScanAtMs),
                        formatSize(library.totalSizeMb),
                    ) + libraryScanSegments(library)
                },
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
                    "${library.artistGroups().size} 位艺术家",
                    formatBrowseSortLabel(artistSortField, artistSortDirection),
                    formatGridColumnsLabel(artistGridColumns),
                ) + scanSegments,
                isScanning = library.isScanning,
                scanProgressLabel = library.scanProgressLabel,
                scanError = library.lastScanError,
                showSortAction = true,
                showRescanAction = true,
            )
            is BrowseDestination.Artist -> null
            else -> null
        }
        HomeSection.Albums -> when (browseDestination) {
            BrowseDestination.Root -> LibraryStatsBarModel(
                segments = listOfNotNull(
                    "${library.albumGroups().size} 张专辑",
                    formatBrowseSortLabel(albumSortField, albumSortDirection),
                    formatGridColumnsLabel(albumGridColumns),
                ) + scanSegments,
                isScanning = library.isScanning,
                scanProgressLabel = library.scanProgressLabel,
                scanError = library.lastScanError,
                showSortAction = true,
                showRescanAction = true,
            )
            is BrowseDestination.Album -> null
            else -> null
        }
        HomeSection.Folders -> when (browseDestination) {
            BrowseDestination.Root -> LibraryStatsBarModel(
                segments = listOfNotNull(
                    "${library.folderGroupsAtDepth(0).size} 个文件夹",
                ) + scanSegments,
                isScanning = library.isScanning,
                scanProgressLabel = library.scanProgressLabel,
                scanError = library.lastScanError,
                showRescanAction = true,
            )
            is BrowseDestination.Folder -> {
                val scope = browseDestination.scopePathSegments
                val groups = library.folderGroupsAtDepth(browseDestination.depth, scope)
                val songs = if (scope.isNotEmpty()) library.songsForFolder(scope) else emptyList()
                if (groups.isEmpty() && songs.isNotEmpty()) {
                    subsetStats(songs, library)
                } else {
                    LibraryStatsBarModel(
                        segments = listOfNotNull(
                            "${groups.size} 个文件夹",
                            if (scope.isNotEmpty()) "${songs.size} 首歌曲" else null,
                        ),
                        showRescanAction = true,
                    )
                }
            }
            else -> null
        }
        else -> null
    }
}

private fun buildSongListSegments(
    library: MusicLibrary,
    visibility: SongListInfoVisibility,
): List<String> {
    val segments = mutableListOf<String>()
    if (visibility.showSongCount) {
        segments += songCountLabel(library.songs.size, library.lastScanAtMs)
    }
    if (visibility.showLibrarySize) {
        segments += formatSize(library.totalSizeMb)
    }
    if (visibility.showSortOrder) {
        segments += sortSegment(library)
    }
    segments += songListScanSegments(library, visibility.showLastScanTime)
    if (visibility.showCustomText) {
        visibility.customText.trim().takeIf { it.isNotEmpty() }?.let { segments += it }
    }
    return segments
}

private fun songListScanSegments(library: MusicLibrary, showLastScanTime: Boolean): List<String> =
    when {
        library.isScanning -> listOf("扫描中")
        !library.scanProgressLabel.isNullOrBlank() -> listOf(library.scanProgressLabel!!)
        showLastScanTime && library.lastScanAtMs != null -> listOf(formatLastScan(library.lastScanAtMs))
        else -> emptyList()
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
        library.isScanning -> listOf("扫描中")
        !library.scanProgressLabel.isNullOrBlank() -> listOf(library.scanProgressLabel!!)
        library.lastScanAtMs != null -> listOf(formatLastScan(library.lastScanAtMs))
        else -> emptyList()
    }

private fun formatBrowseSortLabel(
    field: ArtistBrowseSortField,
    direction: SortDirection,
): String = listOf(field.label, direction.label).joinToString(" · ")

private fun formatBrowseSortLabel(
    field: AlbumBrowseSortField?,
    direction: SortDirection,
): String = listOfNotNull(field?.label ?: "标题", direction.label).joinToString(" · ")

private fun formatGridColumnsLabel(columns: Int): String =
    "${columns.coerceIn(1, 4)}列"

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
