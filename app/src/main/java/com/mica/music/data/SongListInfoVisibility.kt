package com.mica.music.data

data class SongListInfoVisibility(
    val showSongArtist: Boolean = true,
    val showSongAlbum: Boolean = true,
    val showSongPlayCount: Boolean = true,
    val showSongDuration: Boolean = false,
    val showSongCount: Boolean = true,
    val showLibrarySize: Boolean = true,
    val showSortOrder: Boolean = true,
    val showLastScanTime: Boolean = true,
    val showCustomText: Boolean = false,
    val customText: String = "",
    val trailingInfo: SongTrailingInfo = SongTrailingInfo.FORMAT,
)

enum class SongTrailingInfo(val storageValue: Int, val label: String) {
    PLAY_COUNT(0, "播放次数"),
    FORMAT(1, "格式"),
    DURATION(2, "时长"),
    NONE(3, "不显示");

    companion object {
        fun fromStorage(value: Int): SongTrailingInfo = entries.firstOrNull { it.storageValue == value } ?: FORMAT
    }
}
