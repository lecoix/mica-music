package com.mica.music.data

object LibraryBrowseDetails {
    data class AlbumDetail(
        val orderedSongs: List<Song>,
        val discSections: List<AlbumDiscSection>,
        val copyright: String?,
    )

    data class AlbumDiscSection(
        val discNumber: Int?,
        val songs: List<Song>,
    )

    data class ArtistAlbumSection(
        val title: String,
        val year: Int,
        val albumArtUri: String?,
        val coverColorArgb: Int,
        val songs: List<Song>,
    )

    fun albumDetail(songs: List<Song>): AlbumDetail {
        val orderedSongs = sortedAlbumSongs(songs)
        return AlbumDetail(
            orderedSongs = orderedSongs,
            discSections = albumDiscSections(orderedSongs),
            copyright = albumCopyrightLine(orderedSongs),
        )
    }

    fun artistAlbumSections(songs: List<Song>): List<ArtistAlbumSection> {
        val buckets = linkedMapOf<String, MutableList<Song>>()
        songs.forEach { song ->
            buckets.getOrPut(song.album.ifBlank { "未知专辑" }) { mutableListOf() }.add(song)
        }
        return buckets.map { (album, albumSongs) ->
            val artworkSong = albumSongs.firstOrNull { !it.albumArtUri.isNullOrBlank() } ?: albumSongs.first()
            ArtistAlbumSection(
                title = album,
                year = albumSongs.map { it.year }.filter { it > 0 }.maxOrNull() ?: 0,
                albumArtUri = artworkSong.albumArtUri,
                coverColorArgb = artworkSong.coverColorArgb,
                songs = albumSongs.sortedWith(
                    compareBy<Song> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                        .thenBy { it.title.lowercase() },
                ),
            )
        }.sortedWith(compareByDescending<ArtistAlbumSection> { it.year > 0 }.thenByDescending { it.year })
    }

    private fun sortedAlbumSongs(songs: List<Song>): List<Song> =
        songs.sortedWith(
            compareBy<Song> { if (it.discNumber > 0) it.discNumber else Int.MAX_VALUE }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { it.title.lowercase() },
        )

    private fun albumDiscSections(songs: List<Song>): List<AlbumDiscSection> {
        if (songs.none { it.discNumber > 0 }) {
            return listOf(AlbumDiscSection(discNumber = null, songs = songs))
        }
        return songs.groupBy { it.discNumber.takeIf { disc -> disc > 0 } }
            .map { (discNumber, discSongs) -> AlbumDiscSection(discNumber, discSongs) }
            .sortedBy { it.discNumber ?: Int.MAX_VALUE }
    }

    private fun albumCopyrightLine(songs: List<Song>): String? =
        songs.firstNotNullOfOrNull { song -> song.copyright.trim().takeIf { it.isNotEmpty() } }
}
