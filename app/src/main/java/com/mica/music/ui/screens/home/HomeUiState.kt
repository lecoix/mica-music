package com.mica.music.ui.screens.home

import android.content.Context
import androidx.compose.runtime.saveable.Saver
import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.SortDirection

data class HomeUiState(
    val section: HomeSection = HomeSection.Songs,
    val activePlaylistId: String? = null,
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val browseDestination: BrowseDestination = BrowseDestination.Root,
    val returnSection: HomeSection = HomeSection.Songs,
    val folderVisibleDepth: Int = 0,
    val folderVisibleScope: List<String> = emptyList(),
    val browseSort: HomeBrowseSortState = HomeBrowseSortState(
        albumSortField = AlbumBrowseSortField.TITLE,
        albumSortDirection = SortDirection.ASC,
        albumGridColumns = 2,
        artistSortField = ArtistBrowseSortField.TITLE,
        artistSortDirection = SortDirection.ASC,
        artistGridColumns = 2,
    ),
) {
    fun navigationSnapshot(
        songMultiSelectActive: Boolean,
        selectedSongIds: Set<String>,
    ): HomeNavigationSnapshot = HomeNavigationSnapshot(
        section = section,
        searchOpen = searchOpen,
        searchQuery = searchQuery,
        browseDestination = browseDestination,
        returnSection = returnSection,
        activePlaylistId = activePlaylistId,
        songMultiSelectActive = songMultiSelectActive,
        selectedSongIds = selectedSongIds,
    )

    fun withNavigationSnapshot(snapshot: HomeNavigationSnapshot): HomeUiState = copy(
        section = snapshot.section,
        searchOpen = snapshot.searchOpen,
        searchQuery = snapshot.searchQuery,
        browseDestination = snapshot.browseDestination,
        returnSection = snapshot.returnSection,
        activePlaylistId = snapshot.activePlaylistId,
    )

    companion object {
        fun initial(context: Context): HomeUiState = HomeUiState(
            browseSort = HomeBrowseSortState(
                albumSortField = LibraryBrowseSettings.albumBrowseSortField(context),
                albumSortDirection = LibraryBrowseSettings.albumBrowseSortDirection(context),
                albumGridColumns = LibraryBrowseSettings.albumBrowseGridColumns(context),
                artistSortField = LibraryBrowseSettings.artistBrowseSortField(context),
                artistSortDirection = LibraryBrowseSettings.artistBrowseSortDirection(context),
                artistGridColumns = LibraryBrowseSettings.artistBrowseGridColumns(context),
            ),
        )
    }
}

private const val HomeUiStateSaveVersion = "v2"
private const val HomeUiStateLegacySaveVersion = "v1"
private const val FolderScopeDelimiter = "\u0001"
private const val HomeUiStateFixedFieldCount = 14
private const val HomeUiStateLegacyFixedFieldCount = 13

internal fun saveHomeUiState(state: HomeUiState): List<String> = saveHomeUiStateValue(state)

internal fun restoreHomeUiState(saved: List<String>): HomeUiState? = restoreHomeUiStateValue(saved)

internal val HomeUiStateSaver = Saver<HomeUiState, Any>(
    save = { state -> saveHomeUiStateValue(state) },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        restoreHomeUiStateValue(saved as List<String>) ?: HomeUiState()
    },
)

private fun saveHomeUiStateValue(state: HomeUiState): List<String> =
    listOf(HomeUiStateSaveVersion) + listOf(
        state.section.name,
        state.activePlaylistId.orEmpty(),
        state.searchOpen.toString(),
        state.searchQuery,
        state.returnSection.name,
        state.folderVisibleDepth.toString(),
        state.folderVisibleScope.joinToString(FolderScopeDelimiter),
        state.browseSort.albumSortField.storageValue,
        state.browseSort.albumSortDirection.storageValue,
        state.browseSort.albumGridColumns.toString(),
        state.browseSort.artistSortField.storageValue,
        state.browseSort.artistSortDirection.storageValue,
        state.browseSort.artistGridColumns.toString(),
    ) + saveBrowseDestinationForHomeState(state.browseDestination)

private fun restoreHomeUiStateValue(saved: List<String>): HomeUiState? {
    val version = saved.firstOrNull()
    if (version != HomeUiStateSaveVersion && version != HomeUiStateLegacySaveVersion) {
        return HomeUiState()
    }
    val isLegacy = version == HomeUiStateLegacySaveVersion
    val browseSaved = saved.drop(if (isLegacy) HomeUiStateLegacyFixedFieldCount else HomeUiStateFixedFieldCount)
    val artistSortFieldIndex = if (isLegacy) null else 11
    val artistSortDirectionIndex = if (isLegacy) 11 else 12
    val artistGridColumnsIndex = if (isLegacy) 12 else 13
    return HomeUiState(
        section = saved.getOrNull(1)?.let { runCatching { HomeSection.valueOf(it) }.getOrNull() }
            ?: HomeSection.Songs,
        activePlaylistId = saved.getOrNull(2)?.takeIf { it.isNotEmpty() },
        searchOpen = saved.getOrNull(3).toBoolean(),
        searchQuery = saved.getOrNull(4).orEmpty(),
        returnSection = saved.getOrNull(5)?.let { runCatching { HomeSection.valueOf(it) }.getOrNull() }
            ?: HomeSection.Songs,
        folderVisibleDepth = saved.getOrNull(6)?.toIntOrNull() ?: 0,
        folderVisibleScope = saved.getOrNull(7)
            ?.takeIf { it.isNotEmpty() }
            ?.split(FolderScopeDelimiter)
            .orEmpty(),
        browseSort = HomeBrowseSortState(
            albumSortField = AlbumBrowseSortField.fromStorage(saved.getOrNull(8)),
            albumSortDirection = SortDirection.fromStorage(saved.getOrNull(9)),
            albumGridColumns = saved.getOrNull(10)?.toIntOrNull()?.coerceIn(1, 4) ?: 2,
            artistSortField = ArtistBrowseSortField.fromStorage(artistSortFieldIndex?.let { saved.getOrNull(it) }),
            artistSortDirection = SortDirection.fromStorage(saved.getOrNull(artistSortDirectionIndex)),
            artistGridColumns = saved.getOrNull(artistGridColumnsIndex)?.toIntOrNull()?.coerceIn(1, 4) ?: 2,
        ),
        browseDestination = restoreBrowseDestinationForHomeState(browseSaved),
    )
}

private fun saveBrowseDestinationForHomeState(destination: BrowseDestination): List<String> =
    when (destination) {
        BrowseDestination.Root -> listOf("root", "")
        is BrowseDestination.Artist -> listOf("artist", destination.name)
        is BrowseDestination.Album -> listOf("album", destination.title)
        is BrowseDestination.Folder -> listOf(
            "folder",
            destination.depth.toString(),
        ) + destination.scopePathSegments
    }

private fun restoreBrowseDestinationForHomeState(saved: List<String>): BrowseDestination =
    when (saved.getOrNull(0)) {
        "artist" -> BrowseDestination.Artist(saved.getOrNull(1).orEmpty())
        "album" -> BrowseDestination.Album(saved.getOrNull(1).orEmpty())
        "folder" -> BrowseDestination.Folder(
            depth = saved.getOrNull(1)?.toIntOrNull() ?: 0,
            scopePathSegments = saved.drop(2),
        )
        else -> BrowseDestination.Root
    }

private fun String?.toBoolean(): Boolean = this == "true"
