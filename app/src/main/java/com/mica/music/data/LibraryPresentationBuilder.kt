package com.mica.music.data

internal data class LibraryPresentation(
    val visible: List<Song>,
    val fastScrollIndex: FastScrollIndex?,
)

internal object LibraryPresentationBuilder {
    fun prepare(
        scannedSongs: List<Song>,
        field: SongSortField,
        direction: SortDirection,
        useInputOrder: Boolean = false,
        cachedSectionTargets: Map<String, Int>? = null,
    ): LibraryPresentation {
        val visible = if (useInputOrder) {
            scannedSongs
        } else {
            SongSorter.sort(scannedSongs, field, direction)
        }
        val labels = LibraryFastScrollIndex.labelsForSongs(visible, field)
        val fastScrollIndex = labels?.let { resolvedLabels ->
            FastScrollIndex(
                labels = resolvedLabels,
                sectionTargets = cachedSectionTargets ?: LibraryFastScrollIndex.sectionTargets(resolvedLabels),
            )
        }
        return LibraryPresentation(visible, fastScrollIndex)
    }
}
