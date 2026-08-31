package com.mica.music.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.BrowseListInfoVisibility
import com.mica.music.data.FolderBrowseMode
import com.mica.music.data.LibraryBrowse
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
    val showFolderModeAction: Boolean = false,
    val showRescanAction: Boolean = false,
    val showDeletePlaylistAction: Boolean = false,
    val showMultiSelectAction: Boolean = false,
)

@Composable
internal fun rememberLibraryStatsBarModel(
    section: HomeSection,
    browseDestination: BrowseDestination,
    library: MusicLibrary,
    recentSongCount: Int? = null,
    remoteSongs: List<Song> = emptyList(),
    remoteSortField: SongSortField = SongSortField.TITLE,
    remoteSortDirection: SortDirection = SortDirection.ASC,
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
    folderBrowseMode: FolderBrowseMode = FolderBrowseMode.HIERARCHY,
    songListInfoVisibility: SongListInfoVisibility = SongListInfoVisibility(),
    browseListInfoVisibility: BrowseListInfoVisibility = BrowseListInfoVisibility(),
): LibraryStatsBarModel? {
    val songs = library.songs
    return remember(
        section,
        browseDestination,
        recentSongCount,
        remoteSongs,
        remoteSortField,
        remoteSortDirection,
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
        folderBrowseMode,
        songListInfoVisibility,
        browseListInfoVisibility,
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
            recentSongCount,
            remoteSongs,
            remoteSortField,
            remoteSortDirection,
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
            folderBrowseMode,
            songListInfoVisibility,
            browseListInfoVisibility,
        )
    }
}

private fun sortSegment(library: MusicLibrary): String =
    formatSortLabel(library.sortField, library.sortDirection)

