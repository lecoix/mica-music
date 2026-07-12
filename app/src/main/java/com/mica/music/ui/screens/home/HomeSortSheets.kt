package com.mica.music.ui.screens.home

import android.content.Context
import androidx.compose.runtime.Composable
import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlaylistStore
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.AppUiSettings
import com.mica.music.ui.components.BrowseGroupDisplaySheet
import com.mica.music.ui.components.SongSortSheet

data class HomeBrowseSortState(
    val albumSortField: AlbumBrowseSortField,
    val albumSortDirection: SortDirection,
    val albumGridColumns: Int,
    val artistSortField: ArtistBrowseSortField,
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
    uiSettings: AppUiSettings,
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
                uiSettings = uiSettings,
                isArtist = false,
            )
        }
        isArtistRootSort -> {
            val artistSortFields = ArtistBrowseSortField.entries
            BrowseGroupDisplaySheet(
                sortFieldLabels = artistSortFields.map { it.label },
                selectedSortFieldIndex = artistSortFields.indexOf(browseSort.artistSortField),
                currentDirection = browseSort.artistSortDirection,
                currentColumns = browseSort.artistGridColumns,
                onDismiss = onDismiss,
                onSortFieldSelected = { index ->
                    val field = artistSortFields.getOrElse(index) { ArtistBrowseSortField.TITLE }
                    val updated = browseSort.copy(artistSortField = field)
                    onBrowseSortChange(updated)
                    LibraryBrowseSettings.setArtistBrowseSort(context, field, updated.artistSortDirection)
                },
                onDirectionSelected = { direction ->
                    val updated = browseSort.copy(artistSortDirection = direction)
                    onBrowseSortChange(updated)
                    LibraryBrowseSettings.setArtistBrowseSort(context, updated.artistSortField, direction)
                },
                onColumnsSelected = { columns ->
                    val normalized = columns.coerceIn(1, 4)
                    onBrowseSortChange(browseSort.copy(artistGridColumns = normalized))
                    LibraryBrowseSettings.setArtistBrowseGridColumns(context, normalized)
                },
                uiSettings = uiSettings,
                isArtist = true,
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
                includeCustomSort = isPlaylistSort || section == HomeSection.Songs,
                customSortLocked = !isPlaylistSort && library.customSongOrderLocked,
                onDismiss = onDismiss,
                onApply = { field, direction ->
                    if (isPlaylistSort && activePlaylistId != null) {
                        playlistStore.updateSort(activePlaylistId, field, direction)
                    } else {
                        if (field == SongSortField.CUSTOM) {
                            val locked = if (library.sortField == SongSortField.CUSTOM) {
                                !library.customSongOrderLocked
                            } else {
                                false
                            }
                            library.updateCustomSongOrderLocked(locked)
                        }
                        library.updateSort(field, direction)
                    }
                    onDismiss()
                },
                onMultiSelectClick = if (section == HomeSection.Songs && !isPlaylistSort) {
                    onMultiSelectClick
                } else {
                    null
                },
                uiSettings = uiSettings.takeIf { section == HomeSection.Songs && !isPlaylistSort },
            )
        }
    }
}
