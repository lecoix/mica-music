package com.mica.music.data

import java.text.Collator
import java.util.Locale

private const val BrowseFallbackColorArgb: Int = 0xFF334455.toInt()

data class BrowseGroup(
    val title: String,
    val subtitle: String,
    val songCount: Int,
    val artist: String = subtitle,
    val year: Int = 0,
    val albumArtUri: String? = null,
    val coverColorArgb: Int = BrowseFallbackColorArgb,
)

data class BrowseGroupPresentation(
    val groups: List<BrowseGroup>,
    val fastScrollIndex: FastScrollIndex?,
)

enum class AlbumBrowseSortField(val storageValue: String, val label: String) {
    TITLE("title", "标题"),
    YEAR("year", "年份"),
    SONG_COUNT("song_count", "歌曲数量"),
    ARTIST("artist", "艺术家"),
    ;

    companion object {
        fun fromStorage(value: String?): AlbumBrowseSortField =
            entries.firstOrNull { it.storageValue == value } ?: TITLE
    }
}

enum class ArtistBrowseSortField(val storageValue: String, val label: String) {
    TITLE("title", "标题"),
    SONG_COUNT("song_count", "歌曲数量"),
    ;

    companion object {
        fun fromStorage(value: String?): ArtistBrowseSortField =
            entries.firstOrNull { it.storageValue == value } ?: TITLE
    }
}

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
            val artworkSong = artworkSong(list)
            BrowseGroup(
                title = artist,
                subtitle = "${list.size} 首",
                songCount = list.size,
                artist = artist,
                year = albumYear(list),
                albumArtUri = artworkSong?.albumArtUri,
                coverColorArgb = artworkSong?.coverColorArgb ?: BrowseFallbackColorArgb,
            )
        }.sortedWith(AlphabeticalText.comparator({ it.title }, collator))
    }

    fun groupByAlbum(songs: List<Song>): List<BrowseGroup> =
        songs.groupBy { it.album.ifBlank { "未知专辑" } }
            .map { (album, list) ->
                val artistSummary = summarizeAlbumArtists(list)
                val artworkSong = artworkSong(list)
                BrowseGroup(
                    title = album,
                    subtitle = artistSummary,
                    songCount = list.size,
                    artist = artistSummary,
                    year = albumYear(list),
                    albumArtUri = artworkSong?.albumArtUri,
                    coverColorArgb = artworkSong?.coverColorArgb ?: BrowseFallbackColorArgb,
                )
            }
            .sortedWith(AlphabeticalText.comparator({ it.title }, collator))

    fun sortArtistGroups(
        groups: List<BrowseGroup>,
        field: ArtistBrowseSortField,
        direction: SortDirection,
    ): List<BrowseGroup> {
        if (field == ArtistBrowseSortField.SONG_COUNT && direction == SortDirection.DESC) {
            return groups.sortedWith(
                compareByDescending<BrowseGroup> { it.songCount }
                    .then(AlphabeticalText.comparator({ it.title }, collator)),
            )
        }
        val sorted = when (field) {
            ArtistBrowseSortField.TITLE -> groups.sortedWith(AlphabeticalText.comparator({ it.title }, collator))
            ArtistBrowseSortField.SONG_COUNT -> groups.sortedWith(
                compareBy<BrowseGroup> { it.songCount }
                    .then(AlphabeticalText.comparator({ it.title }, collator)),
            )
        }
        return if (direction == SortDirection.DESC && field != ArtistBrowseSortField.SONG_COUNT) {
            sorted.reversed()
        } else {
            sorted
        }
    }

    fun sortAlbumGroups(
        groups: List<BrowseGroup>,
        field: AlbumBrowseSortField,
        direction: SortDirection,
    ): List<BrowseGroup> {
        if (field == AlbumBrowseSortField.SONG_COUNT && direction == SortDirection.DESC) {
            return groups.sortedWith(
                compareByDescending<BrowseGroup> { it.songCount }
                    .then(AlphabeticalText.comparator({ it.title }, collator)),
            )
        }
        val sorted = when (field) {
            AlbumBrowseSortField.TITLE -> groups.sortedWith(AlphabeticalText.comparator({ it.title }, collator))
            AlbumBrowseSortField.YEAR -> groups.sortedWith(albumYearComparator(direction))
            AlbumBrowseSortField.SONG_COUNT -> groups.sortedWith(
                compareBy<BrowseGroup> { it.songCount }
                    .then(AlphabeticalText.comparator({ it.title }, collator)),
            )
            AlbumBrowseSortField.ARTIST -> groups.sortedWith(
                AlphabeticalText.comparator<BrowseGroup>({ it.artist }, collator)
                    .then(AlphabeticalText.comparator({ it.title }, collator)),
            )
        }
        return if (direction == SortDirection.DESC && field != AlbumBrowseSortField.YEAR) {
            sorted.reversed()
        } else {
            sorted
        }
    }

    fun artistGroupPresentation(
        songs: List<Song>,
        field: ArtistBrowseSortField,
        direction: SortDirection,
    ): BrowseGroupPresentation {
        val groups = sortArtistGroups(groupByArtist(songs), field, direction)
        return BrowseGroupPresentation(
            groups = groups,
            fastScrollIndex = fastScrollIndexFor(groups, artistFastScrollLabels(groups, field)),
        )
    }

    fun albumGroupPresentation(
        songs: List<Song>,
        field: AlbumBrowseSortField,
        direction: SortDirection,
    ): BrowseGroupPresentation {
        val groups = sortAlbumGroups(groupByAlbum(songs), field, direction)
        return BrowseGroupPresentation(
            groups = groups,
            fastScrollIndex = fastScrollIndexFor(groups, albumFastScrollLabels(groups, field)),
        )
    }

    private fun artistFastScrollLabels(groups: List<BrowseGroup>, field: ArtistBrowseSortField): List<String>? =
        when (field) {
            ArtistBrowseSortField.TITLE -> groups.map { it.title }
            ArtistBrowseSortField.SONG_COUNT -> null
        }

    private fun albumFastScrollLabels(groups: List<BrowseGroup>, field: AlbumBrowseSortField): List<String>? =
        when (field) {
            AlbumBrowseSortField.TITLE -> groups.map { it.title }
            AlbumBrowseSortField.ARTIST -> groups.map { it.artist }
            AlbumBrowseSortField.YEAR,
            AlbumBrowseSortField.SONG_COUNT,
            -> null
        }

    private fun fastScrollIndexFor(groups: List<BrowseGroup>, labels: List<String>?): FastScrollIndex? {
        if (labels == null) return null
        return FastScrollIndex(
            labels = labels,
            sectionTargets = LibraryFastScrollIndex.sectionTargets(labels),
        )
    }

    private fun summarizeAlbumArtists(songs: List<Song>): String {
        val names = linkedSetOf<String>()
        songs.forEach { song ->
            ArtistNames.split(song.artist).forEach { names.add(it) }
        }
        val sorted = names.sortedWith { a, b ->
            val keyCompare = AlphabeticalText.sortKey(a).compareTo(AlphabeticalText.sortKey(b))
            if (keyCompare != 0) keyCompare else collator.compare(a, b)
        }
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
            AlphabeticalText.comparator<FolderBrowseGroup>({ it.title }, collator)
                .then(AlphabeticalText.comparator({ it.pathSegments.dropLast(1).joinToString("/") }, collator)),
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

    private fun artworkSong(songs: List<Song>): Song? =
        songs.firstOrNull { !it.albumArtUri.isNullOrBlank() } ?: songs.firstOrNull()

    private fun albumYear(songs: List<Song>): Int =
        songs.mapNotNull { it.year.takeIf { year -> year > 0 } }.minOrNull() ?: 0

    private fun albumYearComparator(direction: SortDirection): Comparator<BrowseGroup> =
        Comparator { a, b ->
            val aUnknown = a.year <= 0
            val bUnknown = b.year <= 0
            when {
                aUnknown && bUnknown -> compareText(a.title, b.title)
                aUnknown -> 1
                bUnknown -> -1
                direction == SortDirection.DESC && a.year != b.year -> b.year.compareTo(a.year)
                a.year != b.year -> a.year.compareTo(b.year)
                else -> compareText(a.title, b.title)
            }
        }

    private fun compareText(a: String, b: String): Int {
        val keyCompare = AlphabeticalText.sortKey(a).compareTo(AlphabeticalText.sortKey(b))
        return if (keyCompare != 0) keyCompare else collator.compare(a, b)
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
