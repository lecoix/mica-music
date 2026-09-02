package com.mica.music.data.library

import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import com.mica.music.data.AlbumBrowseKey
import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.ArtistNames
import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.ArtistSplitConfig
import com.mica.music.data.BrowseGroup
import com.mica.music.data.BrowseGroupPresentation
import com.mica.music.data.FolderBrowseGroup
import com.mica.music.data.FolderBrowseIndex
import com.mica.music.data.LibraryBrowse
import com.mica.music.data.LibrarySearchIndex
import com.mica.music.data.PlayHistoryStore
import com.mica.music.data.Song
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.cacheKey
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.util.DiagnosticLog
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.coroutines.withContext

private const val SEARCH_QUERY_CACHE_MAX_ENTRIES = 8

private data class SearchQueryCacheKey(
    val catalogRevision: Long,
    val artistSplitRevision: Long,
    val artistConfigKey: String,
    val localeTag: String,
    val queryLower: String,
)

internal class LibraryBrowseCoordinator(
    private val backing: MusicLibraryBacking,
) {
    private val artistSplitRevisionState = mutableLongStateOf(0L)
    private var artistGroupCacheRevision = -1L
    private var artistGroupCacheField: ArtistBrowseSortField? = null
    private var artistGroupCacheDirection: SortDirection? = null
    private var artistGroupCache: BrowseGroupPresentation? = null
    private var albumGroupCacheRevision = -1L
    private var albumGroupCacheField: AlbumBrowseSortField? = null
    private var albumGroupCacheDirection: SortDirection? = null
    private var albumGroupCache: BrowseGroupPresentation? = null
    private var persistedArtistGroups: List<BrowseGroup>? = null
    private var persistedAlbumGroups: List<BrowseGroup>? = null
    private var persistedBrowseRevision = -1L
    private var persistedBrowseSplitRevision = -1L
    private var persistedArtistPresentation: BrowseGroupPresentation? = null
    private var persistedAlbumPresentation: BrowseGroupPresentation? = null
    private var persistedBrowsePresentationsMatchCurrentSort = false
    private var searchIndexRevision = -1L
    private var searchIndexArtistSplitRevision = -1L
    private var searchIndexArtistConfigKey = ""
    private var searchIndexLocaleTag = ""
    private var searchIndex: LibrarySearchIndex? = null
    private var folderBrowseIndexRevision = -1L
    private var folderBrowseIndex: FolderBrowseIndex? = null
    private var musicFolderGroupCacheRevision = -1L
    private var musicFolderGroupCache: List<FolderBrowseGroup>? = null
    private val searchResultCache = object : LinkedHashMap<SearchQueryCacheKey, List<Song>>(
        SEARCH_QUERY_CACHE_MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SearchQueryCacheKey, List<Song>>?): Boolean =
            size > SEARCH_QUERY_CACHE_MAX_ENTRIES
    }

    init {
        ArtistNames.configure(LibraryBrowseSettings.artistSplitConfig(backing.context))
    }

    val artistSplitRevision: Long
        get() = artistSplitRevisionState.longValue

    fun updateArtistSplitConfig(config: ArtistSplitConfig) {
        val previous = ArtistNames.currentConfig()
        ArtistNames.configure(config)
        if (ArtistNames.currentConfig() == previous) return
        artistSplitRevisionState.longValue++
        invalidateSearch()
        artistGroupCacheRevision = -1L
        artistGroupCache = null
        albumGroupCacheRevision = -1L
        albumGroupCache = null
        persistedArtistPresentation = null
        persistedAlbumPresentation = null
        persistedBrowsePresentationsMatchCurrentSort = false
        if (backing.sortField == SongSortField.ARTIST) {
            backing.catalog.applyCurrentSort()
            backing.catalog.persistPresentationAsync()
        }
    }

    fun artistGroups(): List<BrowseGroup> = LibraryBrowse.groupByArtist(backing.songs)

    fun albumGroups(): List<BrowseGroup> = LibraryBrowse.groupByAlbum(backing.songs)

    fun recentSongs(): List<Song> =
        LibraryBrowse.recentSongs(backing.songs, PlayHistoryStore.recentSongIds(backing.context))

    fun artistGroupPresentation(
        field: ArtistBrowseSortField,
        direction: SortDirection,
    ): BrowseGroupPresentation {
        val sourceRevision = backing.catalogRevision
        artistGroupCache?.takeIf {
            artistGroupCacheRevision == sourceRevision &&
                artistGroupCacheField == field &&
                artistGroupCacheDirection == direction
        }?.let { return it }
        persistedBrowsePresentationsMatchCurrentSort = false
        return LibraryBrowse.artistGroupPresentation(backing.songs, field, direction).also {
            artistGroupCacheRevision = sourceRevision
            artistGroupCacheField = field
            artistGroupCacheDirection = direction
            artistGroupCache = it
        }
    }

    fun albumGroupPresentation(
        field: AlbumBrowseSortField,
        direction: SortDirection,
    ): BrowseGroupPresentation {
        val sourceRevision = backing.catalogRevision
        albumGroupCache?.takeIf {
            albumGroupCacheRevision == sourceRevision &&
                albumGroupCacheField == field &&
                albumGroupCacheDirection == direction
        }?.let { return it }
        persistedBrowsePresentationsMatchCurrentSort = false
        return LibraryBrowse.albumGroupPresentation(backing.songs, field, direction).also {
            albumGroupCacheRevision = sourceRevision
            albumGroupCacheField = field
            albumGroupCacheDirection = direction
            albumGroupCache = it
        }
    }

    fun songsForArtist(artist: String): List<Song> = LibraryBrowse.songsForArtist(backing.songs, artist)

    fun songsForAlbum(albumKey: AlbumBrowseKey): List<Song> =
        LibraryBrowse.songsForAlbum(backing.songs, albumKey)

    suspend fun prewarmBrowseGroupCache() {
        val source = backing.songs
        val sourceRevision = backing.catalogRevision
        val splitRevision = artistSplitRevision
        if (source.isEmpty()) return
        val artistField = LibraryBrowseSettings.artistBrowseSortField(backing.context)
        val artistDirection = LibraryBrowseSettings.artistBrowseSortDirection(backing.context)
        val albumField = LibraryBrowseSettings.albumBrowseSortField(backing.context)
        val albumDirection = LibraryBrowseSettings.albumBrowseSortDirection(backing.context)
        val artistConfigKey = ArtistNames.currentConfig().cacheKey()
        if (
            artistGroupCacheRevision == sourceRevision &&
            artistGroupCacheField == artistField &&
            artistGroupCacheDirection == artistDirection &&
            albumGroupCacheRevision == sourceRevision &&
            albumGroupCacheField == albumField &&
            albumGroupCacheDirection == albumDirection &&
            persistedBrowsePresentationsMatchCurrentSort
        ) {
            return
        }

        val startedMs = SystemClock.elapsedRealtime()
        val canReusePersistedGroups = persistedBrowseRevision == sourceRevision &&
            persistedBrowseSplitRevision == splitRevision &&
            persistedArtistGroups != null &&
            persistedAlbumGroups != null
        val readyArtists = artistGroupCache.takeIf {
            artistGroupCacheRevision == sourceRevision &&
                artistGroupCacheField == artistField &&
                artistGroupCacheDirection == artistDirection
        }
        val readyAlbums = albumGroupCache.takeIf {
            albumGroupCacheRevision == sourceRevision &&
                albumGroupCacheField == albumField &&
                albumGroupCacheDirection == albumDirection
        }
        val reusablePersistedArtists = persistedArtistPresentation.takeIf { canReusePersistedGroups }
        val reusablePersistedAlbums = persistedAlbumPresentation.takeIf { canReusePersistedGroups }
        val prewarmed = withContext(backing.ioDispatcher) {
            val artists = readyArtists ?: reusablePersistedArtists ?: persistedArtistGroups
                .takeIf { canReusePersistedGroups }?.let {
                LibraryBrowse.artistGroupPresentationFromGroups(it, artistField, artistDirection)
            } ?: LibraryBrowse.artistGroupPresentation(source, artistField, artistDirection)
            val albums = readyAlbums ?: reusablePersistedAlbums ?: persistedAlbumGroups
                .takeIf { canReusePersistedGroups }?.let {
                LibraryBrowse.albumGroupPresentationFromGroups(it, albumField, albumDirection)
            } ?: LibraryBrowse.albumGroupPresentation(source, albumField, albumDirection)
            artists to albums
        }
        val isCurrent: () -> Boolean = {
            splitRevision == artistSplitRevision &&
                ArtistNames.currentConfig().cacheKey() == artistConfigKey &&
                LibraryBrowseSettings.artistBrowseSortField(backing.context) == artistField &&
                LibraryBrowseSettings.artistBrowseSortDirection(backing.context) == artistDirection &&
                LibraryBrowseSettings.albumBrowseSortField(backing.context) == albumField &&
                LibraryBrowseSettings.albumBrowseSortDirection(backing.context) == albumDirection
        }
        val needsPersistence = persistedBrowseRevision != sourceRevision ||
            persistedBrowseSplitRevision != splitRevision ||
            persistedArtistGroups == null ||
            persistedAlbumGroups == null ||
            !persistedBrowsePresentationsMatchCurrentSort
        val storeCommitted = if (needsPersistence) {
            backing.storeWriteIfCurrentCatalog(sourceRevision, isCurrent) {
                backing.libraryStore.updateBrowseGroups(
                    artistGroups = prewarmed.first.groups,
                    albumGroups = prewarmed.second.groups,
                    artistConfigKey = artistConfigKey,
                    artistSortField = artistField,
                    artistSortDirection = artistDirection,
                    artistFastScrollSectionTargets = prewarmed.first.fastScrollIndex?.sectionTargets,
                    albumSortField = albumField,
                    albumSortDirection = albumDirection,
                    albumFastScrollSectionTargets = prewarmed.second.fastScrollIndex?.sectionTargets,
                )
            }
        } else {
            true
        }
        if (!storeCommitted) return
        val published = backing.withCurrentCatalogPublication(sourceRevision) {
            if (!isCurrent()) {
                false
            } else {
                artistGroupCacheRevision = sourceRevision
                artistGroupCacheField = artistField
                artistGroupCacheDirection = artistDirection
                artistGroupCache = prewarmed.first
                albumGroupCacheRevision = sourceRevision
                albumGroupCacheField = albumField
                albumGroupCacheDirection = albumDirection
                albumGroupCache = prewarmed.second
                persistedArtistGroups = prewarmed.first.groups
                persistedAlbumGroups = prewarmed.second.groups
                persistedBrowseRevision = sourceRevision
                persistedBrowseSplitRevision = splitRevision
                persistedArtistPresentation = prewarmed.first
                persistedAlbumPresentation = prewarmed.second
                if (needsPersistence) persistedBrowsePresentationsMatchCurrentSort = true
                true
            }
        } == true
        if (!published) return
        DiagnosticLog.event(
            "LibraryLoad",
            "prewarmBrowseGroups durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                "songs=${source.size} artists=${prewarmed.first.groups.size} albums=${prewarmed.second.groups.size} " +
                "artistSort=$artistField/$artistDirection albumSort=$albumField/$albumDirection",
        )
    }

    fun adoptCachedBrowse(cachedBrowse: CachedBrowsePresentations) {
        val revision = backing.catalogRevision
        persistedArtistGroups = cachedBrowse.artistGroups
        persistedAlbumGroups = cachedBrowse.albumGroups
        persistedBrowseRevision = revision
        persistedBrowseSplitRevision = artistSplitRevision
        persistedArtistPresentation = cachedBrowse.persistedArtists
        persistedAlbumPresentation = cachedBrowse.persistedAlbums
        persistedBrowsePresentationsMatchCurrentSort = cachedBrowse.presentationsMatchCurrentSort
        (cachedBrowse.artists ?: cachedBrowse.persistedArtists)?.let {
            artistGroupCacheRevision = revision
            artistGroupCacheField = cachedBrowse.artistField
            artistGroupCacheDirection = cachedBrowse.artistDirection
            artistGroupCache = it
        }
        (cachedBrowse.albums ?: cachedBrowse.persistedAlbums)?.let {
            albumGroupCacheRevision = revision
            albumGroupCacheField = cachedBrowse.albumField
            albumGroupCacheDirection = cachedBrowse.albumDirection
            albumGroupCache = it
        }
    }

    fun searchSongs(query: String): List<Song> {
        val locale = Locale.getDefault()
        val queryLower = query.trim().lowercase(locale)
        if (queryLower.isEmpty()) return emptyList()

        val sourceRevision = backing.catalogRevision
        val splitRevision = artistSplitRevision
        val artistConfigKey = ArtistNames.currentConfig().cacheKey()
        val localeTag = locale.toLanguageTag()
        val cacheKey = SearchQueryCacheKey(
            catalogRevision = sourceRevision,
            artistSplitRevision = splitRevision,
            artistConfigKey = artistConfigKey,
            localeTag = localeTag,
            queryLower = queryLower,
        )
        searchResultCache[cacheKey]?.let { return it }

        val index = currentSearchIndex(
            source = backing.songs,
            sourceRevision = sourceRevision,
            splitRevision = splitRevision,
            artistConfigKey = artistConfigKey,
            localeTag = localeTag,
            locale = locale,
        )
        return LibraryBrowse.search(index, queryLower).also {
            searchResultCache[cacheKey] = it
        }
    }

    fun folderGroups(pathSegments: List<String> = emptyList()): List<FolderBrowseGroup> {
        val parent = pathSegments.map { it.trim() }.filter { it.isNotEmpty() }
        return LibraryBrowse.folderGroupsAtDepth(
            currentFolderBrowseIndex(),
            parent.size,
            parent,
        )
    }

    fun folderGroupsAtDepth(
        depth: Int,
        scopePathSegments: List<String> = emptyList(),
    ): List<FolderBrowseGroup> =
        LibraryBrowse.folderGroupsAtDepth(currentFolderBrowseIndex(), depth, scopePathSegments)

    fun musicFolderGroups(): List<FolderBrowseGroup> {
        val sourceRevision = backing.catalogRevision
        musicFolderGroupCache
            ?.takeIf { musicFolderGroupCacheRevision == sourceRevision }
            ?.let { return it }
        return LibraryBrowse.musicFolderGroups(currentFolderBrowseIndex()).also {
            musicFolderGroupCacheRevision = sourceRevision
            musicFolderGroupCache = it
        }
    }

    fun maxFolderDepth(): Int = currentFolderBrowseIndex().maxDepth

    fun songsForFolder(pathSegments: List<String>): List<Song> =
        LibraryBrowse.songsForFolder(currentFolderBrowseIndex(), pathSegments)

    fun songsInFolder(pathSegments: List<String>): List<Song> =
        LibraryBrowse.songsInFolder(currentFolderBrowseIndex(), pathSegments)

    private fun currentSearchIndex(
        source: List<Song>,
        sourceRevision: Long,
        splitRevision: Long,
        artistConfigKey: String,
        localeTag: String,
        locale: Locale,
    ): LibrarySearchIndex {
        searchIndex
            ?.takeIf {
                searchIndexRevision == sourceRevision &&
                    searchIndexArtistSplitRevision == splitRevision &&
                    searchIndexArtistConfigKey == artistConfigKey &&
                    searchIndexLocaleTag == localeTag
            }
            ?.let { return it }
        searchResultCache.clear()
        return LibraryBrowse.searchIndex(source, locale).also {
            searchIndexRevision = sourceRevision
            searchIndexArtistSplitRevision = splitRevision
            searchIndexArtistConfigKey = artistConfigKey
            searchIndexLocaleTag = localeTag
            searchIndex = it
        }
    }

    private fun currentFolderBrowseIndex(): FolderBrowseIndex {
        val sourceRevision = backing.catalogRevision
        folderBrowseIndex
            ?.takeIf { folderBrowseIndexRevision == sourceRevision }
            ?.let { return it }
        return LibraryBrowse.folderBrowseIndex(backing.songs).also {
            folderBrowseIndexRevision = sourceRevision
            folderBrowseIndex = it
        }
    }

    private fun invalidateSearch() {
        searchIndexRevision = -1L
        searchIndex = null
        searchResultCache.clear()
    }
}
