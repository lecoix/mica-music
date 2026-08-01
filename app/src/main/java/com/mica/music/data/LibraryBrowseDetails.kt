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
        val key: AlbumBrowseKey,
        val title: String,
        val year: Int,
        val releaseDate: String,
        val albumArtUri: String?,
        val coverColorArgb: Int,
        val songs: List<Song>,
        val discSections: List<AlbumDiscSection>,
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
        val buckets = linkedMapOf<AlbumBrowseKey, MutableList<Song>>()
        songs.forEach { song ->
            buckets.getOrPut(AlbumBrowseKey.fromSong(song)) { mutableListOf() }.add(song)
        }
        return buckets.map { (albumKey, albumSongs) ->
            val orderedSongs = sortedAlbumSongs(albumSongs)
            val artworkSong = orderedSongs.firstOrNull { !it.albumArtUri.isNullOrBlank() } ?: orderedSongs.first()
            val releaseDate = ReleaseDates.earliestFullDate(orderedSongs)
            ArtistAlbumSection(
                key = albumKey,
                title = albumKey.title,
                year = ReleaseDates.aggregateYear(orderedSongs, releaseDate),
                releaseDate = releaseDate,
                albumArtUri = artworkSong.albumArtUri,
                coverColorArgb = artworkSong.coverColorArgb,
                songs = orderedSongs,
                discSections = albumDiscSections(orderedSongs),
            )
        }.sortedWith(
            Comparator { left, right ->
                ReleaseDates.compare(
                    left.year,
                    left.releaseDate,
                    right.year,
                    right.releaseDate,
                    SortDirection.DESC,
                ).takeIf { it != 0 } ?: left.title.compareTo(right.title)
            },
        )
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
