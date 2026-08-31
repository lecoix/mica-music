package com.mica.music.data.remote.navidrome

data class NavidromeTrack(
    val remoteId: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val albumId: String = "",
    val artistId: String = "",
    val durationSec: Int = 0,
    val contentType: String = "",
    val suffix: String = "",
    val coverArtId: String = "",
    val bitRateKbps: Int = 0,
    val samplingRateHz: Int = 0,
    val bitDepth: Int = 0,
    val channelCount: Int = 0,
    val sizeBytes: Long = 0L,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val serverPath: String = "",
) {
    init {
        require(remoteId.isNotBlank()) { "Navidrome remoteId must not be blank" }
    }
}

data class NavidromeSongPage(
    val songs: List<NavidromeTrack>,
    val rawCount: Int,
) {
    init {
        require(rawCount >= 0) { "rawCount must not be negative" }
    }
}

interface NavidromeCatalogApi {
    suspend fun searchAllSongsPage(offset: Int, count: Int): NavidromeSongPage
    suspend fun albumIdsPage(offset: Int, count: Int): List<String>
    suspend fun albumSongs(albumId: String): List<NavidromeTrack>
}
