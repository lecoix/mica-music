package com.mica.music.ui.screens.home

import androidx.compose.runtime.saveable.Saver

sealed class BrowseDestination {
    data object Root : BrowseDestination()
    data class Artist(val name: String) : BrowseDestination()
    data class Album(val title: String) : BrowseDestination()
    data class Folder(
        val depth: Int,
        val scopePathSegments: List<String> = emptyList(),
    ) : BrowseDestination()
}

data class HomeNavigationIntent(
    val section: HomeSection,
    val browseDestination: BrowseDestination,
)

val BrowseDestinationSaver = Saver<BrowseDestination, List<String>>(
    save = { destination ->
        when (destination) {
            BrowseDestination.Root -> listOf("root", "")
            is BrowseDestination.Artist -> listOf("artist", destination.name)
            is BrowseDestination.Album -> listOf("album", destination.title)
            is BrowseDestination.Folder -> listOf(
                "folder",
                destination.depth.toString(),
            ) + destination.scopePathSegments
        }
    },
    restore = { saved ->
        when (saved.getOrNull(0)) {
            "artist" -> BrowseDestination.Artist(saved.getOrNull(1).orEmpty())
            "album" -> BrowseDestination.Album(saved.getOrNull(1).orEmpty())
            "folder" -> BrowseDestination.Folder(
                depth = saved.getOrNull(1)?.toIntOrNull() ?: 0,
                scopePathSegments = saved.drop(2),
            )
            else -> BrowseDestination.Root
        }
    },
)
