package com.mica.music.ui.screens.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyListState
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.ui.components.EmptyStatePresets
import com.mica.music.ui.components.SongListPanel

@Composable
internal fun HomeLibraryPane(
    library: MusicLibrary,
    currentSongId: String?,
    isPlaying: Boolean,
    onQueueSongs: (List<Song>) -> Unit,
    shouldOpenSettings: Boolean,
    onSongClick: (String) -> Unit,
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
            SongListPanel(
                songs = library.songs,
                library = library,
                currentSongId = currentSongId,
                isPlaying = isPlaying,
                onSongClick = { songId ->
                    onQueueSongs(library.songs)
                    onSongClick(songId)
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
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
