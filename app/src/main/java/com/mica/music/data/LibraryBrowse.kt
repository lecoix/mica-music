package com.mica.music.data

import java.text.Collator
import java.util.Locale

data class BrowseGroup(
    val title: String,
    val subtitle: String,
    val songCount: Int,
)

object LibraryBrowse {

    private val collator: Collator = Collator.getInstance(Locale.CHINA).apply {
        strength = Collator.PRIMARY
    }

    fun search(songs: List<Song>, query: String): List<Song> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val lower = q.lowercase(Locale.getDefault())
        return songs.filter { song ->
            song.title.lowercase(Locale.getDefault()).contains(lower) ||
                ArtistNames.matchesSearch(song.artist, lower) ||
                song.album.lowercase(Locale.getDefault()).contains(lower) ||
                song.fileName.lowercase(Locale.getDefault()).contains(lower)
        }
    }

    fun groupByArtist(songs: List<Song>): List<BrowseGroup> {
        val buckets = linkedMapOf<String, MutableList<Song>>()
        songs.forEach { song ->
            ArtistNames.split(song.artist).forEach { name ->
                buckets.getOrPut(name) { mutableListOf() }.add(song)
            }
        }
        return buckets.map { (artist, list) ->
            BrowseGroup(
                title = artist,
                subtitle = "${list.size} 首",
                songCount = list.size,
            )
        }.sortedWith(compareBy(collator) { it.title })
    }

    fun groupByAlbum(songs: List<Song>): List<BrowseGroup> =
        songs.groupBy { it.album.ifBlank { "未知专辑" } }
            .map { (album, list) ->
                BrowseGroup(
                    title = album,
                    subtitle = summarizeAlbumArtists(list),
                    songCount = list.size,
                )
            }
            .sortedWith { a, b -> collator.compare(a.title, b.title) }

    /** 汇总专辑内各曲目的艺术家（一曲一艺术家也可不同） */
    private fun summarizeAlbumArtists(songs: List<Song>): String {
        val names = linkedSetOf<String>()
        songs.forEach { song ->
            ArtistNames.split(song.artist).forEach { names.add(it) }
        }
        val sorted = names.sortedWith { a, b -> collator.compare(a, b) }
        return when {
            sorted.isEmpty() -> "未知艺术家"
            sorted.size == 1 -> sorted.first()
            sorted.size <= 4 -> sorted.joinToString(" / ")
            else -> "${sorted.first()} 等 ${sorted.size} 位艺术家"
        }
    }

    fun songsForArtist(songs: List<Song>, artist: String): List<Song> =
        songs.filter { ArtistNames.contains(it.artist, artist) }

    fun songsForAlbum(songs: List<Song>, album: String): List<Song> =
        songs.filter { (it.album.ifBlank { "未知专辑" }) == album }

    fun recentSongs(songs: List<Song>, recentIds: List<String>): List<Song> {
        if (recentIds.isEmpty()) return emptyList()
        val byId = songs.associateBy { it.id }
        return recentIds.mapNotNull { byId[it] }
    }
}
