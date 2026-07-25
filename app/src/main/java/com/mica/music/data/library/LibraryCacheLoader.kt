package com.mica.music.data.library

import android.os.SystemClock
import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.ArtistNames
import com.mica.music.data.BrowseGroup
import com.mica.music.data.BrowseGroupPresentation
import com.mica.music.data.LibraryBrowse
import com.mica.music.data.SortDirection
import com.mica.music.data.StartupBrowseTarget
import com.mica.music.data.cacheKey
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.withContext

internal data class CachedBrowsePresentations(
    val artists: BrowseGroupPresentation?,
    val albums: BrowseGroupPresentation?,
    val persistedArtists: BrowseGroupPresentation?,
    val persistedAlbums: BrowseGroupPresentation?,
    val presentationsMatchCurrentSort: Boolean,
    val artistGroups: List<BrowseGroup>?,
    val albumGroups: List<BrowseGroup>?,
    val artistField: ArtistBrowseSortField,
    val artistDirection: SortDirection,
    val albumField: AlbumBrowseSortField,
    val albumDirection: SortDirection,
)

internal class LibraryCacheLoader(
    private val backing: MusicLibraryBacking,
) {
    private val catalog get() = backing.catalog

    suspend fun loadCachedLibrary(target: StartupBrowseTarget): CachedBrowsePresentations? {
        if (backing.released || backing.hasScanned || backing.isScanning) {
            DiagnosticLog.event(
                "LibraryLoad",
                "loadCached skipped released=${backing.released} hasScanned=${backing.hasScanned} " +
                    "isScanning=${backing.isScanning}",
            )
            return null
        }
        // Claim a publication generation so a concurrent scan/clear can invalidate this hydrate.
        val generation = ++backing.scanGeneration
        val startedMs = SystemClock.elapsedRealtime()
        DiagnosticLog.event("LibraryLoad", "loadCached begin generation=$generation")
        backing.isLoadingCachedLibrary = true
        try {
            val dbStartedMs = SystemClock.elapsedRealtime()
            val cached = withContext(backing.ioDispatcher) { backing.libraryStore.loadCached() }
            DiagnosticLog.event(
                "LibraryLoad",
                "loadCached db durMs=${SystemClock.elapsedRealtime() - dbStartedMs} songs=${cached?.songs?.size ?: 0}",
            )
            if (cached == null) {
                DiagnosticLog.event("LibraryLoad", "loadCached empty durMs=${SystemClock.elapsedRealtime() - startedMs}")
                return null
            }
            if (!backing.isActiveGeneration(generation)) {
                DiagnosticLog.event(
                    "LibraryLoad",
                    "loadCached discarded after db generation=$generation current=${backing.scanGeneration}",
                )
                return null
            }
            catalog.reloadSortFromPrefs()
            val sortCanUseStoredOrder = cached.sortField == backing.sortField &&
                cached.sortDirection == backing.sortDirection &&
                backing.sortField != com.mica.music.data.SongSortField.CUSTOM
            val prepared = catalog.prepareLibrarySongs(
                raw = cached.songs,
                field = backing.sortField,
                direction = backing.sortDirection,
                diagnosticTag = "LibraryLoad",
                diagnosticReason = "loadCached",
                useInputOrder = sortCanUseStoredOrder,
                cachedSectionTargets = cached.fastScrollSectionTargets,
            )
            val artistField = LibraryBrowseSettings.artistBrowseSortField(backing.context)
            val artistDirection = LibraryBrowseSettings.artistBrowseSortDirection(backing.context)
            val albumField = LibraryBrowseSettings.albumBrowseSortField(backing.context)
            val albumDirection = LibraryBrowseSettings.albumBrowseSortDirection(backing.context)
            val browseCacheValid =
                cached.browseArtistConfigKey == ArtistNames.currentConfig().cacheKey() &&
                cached.artistGroups != null &&
                cached.albumGroups != null
            val cachedArtistGroups = cached.artistGroups.takeIf { browseCacheValid }
            val cachedAlbumGroups = cached.albumGroups.takeIf { browseCacheValid }
            val artistSnapshotHit = browseCacheValid &&
                cached.artistBrowseSortField == artistField &&
                cached.artistBrowseSortDirection == artistDirection &&
                (artistField != ArtistBrowseSortField.TITLE ||
                    cached.artistBrowseFastScrollSectionTargets != null)
            val albumSnapshotNeedsIndex = albumField == AlbumBrowseSortField.TITLE ||
                albumField == AlbumBrowseSortField.ARTIST
            val albumSnapshotHit = browseCacheValid &&
                cached.albumBrowseSortField == albumField &&
                cached.albumBrowseSortDirection == albumDirection &&
                (!albumSnapshotNeedsIndex || cached.albumBrowseFastScrollSectionTargets != null)
            val persistedArtists = cachedArtistGroups.takeIf { artistSnapshotHit }?.let {
                LibraryBrowse.artistGroupPresentationFromPersistedOrder(
                    it,
                    artistField,
                    cached.artistBrowseFastScrollSectionTargets,
                )
            }
            val persistedAlbums = cachedAlbumGroups.takeIf { albumSnapshotHit }?.let {
                LibraryBrowse.albumGroupPresentationFromPersistedOrder(
                    it,
                    albumField,
                    cached.albumBrowseFastScrollSectionTargets,
                )
            }
            val prepareBrowse = {
                val artists = if (target == StartupBrowseTarget.ARTISTS) {
                    persistedArtists ?: cachedArtistGroups?.let {
                        LibraryBrowse.artistGroupPresentationFromGroups(it, artistField, artistDirection)
                    } ?: LibraryBrowse.artistGroupPresentation(cached.songs, artistField, artistDirection)
                } else {
                    null
                }
                val albums = if (target == StartupBrowseTarget.ALBUMS) {
                    persistedAlbums ?: cachedAlbumGroups?.let {
                        LibraryBrowse.albumGroupPresentationFromGroups(it, albumField, albumDirection)
                    } ?: LibraryBrowse.albumGroupPresentation(cached.songs, albumField, albumDirection)
                } else {
                    null
                }
                CachedBrowsePresentations(
                    artists = artists,
                    albums = albums,
                    persistedArtists = persistedArtists,
                    persistedAlbums = persistedAlbums,
                    presentationsMatchCurrentSort = artistSnapshotHit && albumSnapshotHit,
                    artistGroups = cachedArtistGroups ?: artists?.groups,
                    albumGroups = cachedAlbumGroups ?: albums?.groups,
                    artistField = artistField,
                    artistDirection = artistDirection,
                    albumField = albumField,
                    albumDirection = albumDirection,
                )
            }
            val cachedBrowse = if (target == StartupBrowseTarget.NONE) {
                prepareBrowse()
            } else {
                withContext(backing.ioDispatcher) { prepareBrowse() }
            }
            if (!backing.isActiveGeneration(generation)) {
                DiagnosticLog.event(
                    "LibraryLoad",
                    "loadCached discarded before adopt generation=$generation current=${backing.scanGeneration}",
                )
                return null
            }
            catalog.adoptPrepared(prepared)
            backing.totalSizeMb = cached.totalSizeMb
            backing.lastScanAtMs = cached.lastScanAtMs
            backing.lastScanSource = cached.lastScanSource
            backing.hasScanned = true
            backing.lastScanError = null
            if (!sortCanUseStoredOrder || cached.fastScrollSectionTargets == null) {
                catalog.persistPresentationAsync()
            }
            DiagnosticLog.event(
                "LibraryLoad",
                "loadCached end durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                    "songs=${backing.songs.size} sizeMb=${backing.totalSizeMb} source=${backing.lastScanSource} " +
                    "cachedOrder=$sortCanUseStoredOrder generation=$generation",
            )
            DiagnosticLog.event(
                "LibraryLoad",
                "loadCached browseCache=${if (browseCacheValid) "hit" else "miss"} target=$target " +
                    "artistSnapshot=$artistSnapshotHit albumSnapshot=$albumSnapshotHit " +
                    "artists=${cachedBrowse?.artists?.groups?.size ?: 0} " +
                    "albums=${cachedBrowse?.albums?.groups?.size ?: 0}",
            )
            return cachedBrowse
        } finally {
            backing.isLoadingCachedLibrary = false
        }
    }
}
