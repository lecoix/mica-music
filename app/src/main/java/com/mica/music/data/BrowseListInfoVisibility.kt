package com.mica.music.data

data class BrowseListInfoVisibility(
    val showArtistCount: Boolean = true,
    val showArtistSortOrder: Boolean = true,
    val showArtistGridColumns: Boolean = true,
    val showArtistLastScanTime: Boolean = true,
    val showArtistCustomText: Boolean = false,
    val artistCustomText: String = "",
    val showAlbumCount: Boolean = true,
    val showAlbumSortOrder: Boolean = true,
    val showAlbumGridColumns: Boolean = true,
    val showAlbumLastScanTime: Boolean = true,
    val showAlbumCustomText: Boolean = false,
    val albumCustomText: String = "",
    val showAlbumSubtitleArtist: Boolean = true,
    val showAlbumSubtitleReleaseDate: Boolean = true,
    val showAlbumSubtitleSongCount: Boolean = true,
)
