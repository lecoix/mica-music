package com.mica.music.data.library

import android.os.SystemClock
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.LibraryPresentationBuilder
import com.mica.music.data.LoudnessAnalysis
import com.mica.music.data.PlayStats
import com.mica.music.data.PlayStatsSnapshot
import com.mica.music.data.Song
import com.mica.music.data.SongChangeDiagnostics
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.scanner.canPersistCoverColor
import com.mica.music.data.scanner.toDeviceShadowCanonicalSong
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.launch
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
        backing.presentationRevision++
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
        backing.presentationRevision++
        publishVisibleSongs(reordered)
        LibraryBrowseSettings.setCustomSongOrderIds(backing.context, reordered.map { it.id })
        persistPresentationAsync()
        return true
    }

    fun updateCustomSongOrderLocked(locked: Boolean) {
        if (backing.customSongOrderLocked == locked) return
        backing.presentationRevision++
        backing.customSongOrderLocked = locked
        LibraryBrowseSettings.setCustomSongOrderLocked(backing.context, locked)
    }

    fun publishVisibleSongs(
        list: List<Song>,
        fastScrollIndex: com.mica.music.data.FastScrollIndex? = null,
        shadowAuthorityMutation: Boolean? = null,
    ) {
        val previous = backing.songs
        if (previous != list) {
            backing.catalogRevision++
            val authorityChanged = shadowAuthorityMutation
                ?: !previous.hasSameShadowAuthority(list)
            if (authorityChanged) {
                backing.shadowAuthorityRevision++
            }
        }
        if (!previous.hasSameQueueMetadata(list)) backing.queueMetadataRevision++
        backing.replaceSongs(list)
        backing.songIds = list.map { it.id }
        backing.songFastScrollLabels = fastScrollIndex?.labels
        backing.songFastScrollSectionTargets = fastScrollIndex?.sectionTargets
    }

    private fun List<Song>.hasSameShadowAuthority(other: List<Song>): Boolean =
        size == other.size && other.associateBy(Song::id).let { byId ->
            all { old ->
                val new = byId[old.id] ?: return@let false
                old.toDeviceShadowCanonicalSong(old.id) ==
                    new.toDeviceShadowCanonicalSong(new.id)
            }
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
        val presentation = LibraryPresentationBuilder.prepare(
            scannedSongs,
            backing.sortField,
            backing.sortDirection,
            customOrderIds = LibraryBrowseSettings.customSongOrderIds(backing.context),
        )
        publishVisibleSongs(presentation.visible, presentation.fastScrollIndex)
        persistCustomOrderIfNeeded(presentation.visible)
    }

    fun persistSongsAsync() {
        if (backing.lastScanAtMs == null) return
        val generation = backing.scanGeneration
        backing.ioScope.launch {
            backing.storeWriteIfCurrentGeneration(generation) {
                val scanAt = backing.lastScanAtMs ?: return@storeWriteIfCurrentGeneration
                backing.libraryStore.save(
                    backing.songs,
                    scanAt,
                    backing.lastScanSource,
                    backing.totalSizeMb,
                    backing.sortField,
                    backing.sortDirection,
                    backing.songFastScrollSectionTargets,
                )
            }
        }
    }

    fun persistPresentationAsync() {
        if (scannedSongs.isEmpty() || backing.lastScanAtMs == null) return
        val generation = backing.scanGeneration
        backing.ioScope.launch {
            backing.storeWriteIfCurrentGeneration(generation) {
                backing.libraryStore.updatePresentation(
                    backing.songIds,
                    backing.sortField,
                    backing.sortDirection,
                    backing.songFastScrollSectionTargets,
                )
            }
        }
    }

    fun removeSong(songId: String) {
        val updated = scannedSongs.filterNot { it.id == songId }
        if (updated.size == scannedSongs.size) return
        backing.catalogRevision++
        scannedSongs = updated
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
        if (updatedScanned == oldScanned) return
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
        backing.catalogRevision++
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
                publishVisibleSongs(
                    presentation.visible,
                    presentation.fastScrollIndex,
                    shadowAuthorityMutation = false,
                )
            }
            else -> {
                val visibleIndex = backing.songs.indexOfFirst { it.id == songId }
                if (visibleIndex >= 0) {
                    backing.replaceSongAt(visibleIndex, updatedScanned)
                }
            }
        }
    }

    fun applyCoverColorArgb(
        songId: String,
        albumArtUri: String?,
        argb: Int,
    ) {
        val scannedIndex = scannedSongs.indexOfFirst { it.id == songId }
        if (scannedIndex < 0) return
        val current = scannedSongs[scannedIndex]
        if (current.coverColorArgb == argb) return
        if (!canPersistCoverColor(current, songId, albumArtUri, argb)) return
        val updated = current.copy(coverColorArgb = argb)
        backing.catalogRevision++
        scannedSongs = scannedSongs.toMutableList().also { it[scannedIndex] = updated }
        val visibleIndex = backing.songs.indexOfFirst { it.id == songId }
        if (visibleIndex >= 0) {
            backing.replaceSongAt(visibleIndex, updated)
        }
    }

    fun applyLoudnessAnalysis(
        songId: String,
        analysis: LoudnessAnalysis,
        notifyQueueMetadata: Boolean,
    ) {
        val scannedIndex = scannedSongs.indexOfFirst { it.id == songId }
        if (scannedIndex < 0) return
        val current = scannedSongs[scannedIndex]
        val updated = current.copy(loudnessAnalysis = analysis)
        if (updated == current) return
        backing.catalogRevision++
        scannedSongs = scannedSongs.toMutableList().also { it[scannedIndex] = updated }
        val visibleIndex = backing.songs.indexOfFirst { it.id == songId }
        if (visibleIndex >= 0) {
            if (notifyQueueMetadata) backing.queueMetadataRevision++
            backing.replaceSongAt(visibleIndex, updated)
        }
    }

    fun notifyQueueMetadataChanged() {
        backing.queueMetadataRevision++
    }

    suspend fun prepareLibrarySongs(
        raw: List<Song>,
        field: SongSortField,
        direction: SortDirection,
        diagnosticTag: String,
        diagnosticReason: String,
        useInputOrder: Boolean = false,
        cachedSectionTargets: Map<String, Int>? = null,
        releaseLoadedLyrics: Boolean = false,
    ): PreparedLibrarySongs {
        // Capture all mutable catalog/presentation inputs before leaving the owner dispatcher.
        val catalogRevision = backing.catalogRevision
        val presentationRevision = backing.presentationRevision
        val customOrderIds = LibraryBrowseSettings.customSongOrderIds(backing.context)
        return withContext(backing.ioDispatcher) {
            val statsStartedMs = SystemClock.elapsedRealtime()
            val playStats = backing.scanEnvironment.playStatsSnapshot(raw.map(Song::id))
            val scanned = raw.map { song ->
                val withStats = song.withPlayStats(playStats)
                if (releaseLoadedLyrics && withStats.lyricsLoaded) {
                    withStats.copy(
                        lyricsDocument = com.mica.music.data.LyricsDocument(),
                        lyricsLoaded = false,
                    )
                } else {
                    withStats
                }
            }
            DiagnosticLog.event(
                diagnosticTag,
                "$diagnosticReason stats durMs=${SystemClock.elapsedRealtime() - statsStartedMs} songs=${scanned.size}",
            )

            val presentationStartedMs = SystemClock.elapsedRealtime()
            val timedPresentation = LibraryPresentationBuilder.prepareTimed(
                scannedSongs = scanned,
                field = field,
                direction = direction,
                useInputOrder = useInputOrder,
                cachedSectionTargets = cachedSectionTargets,
                customOrderIds = customOrderIds,
            )
            val presentation = timedPresentation.presentation
            val proposedCustomOrder = if (field == SongSortField.CUSTOM) {
                presentation.visible.map(Song::id).takeIf { it != customOrderIds }
            } else {
                null
            }
            DiagnosticLog.event(
                diagnosticTag,
                "$diagnosticReason presentation durMs=${SystemClock.elapsedRealtime() - presentationStartedMs} " +
                    "sortMs=${timedPresentation.timing.sortMs} " +
                    "labelsMs=${timedPresentation.timing.labelsMs} " +
                    "sectionsMs=${timedPresentation.timing.sectionsMs} " +
                    "raw=${scanned.size} visible=${presentation.visible.size} sort=$field/$direction " +
                    "cachedOrder=$useInputOrder labels=${presentation.fastScrollIndex?.labels?.size ?: 0} " +
                    "sections=${presentation.fastScrollIndex?.sectionTargets?.size ?: 0} " +
                    "cachedSections=${cachedSectionTargets != null}",
            )

            PreparedLibrarySongs(
                scanned = scanned,
                visible = presentation.visible,
                fastScrollIndex = presentation.fastScrollIndex,
                catalogRevision = catalogRevision,
                presentationRevision = presentationRevision,
                customOrderIdsToPersist = proposedCustomOrder,
            )
        }
    }

    fun persistPreparedCustomOrderIfCurrent(prepared: PreparedLibrarySongs) {
        val ids = prepared.customOrderIdsToPersist ?: return
        if (backing.presentationRevision != prepared.presentationRevision) return
        LibraryBrowseSettings.setCustomSongOrderIds(backing.context, ids)
    }

    private fun Song.withPlayStats(playStats: PlayStatsSnapshot): Song {
        val stats = playStats[id]
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
