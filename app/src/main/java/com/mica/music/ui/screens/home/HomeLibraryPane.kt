package com.mica.music.ui.screens.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyListState
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.data.SongSortField
import com.mica.music.ui.components.EmptyStatePresets
import com.mica.music.ui.components.PlaylistSongListPanel
import com.mica.music.ui.components.SongListPanel

@Composable
internal fun HomeLibraryPane(
    library: MusicLibrary,
    currentSongId: String?,
    isPlaying: Boolean,
    onQueueSongClick: (List<Song>, String) -> Unit,
    shouldOpenSettings: Boolean,
    onSongOpenMenu: (Song) -> Unit,
    onPickLibraryFolder: () -> Unit,
    onRequestFullScan: () -> Unit,
    onStartScan: () -> Unit,
    onRequestRescan: () -> Unit,
    onOpenSettings: () -> Unit,
    listState: LazyListState,
    listBottomPadding: Dp,
    selectionMode: Boolean = false,
    selectedSongIds: Set<String> = emptySet(),
    onSelectionToggle: (String) -> Unit = {},
    onMoveSong: (Int, Int) -> Unit = { _, _ -> },
    songListInfoVisibility: SongListInfoVisibility = SongListInfoVisibility(),
) {
    val folderLabel = library.libraryFolderLabel
    when {
        shouldOpenSettings && !library.permissionGranted && !library.hasLibraryFolder() -> {
            EmptyStatePresets.PermissionDeniedOpenSettings(onOpenSettings = onOpenSettings)
        }
        library.isScanning && library.songs.isEmpty() -> {
            EmptyStatePresets.Scanning(progressLabel = library.scanProgressLabel)
        }
        library.isLoadingCachedLibrary && library.songs.isEmpty() -> {
            EmptyStatePresets.Scanning(progressLabel = "正在加载本地曲库...")
        }
        !library.hasScanned && !library.permissionGranted && !library.hasLibraryFolder() -> {
            EmptyStatePresets.InitialLibrarySetup(
                onPickFolderClick = onPickLibraryFolder,
                onScanAllClick = onRequestFullScan,
            )
        }
        !library.hasScanned -> {
            EmptyStatePresets.ReadyToScan(
                folderLabel = folderLabel,
                onScanClick = onStartScan,
            )
        }
        library.hasScanned && library.songs.isEmpty() -> {
            EmptyStatePresets.NoMusicFound(
                folderLabel = folderLabel,
                onRescanClick = onRequestRescan,
                onPickFolderClick = onPickLibraryFolder,
            )
        }
        else -> {
            LibrarySongsPanel(
                songs = library.songs,
                library = library,
                currentSongId = currentSongId,
                isPlaying = isPlaying,
                onSongClick = { songId ->
                    onQueueSongClick(library.songs, songId)
                },
                onSongOpenMenu = onSongOpenMenu,
                emptyMessage = "暂无歌曲",
                listState = listState,
                fastScrollLabels = library.songFastScrollLabels,
                fastScrollSectionTargets = library.songFastScrollSectionTargets,
                listBottomPadding = listBottomPadding,
                selectionMode = selectionMode,
                selectedSongIds = selectedSongIds,
                onSelectionToggle = onSelectionToggle,
                onMoveSong = onMoveSong,
                songListInfoVisibility = songListInfoVisibility,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LibrarySongsPanel(
    songs: List<Song>,
    library: MusicLibrary,
    currentSongId: String?,
    isPlaying: Boolean,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    emptyMessage: String,
    listState: LazyListState,
    fastScrollLabels: List<String>?,
    fastScrollSectionTargets: Map<String, Int>?,
    listBottomPadding: Dp,
    selectionMode: Boolean,
    selectedSongIds: Set<String>,
    onSelectionToggle: (String) -> Unit,
    onMoveSong: (Int, Int) -> Unit,
    songListInfoVisibility: SongListInfoVisibility,
    modifier: Modifier = Modifier,
) {
    if (library.sortField == SongSortField.CUSTOM && !library.customSongOrderLocked && !selectionMode) {
        PlaylistSongListPanel(
            songs = songs,
            customOrder = true,
            library = library,
            currentSongId = currentSongId,
            isPlaying = isPlaying,
            onSongClick = onSongClick,
            onSongOpenMenu = onSongOpenMenu,
            onMoveSong = onMoveSong,
            emptyMessage = emptyMessage,
            sortField = library.sortField,
            sortDirection = library.sortDirection,
            listBottomPadding = listBottomPadding,
            infoVisibility = songListInfoVisibility,
            modifier = modifier,
        )
    } else {
        SongListPanel(
            songs = songs,
            library = library,
            currentSongId = currentSongId,
            isPlaying = isPlaying,
            onSongClick = onSongClick,
            onSongOpenMenu = onSongOpenMenu,
            emptyMessage = emptyMessage,
            listState = listState,
            fastScrollLabels = fastScrollLabels,
            fastScrollSectionTargets = fastScrollSectionTargets,
            listBottomPadding = listBottomPadding,
            selectionMode = selectionMode,
            selectedSongIds = selectedSongIds,
            onSelectionToggle = onSelectionToggle,
            infoVisibility = songListInfoVisibility,
            modifier = modifier,
        )
    }
}
