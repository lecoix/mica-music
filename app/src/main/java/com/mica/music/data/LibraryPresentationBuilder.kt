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
        customOrderIds: List<String> = emptyList(),
    ): LibraryPresentation {
        val visible = when {
            useInputOrder -> scannedSongs
            field == SongSortField.CUSTOM -> SongSorter.customOrder(scannedSongs, customOrderIds)
            else -> SongSorter.sort(scannedSongs, field, direction)
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
