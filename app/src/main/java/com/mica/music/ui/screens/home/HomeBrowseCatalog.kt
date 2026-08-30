package com.mica.music.ui.screens.home

import com.mica.music.data.LibraryBrowse
import com.mica.music.data.Song

internal fun mergedBrowseSongs(
    localSongs: List<Song>,
    remoteSongs: List<Song>,
): List<Song> {
    if (remoteSongs.isEmpty()) return localSongs
    return sequenceOf(localSongs.asSequence(), remoteSongs.asSequence())
        .flatten()
        .distinctBy { it.id }
        .toList()
}

internal fun hasRemoteBrowseContent(
    section: HomeSection,
    destination: BrowseDestination,
    remoteSongs: List<Song>,
): Boolean = when {
    remoteSongs.isEmpty() -> false
    section == HomeSection.Artists && destination == BrowseDestination.Root -> true
    section == HomeSection.Albums && destination == BrowseDestination.Root -> true
    section == HomeSection.Artists && destination is BrowseDestination.Artist ->
        LibraryBrowse.songsForArtist(remoteSongs, destination.name).isNotEmpty()
    section == HomeSection.Albums && destination is BrowseDestination.Album ->
        LibraryBrowse.songsForAlbum(remoteSongs, destination.key).isNotEmpty()
    else -> false
}