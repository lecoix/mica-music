package com.mica.music.ui.screens.home

import com.mica.music.data.Song

enum class HomeSection {
    Songs,
    Artists,
    Albums,
    Folders,
    Recent,
    Playlist,
    LibraryAnalysis,
    Settings,
}

data class HomePlaybackState(
    val currentSong: Song?,
    val isPlaying: Boolean,
    val positionMs: Int = 0,
    val queue: List<Song>,
    val isBuffering: Boolean = false,
    val playbackSpeed: Float = 1f,
)

data class HomePlaybackActions(
    val syncPlaybackState: () -> Unit,
    val syncPosition: () -> Unit,
    val insertPlayNext: (Song) -> Unit,
    val setQueue: (List<Song>) -> Unit,
    val appendToQueue: (List<Song>) -> Unit,
    val togglePlay: () -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
)
