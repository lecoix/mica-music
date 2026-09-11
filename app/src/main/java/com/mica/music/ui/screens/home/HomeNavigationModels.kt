package com.mica.music.ui.screens.home

import com.mica.music.data.AlbumBrowseKey

sealed class BrowseDestination {
    data object Root : BrowseDestination()
    data class Artist(val name: String) : BrowseDestination()
    data class Album(val key: AlbumBrowseKey) : BrowseDestination() {
        constructor(title: String) : this(AlbumBrowseKey.legacyTitleOnly(title))

        val title: String
            get() = key.title
    }
    data class Folder(
        val depth: Int,
        val scopePathSegments: List<String> = emptyList(),
    ) : BrowseDestination()
}

data class HomeNavigationIntent(
    val section: HomeSection,
    val browseDestination: BrowseDestination,
)
