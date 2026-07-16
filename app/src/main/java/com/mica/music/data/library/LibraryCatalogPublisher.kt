package com.mica.music.data.library

import android.os.SystemClock
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.LibraryPresentationBuilder
import com.mica.music.data.PlayStats
import com.mica.music.data.Song
import com.mica.music.data.SongChangeDiagnostics
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class LibraryCatalogPublisher(
    private val backing: MusicLibraryBacking,
) {
    private var scannedSongs: List<Song> = emptyList()

    fun hasScannedSongs(): Boolean = scannedSongs.isNotEmpty()

    fun scannedSongsSnapshot(): List<Song> = scannedSongs

    fun adoptPrepared(prepared: PreparedLibrarySongs) {
        scannedSongs = prepared.scanned
        publishVisibleSongs(prepared.visible, prepared.fastScrollIndex)
    }

    fun clearCatalog() {
        scannedSongs = emptyList()
        publishVisibleSongs(emptyList())
    }

    fun releaseLoadedLyrics() {
        if (scannedSongs.none(Song::lyricsLoaded)) return
        scannedSongs = scannedSongs.map { song ->
            song.copy(lyricsDocument = com.mica.music.data.LyricsDocument(), lyricsLoaded = false)
        }
        applyCurrentSort()
    }

    fun reloadSortFromPrefs() {
        backing.sortField = LibraryBrowseSettings.songSortField(backing.context)
        backing.sortDirection = LibraryBrowseSettings.songSortDirection(backing.context)
        backing.customSongOrderLocked = LibraryBrowseSettings.customSongOrderLocked(backing.context)
    }

    fun updateSort(field: SongSortField, direction: SortDirection) {
        if (field == SongSortField.CUSTOM && LibraryBrowseSettings.customSongOrderIds(backing.context).isEmpty()) {
            LibraryBrowseSettings.setCustomSongOrderIds(backing.context, backing.songs.map { it.id })
        }
        backing.sortField = field
        backing.sortDirection = if (field == SongSortField.CUSTOM) SortDirection.ASC else direction
        LibraryBrowseSettings.setSongSort(backing.context, field, backing.sortDirection)
        applyCurrentSort()
        persistPresentationAsync()
    }

    fun moveVisibleSong(fromIndex: Int, toIndex: Int): Boolean {
        if (backing.sortField != SongSortField.CUSTOM) return false
        if (backing.customSongOrderLocked) return false
        val reordered = backing.songs.toMutableList()
        if (fromIndex !in reordered.indices || toIndex !in reordered.indices || fromIndex == toIndex) return false
        val moved = reordered.removeAt(fromIndex)
        reordered.add(toIndex, moved)
        publishVisibleSongs(reordered)
        LibraryBrowseSettings.setCustomSongOrderIds(backing.context, reordered.map { it.id })
        persistPresentationAsync()
        return true
    }

    fun updateCustomSongOrderLocked(locked: Boolean) {
        backing.customSongOrderLocked = locked
        LibraryBrowseSettings.setCustomSongOrderLocked(backing.context, locked)
    }

    fun publishVisibleSongs(list: List<Song>, fastScrollIndex: com.mica.music.data.FastScrollIndex? = null) {
        val previous = backing.songs
        if (previous != list) backing.catalogRevision++
        if (!previous.hasSameQueueMetadata(list)) backing.queueMetadataRevision++
        backing.replaceSongs(list)
        backing.songIds = list.map { it.id }
        backing.songFastScrollLabels = fastScrollIndex?.labels
        backing.songFastScrollSectionTargets = fastScrollIndex?.sectionTargets
    }

    private fun List<Song>.hasSameQueueMetadata(other: List<Song>): Boolean =
        size == other.size && other.associateBy(Song::id).let { byId ->
            all { old ->
                val new = byId[old.id] ?: return@let false
                old.copy(
                    playCount = new.playCount,
                    totalListenSeconds = new.totalListenSeconds,
                    lastPlayedAtMs = new.lastPlayedAtMs,
                ) == new
            }
        }

    fun applyCurrentSort(diagnosticReason: String? = null) {
        if (scannedSongs.isEmpty()) return
        val startedMs = SystemClock.elapsedRealtime()
        val presentation = LibraryPresentationBuilder.prepare(
            scannedSongs,
            backing.sortField,
            backing.sortDirection,
            customOrderIds = LibraryBrowseSettings.customSongOrderIds(backing.context),
        )
        publishVisibleSongs(presentation.visible, presentation.fastScrollIndex)
        persistCustomOrderIfNeeded(presentation.visible)
        if (diagnosticReason != null) {
            DiagnosticLog.event(
                "LibraryLoad",
                "$diagnosticReason sort+publish durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                    "raw=${scannedSongs.size} visible=${backing.songs.size} " +
                    "sort=${backing.sortField}/${backing.sortDirection}",
            )
        }
    }

    fun persistSongsAsync() {
        if (scannedSongs.isEmpty() || backing.lastScanAtMs == null) return
        val snapshot = backing.songs
        val scanAt = backing.lastScanAtMs!!
        val source = backing.lastScanSource
        val sizeMb = backing.totalSizeMb
        val field = backing.sortField
        val direction = backing.sortDirection
        val sectionTargets = backing.songFastScrollSectionTargets
        val revision = backing.nextStoreRevision()
        backing.ioScope.launch {
            backing.storeSyncMutex.withLock {
                if (!backing.isLatestStoreRevision(revision)) return@withLock
                backing.libraryStore.save(snapshot, scanAt, source, sizeMb, field, direction, sectionTargets)
            }
        }
    }

    fun persistPresentationAsync() {
        if (scannedSongs.isEmpty() || backing.lastScanAtMs == null) return
        val songIds = backing.songIds
        val field = backing.sortField
        val direction = backing.sortDirection
        val sectionTargets = backing.songFastScrollSectionTargets
        val revision = backing.nextStoreRevision()
        backing.ioScope.launch {
            backing.storeSyncMutex.withLock {
                if (!backing.isLatestStoreRevision(revision)) return@withLock
                backing.libraryStore.updatePresentation(songIds, field, direction, sectionTargets)
            }
        }
    }

    fun removeSong(songId: String) {
        scannedSongs = scannedSongs.filterNot { it.id == songId }
        applyCurrentSort()
        if (backing.lastScanAtMs != null) {
            persistSongsAsync()
        }
    }

    fun applyPlayStats(songId: String, stats: PlayStats) {
        val scannedIndex = scannedSongs.indexOfFirst { it.id == songId }
        if (scannedIndex < 0) return
        val oldScanned = scannedSongs[scannedIndex]
        val updatedScanned = scannedSongs[scannedIndex].copy(
            playCount = stats.count,
            totalListenSeconds = stats.totalListenSeconds,
            lastPlayedAtMs = stats.lastPlayedAtMs,
        )
        DiagnosticLog.event(
            "LibraryMutation",
            "diag=play-stats-song-update song=${songId.takeLast(12)} " +
                "fields=${SongChangeDiagnostics.summarizeChangedFields(oldScanned, updatedScanned)} " +
                "count=${oldScanned.playCount}->${updatedScanned.playCount} " +
                "listen=${oldScanned.totalListenSeconds}->${updatedScanned.totalListenSeconds} " +
                "lastPlayed=${oldScanned.lastPlayedAtMs}->${updatedScanned.lastPlayedAtMs} " +
                "sort=${backing.sortField}/${backing.sortDirection} " +
                "visibleIndex=${backing.songs.indexOfFirst { it.id == songId }}",
        )
        scannedSongs = scannedSongs.toMutableList().also { it[scannedIndex] = updatedScanned }
        when (backing.sortField) {
            SongSortField.PLAY_COUNT,
            SongSortField.LAST_PLAYED,
            -> {
                val presentation = LibraryPresentationBuilder.prepare(
                    scannedSongs,
                    backing.sortField,
                    backing.sortDirection,
                )
                publishVisibleSongs(presentation.visible, presentation.fastScrollIndex)
            }
            else -> {
                val visibleIndex = backing.songs.indexOfFirst { it.id == songId }
                if (visibleIndex >= 0) {
                    backing.replaceSongAt(visibleIndex, updatedScanned)
                }
            }
        }
    }

    suspend fun prepareLibrarySongs(
        raw: List<Song>,
        field: SongSortField,
        direction: SortDirection,
        diagnosticTag: String,
        diagnosticReason: String,
        useInputOrder: Boolean = false,
        cachedSectionTargets: Map<String, Int>? = null,
    ): PreparedLibrarySongs = withContext(backing.ioDispatcher) {
        val statsStartedMs = SystemClock.elapsedRealtime()
        val scanned = raw.map { song -> song.withPlayStats() }
        DiagnosticLog.event(
            diagnosticTag,
            "$diagnosticReason stats durMs=${SystemClock.elapsedRealtime() - statsStartedMs} songs=${scanned.size}",
        )

        val presentationStartedMs = SystemClock.elapsedRealtime()
        val presentation = LibraryPresentationBuilder.prepare(
            scannedSongs = scanned,
            field = field,
            direction = direction,
            useInputOrder = useInputOrder,
            cachedSectionTargets = cachedSectionTargets,
            customOrderIds = LibraryBrowseSettings.customSongOrderIds(backing.context),
        )
        if (field == SongSortField.CUSTOM) {
            LibraryBrowseSettings.setCustomSongOrderIds(backing.context, presentation.visible.map { it.id })
        }
        DiagnosticLog.event(
            diagnosticTag,
            "$diagnosticReason presentation durMs=${SystemClock.elapsedRealtime() - presentationStartedMs} " +
                "raw=${scanned.size} visible=${presentation.visible.size} sort=$field/$direction " +
                "cachedOrder=$useInputOrder labels=${presentation.fastScrollIndex?.labels?.size ?: 0} " +
                "sections=${presentation.fastScrollIndex?.sectionTargets?.size ?: 0} " +
                "cachedSections=${cachedSectionTargets != null}",
        )

        PreparedLibrarySongs(
            scanned = scanned,
            visible = presentation.visible,
            fastScrollIndex = presentation.fastScrollIndex,
        )
    }

    private fun Song.withPlayStats(): Song {
        val stats = backing.scanEnvironment.playStats(id)
        return copy(
            playCount = stats.count,
            totalListenSeconds = stats.totalListenSeconds,
            lastPlayedAtMs = stats.lastPlayedAtMs,
        )
    }

    private fun persistCustomOrderIfNeeded(visible: List<Song>) {
        if (backing.sortField != SongSortField.CUSTOM) return
        val ids = visible.map { it.id }
        if (ids != LibraryBrowseSettings.customSongOrderIds(backing.context)) {
            LibraryBrowseSettings.setCustomSongOrderIds(backing.context, ids)
        }
    }
}
