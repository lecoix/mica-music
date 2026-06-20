package com.mica.music.data

data class SongListInfoVisibility(
    val showSongCount: Boolean = true,
    val showLibrarySize: Boolean = true,
    val showSortOrder: Boolean = true,
    val showLastScanTime: Boolean = true,
    val showCustomText: Boolean = false,
    val customText: String = "",
)
