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
    val releaseDate: String = "",
    val albumArtUri: String? = null,
    val coverColorArgb: Int = BrowseFallbackColorArgb,
    val key: String = title,
)

/** Stable identity for an album browse group; title remains display-only. */
data class AlbumBrowseKey(
    val title: String,
    val albumArtist: String,
    val legacyTitleOnly: Boolean = false,
    val fallbackFolderPath: String? = null,
) {
    val storageKey: String
        get() = encodePart(title) + encodePart(albumArtist) +
            fallbackFolderPath?.let(::encodePart).orEmpty()

    fun matches(song: Song): Boolean =
        song.album.trim().ifBlank { UNKNOWN_ALBUM } == title &&
            (legacyTitleOnly || fromSong(song) == this || matchesLegacyArtistFallback(song))

    private fun matchesLegacyArtistFallback(song: Song): Boolean =
        fallbackFolderPath == null &&
            albumArtist == song.albumArtist.trim().ifBlank { song.artist.trim() }

    companion object {
        fun fromSong(song: Song): AlbumBrowseKey {
            val album = song.album.trim()
            val albumArtist = song.albumArtist.trim()
            val folder = normalizeFolderPath(song.folderPath)
            return AlbumBrowseKey(
                title = album.ifBlank { UNKNOWN_ALBUM },
                albumArtist = when {
                    albumArtist.isNotEmpty() -> albumArtist
                    album.isBlank() || folder.isEmpty() -> song.artist.trim()
                    else -> ""
                },
                fallbackFolderPath = folder.takeIf {
                    album.isNotEmpty() && albumArtist.isEmpty() && it.isNotEmpty()
                },
            )
        }

        fun fromStorageKey(key: String): AlbumBrowseKey? {
            val first = decodePart(key, 0) ?: return null
            val second = decodePart(key, first.nextIndex) ?: return null
            if (second.nextIndex == key.length) return AlbumBrowseKey(first.value, second.value)
            val third = decodePart(key, second.nextIndex) ?: return null
            if (third.nextIndex != key.length) return null
            return AlbumBrowseKey(
                title = first.value,
                albumArtist = second.value,
                fallbackFolderPath = third.value,
            )
        }

        fun legacyTitleOnly(title: String): AlbumBrowseKey = AlbumBrowseKey(
            title = title,
            albumArtist = "",
            legacyTitleOnly = true,
        )

        private fun encodePart(value: String): String = "${value.length}:$value"

        private fun normalizeFolderPath(value: String): String =
            value.replace('\\', '/').trim().trim('/')

        private fun decodePart(value: String, start: Int): DecodedPart? {
            val separator = value.indexOf(':', startIndex = start)
            if (separator <= start) return null
            val length = value.substring(start, separator).toIntOrNull() ?: return null
            if (length < 0) return null
            val partStart = separator + 1
            val partEnd = partStart + length
            if (partEnd > value.length) return null
            return DecodedPart(value.substring(partStart, partEnd), partEnd)
        }

        private data class DecodedPart(val value: String, val nextIndex: Int)
    }
}

private const val UNKNOWN_ALBUM = "未知专辑"

data class BrowseGroupPresentation(
    val groups: List<BrowseGroup>,
    val fastScrollIndex: FastScrollIndex?,
)

enum class AlbumBrowseSortField(val storageValue: String, val label: String) {
    TITLE("title", "标题"),
    YEAR("year", "日期"),
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

internal data class FolderBrowseIndexedGroup(
    val path: String,
    val songCount: Int,
)

/** Revision-scoped folder projection. It retains songs and path keys, never lyrics payloads. */
internal class FolderBrowseIndex internal constructor(
    internal val maxDepth: Int,
    internal val groupsByDepth: Map<Int, List<FolderBrowseIndexedGroup>>,
    internal val directSongsByPath: Map<String, List<Song>>,
    internal val descendantSongsByPath: Map<String, List<Song>>,
)

internal data class LibrarySearchEntry(
    val song: Song,
    val titleLower: String,
    val artistLowerRoot: String,
    val artistPartsLowerRoot: List<String>,
    val albumLower: String,
    val fileNameLower: String,
)

internal class LibrarySearchIndex internal constructor(
    internal val entries: List<LibrarySearchEntry>,
)

enum class FolderBrowseMode(val storageValue: String, val label: String) {
    HIERARCHY("hierarchy", "层级浏览"),
    MUSIC_FOLDERS("music_folders", "扁平浏览"),
    ;

