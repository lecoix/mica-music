package com.mica.music.ui.screens.home

import android.content.Context
import androidx.compose.runtime.Composable
import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlaylistStore
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.ui.components.BrowseGroupDisplaySheet
import com.mica.music.ui.components.SongSortSheet

data class HomeBrowseSortState(
    val albumSortField: AlbumBrowseSortField,
    val albumSortDirection: SortDirection,
    val albumGridColumns: Int,
    val artistSortDirection: SortDirection,
    val artistGridColumns: Int,
)

@Composable
internal fun HomeSortSheets(
    visible: Boolean,
    context: Context,
    section: HomeSection,
    isAlbumRootSort: Boolean,
    isArtistRootSort: Boolean,
    isPlaylistSort: Boolean,
    browseSort: HomeBrowseSortState,
    onBrowseSortChange: (HomeBrowseSortState) -> Unit,
    library: MusicLibrary,
    playlistStore: PlaylistStore,
    activePlaylistId: String?,
    playlistSortField: SongSortField?,
    playlistSortDirection: SortDirection?,
    onDismiss: () -> Unit,
    onMultiSelectClick: (() -> Unit)?,
) {
    if (!visible) return

    when {
        isAlbumRootSort -> {
            val albumSortFields = AlbumBrowseSortField.entries
            BrowseGroupDisplaySheet(
                sortFieldLabels = albumSortFields.map { it.label },
                selectedSortFieldIndex = albumSortFields.indexOf(browseSort.albumSortField),
                currentDirection = browseSort.albumSortDirection,
                currentColumns = browseSort.albumGridColumns,
                onDismiss = onDismiss,
                onSortFieldSelected = { index ->
                    val field = albumSortFields.getOrElse(index) { AlbumBrowseSortField.TITLE }
                    val updated = browseSort.copy(albumSortField = field)
                    onBrowseSortChange(updated)
                    LibraryBrowseSettings.setAlbumBrowseSort(context, field, updated.albumSortDirection)
                },
                onDirectionSelected = { direction ->
                    val updated = browseSort.copy(albumSortDirection = direction)
                    onBrowseSortChange(updated)
                    LibraryBrowseSettings.setAlbumBrowseSort(context, updated.albumSortField, direction)
                },
                onColumnsSelected = { columns ->
                    val normalized = columns.coerceIn(1, 4)
                    onBrowseSortChange(browseSort.copy(albumGridColumns = normalized))
                    LibraryBrowseSettings.setAlbumBrowseGridColumns(context, normalized)
                },
            )
        }
        isArtistRootSort -> {
            BrowseGroupDisplaySheet(
                sortFieldLabels = emptyList(),
                selectedSortFieldIndex = 0,
                currentDirection = browseSort.artistSortDirection,
                currentColumns = browseSort.artistGridColumns,
                onDismiss = onDismiss,
                onSortFieldSelected = {},
                onDirectionSelected = { direction ->
                    onBrowseSortChange(browseSort.copy(artistSortDirection = direction))
                    LibraryBrowseSettings.setArtistBrowseSortDirection(context, direction)
                },
                onColumnsSelected = { columns ->
                    val normalized = columns.coerceIn(1, 4)
                    onBrowseSortChange(browseSort.copy(artistGridColumns = normalized))
                    LibraryBrowseSettings.setArtistBrowseGridColumns(context, normalized)
                },
            )
        }
        else -> {
            SongSortSheet(
                currentField = if (isPlaylistSort) {
                    playlistSortField ?: library.sortField
                } else {
                    library.sortField
                },
                currentDirection = if (isPlaylistSort) {
                    playlistSortDirection ?: library.sortDirection
                } else {
                    library.sortDirection
                },
                includeCustomSort = isPlaylistSort,
                onDismiss = onDismiss,
                onApply = { field, direction ->
                    if (isPlaylistSort && activePlaylistId != null) {
                        playlistStore.updateSort(activePlaylistId, field, direction)
                    } else if (field != SongSortField.CUSTOM) {
                        library.updateSort(field, direction)
                    }
                    onDismiss()
                },
                onMultiSelectClick = if (section == HomeSection.Songs && !isPlaylistSort) {
                    onMultiSelectClick
                } else {
                    null
                },
            )
        }
    }
}
