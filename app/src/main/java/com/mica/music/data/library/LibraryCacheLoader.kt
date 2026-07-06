package com.mica.music.data.library

import android.os.SystemClock
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.withContext

internal class LibraryCacheLoader(
    private val backing: MusicLibraryBacking,
) {
    private val catalog get() = backing.catalog

    suspend fun loadCachedLibrary() {
        if (backing.released || backing.hasScanned || backing.isScanning) {
            DiagnosticLog.event(
                "LibraryLoad",
                "loadCached skipped released=${backing.released} hasScanned=${backing.hasScanned} " +
                    "isScanning=${backing.isScanning}",
            )
            return
        }
        val startedMs = SystemClock.elapsedRealtime()
        DiagnosticLog.event("LibraryLoad", "loadCached begin")
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
                return
            }
            if (backing.released) return
            catalog.reloadSortFromPrefs()
            val sortCanUseStoredOrder = cached.sortField == backing.sortField &&
                cached.sortDirection == backing.sortDirection
            val prepared = catalog.prepareLibrarySongs(
                raw = cached.songs,
                field = backing.sortField,
                direction = backing.sortDirection,
                diagnosticTag = "LibraryLoad",
                diagnosticReason = "loadCached",
                useInputOrder = sortCanUseStoredOrder,
                cachedSectionTargets = cached.fastScrollSectionTargets,
            )
            catalog.adoptPrepared(prepared)
            backing.totalSizeMb = cached.totalSizeMb
            backing.lastScanAtMs = cached.lastScanAtMs
            backing.lastScanSource = cached.lastScanSource
            backing.hasScanned = true
            backing.lastScanError = null
            if (!sortCanUseStoredOrder || cached.fastScrollSectionTargets == null) {
                catalog.persistSongsAsync()
            }
            DiagnosticLog.event(
                "LibraryLoad",
                "loadCached end durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                    "songs=${backing.songs.size} sizeMb=${backing.totalSizeMb} source=${backing.lastScanSource} " +
                    "cachedOrder=$sortCanUseStoredOrder",
            )
        } finally {
            backing.isLoadingCachedLibrary = false
        }
    }
}