    companion object {
        fun fromStorage(value: String?): FolderBrowseMode =
            entries.firstOrNull { it.storageValue == value } ?: HIERARCHY
    }
}

object LibraryBrowse {

    private val collator: Collator = Collator.getInstance(Locale.CHINA).apply {
        strength = Collator.PRIMARY
    }

    fun search(songs: List<Song>, query: String): List<Song> {
        val locale = Locale.getDefault()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return search(searchIndex(songs, locale), q.lowercase(locale))
    }

    internal fun searchIndex(songs: List<Song>, locale: Locale): LibrarySearchIndex =
        LibrarySearchIndex(
            entries = songs.map { song ->
                LibrarySearchEntry(
                    song = song,
                    titleLower = song.title.lowercase(locale),
                    artistLowerRoot = song.artist.lowercase(Locale.ROOT),
                    artistPartsLowerRoot = ArtistNames.split(song.artist)
                        .map { it.lowercase(Locale.ROOT) },
                    albumLower = song.album.lowercase(locale),
                    fileNameLower = song.fileName.lowercase(locale),
                )
            },
        )

    internal fun search(index: LibrarySearchIndex, queryLower: String): List<Song> {
        if (queryLower.isEmpty()) return emptyList()
        val results = ArrayList<Song>()
        index.entries.forEach { entry ->
            if (
                entry.titleLower.contains(queryLower) ||
                entry.artistLowerRoot.contains(queryLower) ||
                entry.artistPartsLowerRoot.any { it.contains(queryLower) } ||
                entry.albumLower.contains(queryLower) ||
                entry.fileNameLower.contains(queryLower)
            ) {
                results += entry.song
            }
        }
        return results
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
            val releaseDate = ReleaseDates.earliestFullDate(list)
            BrowseGroup(
                title = artist,
                subtitle = "${list.size} 首",
                songCount = list.size,
                artist = artist,
                year = ReleaseDates.aggregateYear(list, releaseDate),
                releaseDate = releaseDate,
                albumArtUri = artworkSong?.albumArtUri,
                coverColorArgb = artworkSong?.coverColorArgb ?: BrowseFallbackColorArgb,
            )
        }.sortedWith(AlphabeticalText.comparator({ it.title }, collator))
    }

