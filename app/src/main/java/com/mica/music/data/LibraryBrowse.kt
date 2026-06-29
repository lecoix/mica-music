package com.mica.music.data

import java.text.Collator
import java.util.Locale

data class BrowseGroup(
    val title: String,
    val subtitle: String,
    val songCount: Int,
)

data class FolderBrowseGroup(
    val title: String,
    val subtitle: String,
    val songCount: Int,
    val pathSegments: List<String>,
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

    fun folderGroups(songs: List<Song>, parentPathSegments: List<String>): List<FolderBrowseGroup> {
        val parent = parentPathSegments.normalizedFolderSegments()
        return folderGroupsAtDepth(songs, parent.size, parent)
    }

    fun folderGroupsAtDepth(
        songs: List<Song>,
        depth: Int,
        scopePathSegments: List<String> = emptyList(),
    ): List<FolderBrowseGroup> {
        val targetDepth = depth.coerceAtLeast(0)
        val scope = scopePathSegments.normalizedFolderSegments()
        if (scope.isNotEmpty() && targetDepth < scope.size) return emptyList()
        val buckets = linkedMapOf<String, MutableList<Song>>()
        songs.forEach { song ->
            val segments = song.folderBrowseSegments()
            if (segments.size <= targetDepth || !segments.startsWith(scope)) return@forEach
            val folderPath = segments.take(targetDepth + 1).joinToString("/")
            buckets.getOrPut(folderPath) { mutableListOf() }.add(song)
        }
        return buckets.map { (folderPath, list) ->
            val pathSegments = folderPath.folderSegments()
            val parentLabel = pathSegments.dropLast(1).joinToString(" / ")
            FolderBrowseGroup(
                title = pathSegments.lastOrNull().orEmpty(),
                subtitle = listOfNotNull(
                    parentLabel.takeIf { it.isNotBlank() },
                    "${list.size} 首",
                ).joinToString(" · "),
                songCount = list.size,
                pathSegments = pathSegments,
            )
        }.sortedWith(
            compareBy<FolderBrowseGroup, String>(collator) { it.title }
                .then(compareBy(collator) { it.pathSegments.dropLast(1).joinToString("/") }),
        )
    }

    fun maxFolderDepth(songs: List<Song>): Int {
        return songs.maxOfOrNull { it.folderBrowseSegments().size } ?: 0
    }

    fun songsForFolder(songs: List<Song>, pathSegments: List<String>): List<Song> {
        val folder = pathSegments.normalizedFolderSegments()
        return songs.filter { song ->
            val segments = song.folderBrowseSegments()
            folder.isNotEmpty() && segments.startsWith(folder)
        }
    }

    fun songsInFolder(songs: List<Song>, pathSegments: List<String>): List<Song> {
        val folder = pathSegments.normalizedFolderSegments()
        return songs.filter { song ->
            val segments = song.folderBrowseSegments()
            folder.isNotEmpty() && segments == folder
        }
    }

    fun recentSongs(songs: List<Song>, recentIds: List<String>): List<Song> {
        if (recentIds.isEmpty()) return emptyList()
        val byId = songs.associateBy { it.id }
        return recentIds.mapNotNull { byId[it] }
    }

    private fun String.folderSegments(): List<String> =
        split('/', '\\')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun Song.folderBrowseSegments(): List<String> {
        val stored = folderPath.folderSegments()
        val fileParent = filePath
            .trim()
            .replace('\\', '/')
            .substringBeforeLast('/', "")
            .folderSegments()
            .withoutAndroidStoragePrefix()
        return when {
            fileParent.isEmpty() -> stored
            stored.isEmpty() -> fileParent
            fileParent.size > stored.size && fileParent.endsWith(stored).not() -> fileParent
            fileParent.size > stored.size -> fileParent
            else -> stored
        }
    }

    private fun List<String>.withoutAndroidStoragePrefix(): List<String> {
        val publicRoot = indexOfFirst {
            it.equals("Music", ignoreCase = true) ||
                it.equals("Download", ignoreCase = true) ||
                it.equals("Downloads", ignoreCase = true) ||
                it.equals("Podcasts", ignoreCase = true) ||
                it.equals("Audiobooks", ignoreCase = true) ||
                it.equals("Recordings", ignoreCase = true)
        }
        if (publicRoot >= 0) return drop(publicRoot)
        val storageRoot = indexOfLast {
            it == "0" || it.equals("sdcard", ignoreCase = true)
        }
        return if (storageRoot >= 0 && storageRoot < lastIndex) drop(storageRoot + 1) else this
    }

    private fun List<String>.normalizedFolderSegments(): List<String> =
        map { it.trim() }.filter { it.isNotEmpty() }

    private fun List<String>.startsWith(prefix: List<String>): Boolean {
        if (prefix.size > size) return false
        return prefix.indices.all { index -> this[index] == prefix[index] }
    }

    private fun List<String>.endsWith(suffix: List<String>): Boolean {
        if (suffix.size > size) return false
        val offset = size - suffix.size
        return suffix.indices.all { index -> this[offset + index] == suffix[index] }
    }
}