internal fun resolveLibraryStatsBarModel(
    section: HomeSection,
    browseDestination: BrowseDestination,
    library: MusicLibrary,
    recentSongCount: Int? = null,
    remoteSongs: List<Song> = emptyList(),
    remoteSortField: SongSortField = SongSortField.TITLE,
    remoteSortDirection: SortDirection = SortDirection.ASC,
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
    folderBrowseMode: FolderBrowseMode = FolderBrowseMode.HIERARCHY,
    songListInfoVisibility: SongListInfoVisibility = SongListInfoVisibility(),
    browseListInfoVisibility: BrowseListInfoVisibility = BrowseListInfoVisibility(),
): LibraryStatsBarModel? {
    if (section == HomeSection.Settings) {
        return null
    }

    val scanSegments = libraryScanSegments(library)
    val browseSongs = mergedBrowseSongs(library.songs, remoteSongs)

    return when (section) {
        HomeSection.Songs -> LibraryStatsBarModel(
            segments = buildSongListSegments(library, songListInfoVisibility),
            isScanning = library.isScanning,
            scanProgressLabel = library.scanProgressLabel,
            scanError = library.lastScanError,
            showSortAction = true,
            showRescanAction = true,
        )
        HomeSection.Remote -> LibraryStatsBarModel(
            segments = listOfNotNull(
                if (remoteSongs.isEmpty()) "暂无远程歌曲" else "${remoteSongs.size} 首",
                formatSortLabel(remoteSortField, remoteSortDirection).takeIf { remoteSongs.isNotEmpty() },
            ),
            showSortAction = remoteSongs.isNotEmpty(),
            showMultiSelectAction = remoteSongs.isNotEmpty(),
        )
        HomeSection.Recent -> {
            val count = recentSongCount ?: library.recentSongs().size
            LibraryStatsBarModel(
                segments = listOfNotNull(
                    if (count == 0) "暂无播放记录" else "$count 首",
                    if (count > 0) "按最近播放" else null,
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
                    if (browseListInfoVisibility.showArtistCount) {
                        "${artistGroupCount(library, browseSongs, remoteSongs.isEmpty(), artistSortField, artistSortDirection)} 位艺术家"
                    } else {
                        null
                    },
                    formatBrowseSortLabel(artistSortField, artistSortDirection)
                        .takeIf { browseListInfoVisibility.showArtistSortOrder },
                    formatGridColumnsLabel(artistGridColumns)
                        .takeIf { browseListInfoVisibility.showArtistGridColumns },
                    browseListInfoVisibility.artistCustomText.trim()
                        .takeIf { browseListInfoVisibility.showArtistCustomText && it.isNotEmpty() },
                ) + browseScanSegments(library, browseListInfoVisibility.showArtistLastScanTime),
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
                    if (browseListInfoVisibility.showAlbumCount) {
                        "${albumGroupCount(library, browseSongs, remoteSongs.isEmpty(), albumSortField, albumSortDirection)} 张专辑"
                    } else {
                        null
                    },
                    formatBrowseSortLabel(albumSortField, albumSortDirection)
                        .takeIf { browseListInfoVisibility.showAlbumSortOrder },
                    formatGridColumnsLabel(albumGridColumns)
                        .takeIf { browseListInfoVisibility.showAlbumGridColumns },
                    browseListInfoVisibility.albumCustomText.trim()
                        .takeIf { browseListInfoVisibility.showAlbumCustomText && it.isNotEmpty() },
                ) + browseScanSegments(library, browseListInfoVisibility.showAlbumLastScanTime),
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
                    folderRootCountLabel(library, folderBrowseMode),
                    folderBrowseMode.label,
                ) + scanSegments,
                isScanning = library.isScanning,
                scanProgressLabel = library.scanProgressLabel,
                scanError = library.lastScanError,
                showFolderModeAction = true,
                showRescanAction = true,
            )
            is BrowseDestination.Folder -> {
                val scope = browseDestination.scopePathSegments
                if (scope.isEmpty()) {
                    return LibraryStatsBarModel(
                        segments = listOf(
                            folderRootCountLabel(library, folderBrowseMode),
                            folderBrowseMode.label,
                        ) + scanSegments,
                        isScanning = library.isScanning,
                        scanProgressLabel = library.scanProgressLabel,
                        scanError = library.lastScanError,
                        showFolderModeAction = true,
                        showRescanAction = true,
                    )
                }
                if (folderBrowseMode == FolderBrowseMode.MUSIC_FOLDERS) {
                    return LibraryStatsBarModel(
                        segments = listOf(scope.joinToString(" / ")),
                        showRescanAction = true,
                    )
                }
                val groups = library.folderGroupsAtDepth(browseDestination.depth, scope)
                val songs = library.songsForFolder(scope)
                if (groups.isEmpty() && songs.isNotEmpty()) {
                    subsetStats(songs, library)
                } else {
                    LibraryStatsBarModel(
                        segments = listOfNotNull(
                            "${groups.size} 个文件夹",
                            "${songs.size} 首歌曲",
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

private fun artistGroupCount(
    library: MusicLibrary,
    songs: List<Song>,
    useLibraryPresentationCache: Boolean,
    field: ArtistBrowseSortField,
    direction: SortDirection,
): Int = if (useLibraryPresentationCache) {
    library.artistGroupPresentation(field, direction).groups.size
} else {
    LibraryBrowse.artistGroupPresentation(songs, field, direction).groups.size
}

private fun albumGroupCount(
    library: MusicLibrary,
    songs: List<Song>,
    useLibraryPresentationCache: Boolean,
    field: AlbumBrowseSortField,
    direction: SortDirection,
): Int = if (useLibraryPresentationCache) {
    library.albumGroupPresentation(field, direction).groups.size
} else {
    LibraryBrowse.albumGroupPresentation(songs, field, direction).groups.size
}
private fun folderRootCountLabel(library: MusicLibrary, mode: FolderBrowseMode): String =
    when (mode) {
        FolderBrowseMode.HIERARCHY -> "${library.folderGroupsAtDepth(0).size} 个文件夹"
        FolderBrowseMode.MUSIC_FOLDERS -> "${library.musicFolderGroups().size} 个文件夹"
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

private fun browseScanSegments(library: MusicLibrary, showLastScanTime: Boolean): List<String> =
    when {
        library.isScanning -> listOf("扫描中")
        !library.scanProgressLabel.isNullOrBlank() -> listOf(library.scanProgressLabel!!)
        showLastScanTime && library.lastScanAtMs != null -> listOf(formatLastScan(library.lastScanAtMs))
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
