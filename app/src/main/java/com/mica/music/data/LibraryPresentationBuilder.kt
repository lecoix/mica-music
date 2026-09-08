package com.mica.music.data

import android.os.SystemClock

internal data class LibraryPresentation(
    val visible: List<Song>,
    val fastScrollIndex: FastScrollIndex?,
)

internal data class LibraryPresentationTiming(
    val sortMs: Long,
    val labelsMs: Long,
    val sectionsMs: Long,
)

internal data class TimedLibraryPresentation(
    val presentation: LibraryPresentation,
    val timing: LibraryPresentationTiming,
)

internal object LibraryPresentationBuilder {
    fun prepare(
        scannedSongs: List<Song>,
        field: SongSortField,
        direction: SortDirection,
        useInputOrder: Boolean = false,
        cachedSectionTargets: Map<String, Int>? = null,
        customOrderIds: List<String> = emptyList(),
    ): LibraryPresentation = prepareTimed(
        scannedSongs = scannedSongs,
        field = field,
        direction = direction,
        useInputOrder = useInputOrder,
        cachedSectionTargets = cachedSectionTargets,
        customOrderIds = customOrderIds,
    ).presentation

    internal fun prepareTimed(
        scannedSongs: List<Song>,
        field: SongSortField,
        direction: SortDirection,
        useInputOrder: Boolean = false,
        cachedSectionTargets: Map<String, Int>? = null,
        customOrderIds: List<String> = emptyList(),
    ): TimedLibraryPresentation {
        val sortStartedMs = SystemClock.elapsedRealtime()
        val visible = when {
            useInputOrder -> scannedSongs
            field == SongSortField.CUSTOM -> SongSorter.customOrder(scannedSongs, customOrderIds)
            else -> SongSorter.sort(scannedSongs, field, direction)
        }
        val sortMs = SystemClock.elapsedRealtime() - sortStartedMs
        val labelsStartedMs = SystemClock.elapsedRealtime()
        val labels = LibraryFastScrollIndex.labelsForSongs(visible, field)
        val labelsMs = SystemClock.elapsedRealtime() - labelsStartedMs
        val sectionsStartedMs = SystemClock.elapsedRealtime()
        val fastScrollIndex = labels?.let { resolvedLabels ->
            FastScrollIndex(
                labels = resolvedLabels,
                sectionTargets = cachedSectionTargets ?: LibraryFastScrollIndex.sectionTargets(resolvedLabels),
            )
        }
        val sectionsMs = SystemClock.elapsedRealtime() - sectionsStartedMs
        return TimedLibraryPresentation(
            presentation = LibraryPresentation(visible, fastScrollIndex),
            timing = LibraryPresentationTiming(
                sortMs = sortMs,
                labelsMs = labelsMs,
                sectionsMs = sectionsMs,
            ),
        )
    }
}
