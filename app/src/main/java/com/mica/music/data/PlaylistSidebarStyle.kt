package com.mica.music.data

/** How playlists are exposed from the home navigation drawer. */
enum class PlaylistSidebarStyle(
    val storageValue: String,
    val settingsLabel: String,
) {
    DEFAULT("default", "默认形式"),
    OVERVIEW("overview", "歌单总览"),
    ;

    companion object {
        fun fromStorage(value: String?): PlaylistSidebarStyle =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}