    fun groupByAlbum(songs: List<Song>): List<BrowseGroup> =
        songs.groupBy(AlbumBrowseKey::fromSong)
            .map { (albumKey, list) ->
                val artistSummary = summarizeAlbumArtists(list)
                val artworkSong = artworkSong(list)
                val releaseDate = ReleaseDates.earliestFullDate(list)
                BrowseGroup(
                    title = albumKey.title,
                    subtitle = artistSummary,
                    songCount = list.size,
                    artist = artistSummary,
                    year = ReleaseDates.aggregateYear(list, releaseDate),
                    releaseDate = releaseDate,
                    albumArtUri = artworkSong?.albumArtUri,
                    coverColorArgb = artworkSong?.coverColorArgb ?: BrowseFallbackColorArgb,
                    key = albumKey.storageKey,
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
        return artistGroupPresentationFromGroups(groupByArtist(songs), field, direction)
    }

    fun artistGroupPresentationFromGroups(
        baseGroups: List<BrowseGroup>,
        field: ArtistBrowseSortField,
        direction: SortDirection,
    ): BrowseGroupPresentation {
        val groups = sortArtistGroups(baseGroups, field, direction)
        return BrowseGroupPresentation(
            groups = groups,
            fastScrollIndex = fastScrollIndexFor(groups, artistFastScrollLabels(groups, field)),
        )
    }

    fun artistGroupPresentationFromPersistedOrder(
        groups: List<BrowseGroup>,
        field: ArtistBrowseSortField,
        sectionTargets: Map<String, Int>?,
    ): BrowseGroupPresentation = BrowseGroupPresentation(
        groups = groups,
        fastScrollIndex = persistedFastScrollIndex(artistFastScrollLabels(groups, field), sectionTargets),
    )

    fun albumGroupPresentation(
        songs: List<Song>,
        field: AlbumBrowseSortField,
        direction: SortDirection,
    ): BrowseGroupPresentation {
        return albumGroupPresentationFromGroups(groupByAlbum(songs), field, direction)
    }

    fun albumGroupPresentationFromGroups(
        baseGroups: List<BrowseGroup>,
        field: AlbumBrowseSortField,
        direction: SortDirection,
    ): BrowseGroupPresentation {
        val groups = sortAlbumGroups(baseGroups, field, direction)
        return BrowseGroupPresentation(
            groups = groups,
            fastScrollIndex = fastScrollIndexFor(groups, albumFastScrollLabels(groups, field)),
        )
    }

    fun albumGroupPresentationFromPersistedOrder(
        groups: List<BrowseGroup>,
        field: AlbumBrowseSortField,
        sectionTargets: Map<String, Int>?,
    ): BrowseGroupPresentation = BrowseGroupPresentation(
        groups = groups,
        fastScrollIndex = persistedFastScrollIndex(albumFastScrollLabels(groups, field), sectionTargets),
    )

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

    private fun persistedFastScrollIndex(
        labels: List<String>?,
        sectionTargets: Map<String, Int>?,
    ): FastScrollIndex? = labels?.let { values ->
        sectionTargets?.let { FastScrollIndex(values, it) }
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

    fun songsForAlbum(songs: List<Song>, albumKey: AlbumBrowseKey): List<Song> =
        songs.filter { albumKey.matches(it) }

    fun folderGroups(songs: List<Song>, parentPathSegments: List<String>): List<FolderBrowseGroup> {
        val parent = parentPathSegments.normalizedFolderSegments()
        return folderGroupsAtDepth(folderBrowseIndex(songs), parent.size, parent)
    }

    fun folderGroupsAtDepth(
        songs: List<Song>,
        depth: Int,
        scopePathSegments: List<String> = emptyList(),
    ): List<FolderBrowseGroup> = folderGroupsAtDepth(
        folderBrowseIndex(songs),
        depth,
        scopePathSegments,
    )

    /**
     * Returns only exact directories that directly contain at least one song.
     *
     * Parent directories that merely contain music in descendants are intentionally omitted.
     * Counts are direct-song counts and no song or lyrics payload is retained by the result.
     */
    fun musicFolderGroups(songs: List<Song>): List<FolderBrowseGroup> =
        musicFolderGroups(folderBrowseIndex(songs))

    fun maxFolderDepth(songs: List<Song>): Int = folderBrowseIndex(songs).maxDepth

    fun songsForFolder(songs: List<Song>, pathSegments: List<String>): List<Song> =
        songsForFolder(folderBrowseIndex(songs), pathSegments)

    fun songsInFolder(songs: List<Song>, pathSegments: List<String>): List<Song> =
        songsInFolder(folderBrowseIndex(songs), pathSegments)

    /** Builds all folder query projections once for the current library revision. */
    internal fun folderBrowseIndex(songs: List<Song>): FolderBrowseIndex {
        val groupBuckets = linkedMapOf<Int, LinkedHashMap<String, Int>>()
        val directSongsByPath = linkedMapOf<String, MutableList<Song>>()
        val descendantSongsByPath = linkedMapOf<String, MutableList<Song>>()
        var maxDepth = 0

        songs.forEach { song ->
            val segments = song.folderBrowseSegments()
            if (segments.isEmpty()) return@forEach
            maxDepth = maxOf(maxDepth, segments.size)

            val prefixes = ArrayList<String>(segments.size)
            var path = ""
            segments.forEach { segment ->
                path = if (path.isEmpty()) segment else "$path/$segment"
                prefixes += path
            }

            directSongsByPath.getOrPut(prefixes.last()) { mutableListOf() }.add(song)
            prefixes.forEach { prefix ->
                descendantSongsByPath.getOrPut(prefix) { mutableListOf() }.add(song)
            }

            prefixes.forEachIndexed { targetDepth, childPath ->
                val bucket = groupBuckets.getOrPut(targetDepth) { linkedMapOf() }
                bucket[childPath] = bucket.getOrDefault(childPath, 0) + 1
            }
        }

        return FolderBrowseIndex(
            maxDepth = maxDepth,
            groupsByDepth = groupBuckets.mapValues { (_, bucket) ->
                bucket.map { (path, songCount) -> FolderBrowseIndexedGroup(path, songCount) }
            },
            directSongsByPath = directSongsByPath.mapValues { (_, songsAtPath) -> songsAtPath.toList() },
            descendantSongsByPath = descendantSongsByPath.mapValues { (_, songsUnderPath) -> songsUnderPath.toList() },
        )
    }

    internal fun folderGroupsAtDepth(
        index: FolderBrowseIndex,
        depth: Int,
        scopePathSegments: List<String> = emptyList(),
    ): List<FolderBrowseGroup> {
        val targetDepth = depth.coerceAtLeast(0)
        val scope = scopePathSegments.normalizedFolderSegments()
        if (scope.isNotEmpty() && targetDepth < scope.size) return emptyList()
        val scopePath = scope.joinToString("/")
        val scopePrefix = scopePath.takeIf { it.isNotEmpty() }?.let { "$it/" }
        val groups = index.groupsByDepth[targetDepth]
            ?.filter { group -> scopePrefix == null || group.path.startsWith(scopePrefix) }
            ?: return emptyList()
        return groups.map { group ->
            val pathSegments = group.path.folderSegments()
            val parentLabel = pathSegments.dropLast(1).joinToString(" / ")
            FolderBrowseGroup(
                title = pathSegments.lastOrNull().orEmpty(),
                subtitle = listOfNotNull(
                    parentLabel.takeIf { it.isNotBlank() },
                    "${group.songCount} 首",
                ).joinToString(" · "),
                songCount = group.songCount,
                pathSegments = pathSegments,
            )
        }.sortedWith(
            AlphabeticalText.comparator<FolderBrowseGroup>({ it.title }, collator)
                .then(AlphabeticalText.comparator({ it.pathSegments.dropLast(1).joinToString("/") }, collator)),
        )
    }

    internal fun musicFolderGroups(index: FolderBrowseIndex): List<FolderBrowseGroup> =
        index.directSongsByPath.map { (folderPath, songsAtPath) ->
            val pathSegments = folderPath.folderSegments()
            FolderBrowseGroup(
                title = pathSegments.lastOrNull().orEmpty(),
                subtitle = "${songsAtPath.size} 首",
                songCount = songsAtPath.size,
                pathSegments = pathSegments,
            )
        }.sortedWith(folderGroupComparator())

    internal fun songsForFolder(index: FolderBrowseIndex, pathSegments: List<String>): List<Song> {
        val folder = pathSegments.normalizedFolderSegments()
        if (folder.isEmpty()) return emptyList()
        return index.descendantSongsByPath[folder.joinToString("/")].orEmpty()
    }

    internal fun songsInFolder(index: FolderBrowseIndex, pathSegments: List<String>): List<Song> {
        val folder = pathSegments.normalizedFolderSegments()
        if (folder.isEmpty()) return emptyList()
        return index.directSongsByPath[folder.joinToString("/")].orEmpty()
    }

    fun recentSongs(songs: List<Song>, recentIds: List<String>): List<Song> {
        if (recentIds.isEmpty()) return emptyList()
        val byId = songs.associateBy { it.id }
        return recentIds.mapNotNull { byId[it] }
    }

    private fun artworkSong(songs: List<Song>): Song? =
        songs.firstOrNull { !it.albumArtUri.isNullOrBlank() } ?: songs.firstOrNull()

    private fun albumYearComparator(direction: SortDirection): Comparator<BrowseGroup> =
        Comparator { a, b ->
            ReleaseDates.compare(
                leftYear = a.year,
                leftFullDate = a.releaseDate,
                rightYear = b.year,
                rightFullDate = b.releaseDate,
                direction = direction,
            ).takeIf { it != 0 } ?: compareText(a.title, b.title)
        }

    private fun compareText(a: String, b: String): Int {
        val keyCompare = AlphabeticalText.sortKey(a).compareTo(AlphabeticalText.sortKey(b))
        return if (keyCompare != 0) keyCompare else collator.compare(a, b)
    }

    private fun folderGroupComparator(): Comparator<FolderBrowseGroup> =
        AlphabeticalText.comparator<FolderBrowseGroup>({ it.title }, collator)
            .then(AlphabeticalText.comparator({ it.pathSegments.dropLast(1).joinToString("/") }, collator))

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

    private fun List<String>.endsWith(suffix: List<String>): Boolean {
        if (suffix.size > size) return false
        val offset = size - suffix.size
        return suffix.indices.all { index -> this[offset + index] == suffix[index] }
    }
}
