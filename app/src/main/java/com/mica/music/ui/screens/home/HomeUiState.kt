package com.mica.music.ui.screens.home

import com.mica.music.data.AlbumBrowseKey
import android.content.Context
import androidx.compose.runtime.saveable.Saver
import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.FolderBrowseMode
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.SortDirection

data class HomeUiState(
    val section: HomeSection = HomeSection.Songs,
    val activePlaylistId: String? = null,
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val browseDestination: BrowseDestination = BrowseDestination.Root,
    val browseStack: List<BrowseStackFrame> = emptyList(),
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
        folderBrowseMode = FolderBrowseMode.HIERARCHY,
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
        browseStack = browseStack,
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
        browseStack = snapshot.browseStack,
        returnSection = snapshot.returnSection,
        activePlaylistId = snapshot.activePlaylistId,
    )

    companion object {
        fun initial(context: Context): HomeUiState {
            val (section, playlistId) = restoreHomeLocation(
                LibraryBrowseSettings.lastHomeSection(context),
                LibraryBrowseSettings.lastHomePlaylistId(context),
                LibraryBrowseSettings.remoteLibrarySidebarEnabled(context),
            )
            return HomeUiState(
                section = section,
                activePlaylistId = playlistId,
                browseSort = HomeBrowseSortState(
                    albumSortField = LibraryBrowseSettings.albumBrowseSortField(context),
                    albumSortDirection = LibraryBrowseSettings.albumBrowseSortDirection(context),
                    albumGridColumns = LibraryBrowseSettings.albumBrowseGridColumns(context),
                    artistSortField = LibraryBrowseSettings.artistBrowseSortField(context),
                    artistSortDirection = LibraryBrowseSettings.artistBrowseSortDirection(context),
                    artistGridColumns = LibraryBrowseSettings.artistBrowseGridColumns(context),
                    folderBrowseMode = LibraryBrowseSettings.folderBrowseMode(context),
                ),
            )
        }
    }
}

internal fun restoreHomeLocation(
    sectionValue: String?,
    playlistId: String?,
    remoteLibraryEnabled: Boolean = false,
): Pair<HomeSection, String?> {
    val section = sectionValue
        ?.let { runCatching { HomeSection.valueOf(it) }.getOrNull() }
        ?: return HomeSection.Songs to null
    return when (section) {
        HomeSection.Songs,
        HomeSection.Artists,
        HomeSection.Albums,
        HomeSection.Folders -> section to null
        HomeSection.Remote -> if (remoteLibraryEnabled) section to null else HomeSection.Songs to null
        HomeSection.Playlist -> HomeSection.Playlist to playlistId?.takeIf(String::isNotBlank)
        else -> HomeSection.Songs to null
    }
}

private const val HomeUiStateSaveVersion = "v4"
private const val HomeUiStateLegacySaveVersionV3 = "v3"
private const val HomeUiStateLegacySaveVersionV2 = "v2"
private const val HomeUiStateLegacySaveVersionV1 = "v1"
private const val FolderScopeDelimiter = "\u0001"
private const val HomeUiStateFixedFieldCount = 15
private const val HomeUiStateLegacyFixedFieldCountV2V3 = 14
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

private fun saveHomeUiStateValue(state: HomeUiState): List<String> {
    val destination = saveBrowseDestinationForHomeState(state.browseDestination)
    val stack = state.browseStack.flatMap(::saveBrowseStackFrame)
    return listOf(HomeUiStateSaveVersion) + listOf(
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
        state.browseSort.folderBrowseMode.storageValue,
    ) + listOf(destination.size.toString()) +
        destination +
        listOf(state.browseStack.size.toString()) +
        stack
}

private fun restoreHomeUiStateValue(saved: List<String>): HomeUiState? {
    val version = saved.firstOrNull()
    if (
        version != HomeUiStateSaveVersion &&
        version != HomeUiStateLegacySaveVersionV3 &&
        version != HomeUiStateLegacySaveVersionV2 &&
        version != HomeUiStateLegacySaveVersionV1
    ) {
        return HomeUiState()
    }
    val isLegacyV1 = version == HomeUiStateLegacySaveVersionV1
    val isV3OrNewer = version == HomeUiStateSaveVersion || version == HomeUiStateLegacySaveVersionV3
    val browseSaved = saved.drop(
        when {
            isLegacyV1 -> HomeUiStateLegacyFixedFieldCount
            version == HomeUiStateSaveVersion -> HomeUiStateFixedFieldCount
            else -> HomeUiStateLegacyFixedFieldCountV2V3
        },
    )
    val artistSortFieldIndex = if (isLegacyV1) null else 11
    val artistSortDirectionIndex = if (isLegacyV1) 11 else 12
    val artistGridColumnsIndex = if (isLegacyV1) 12 else 13
    val (browseDestination, browseStack) = if (isV3OrNewer) {
        restoreBrowseNavigationForV3(browseSaved)
    } else {
        restoreBrowseDestinationForHomeState(browseSaved) to emptyList()
    }
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
            folderBrowseMode = if (version == HomeUiStateSaveVersion) {
                FolderBrowseMode.fromStorage(saved.getOrNull(14))
            } else {
                FolderBrowseMode.HIERARCHY
            },
        ),
        browseDestination = browseDestination,
        browseStack = browseStack,
    )
}

private fun restoreBrowseNavigationForV3(
    saved: List<String>,
): Pair<BrowseDestination, List<BrowseStackFrame>> {
    val destinationSize = saved.firstOrNull()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val destinationSaved = saved.drop(1).take(destinationSize)
    val afterDestination = saved.drop(1 + destinationSize)
    val stackSize = afterDestination.firstOrNull()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    var rest = afterDestination.drop(1)
    val frames = ArrayList<BrowseStackFrame>(stackSize)
    repeat(stackSize) {
        val (frame, consumed) = restoreBrowseStackFrame(rest) ?: return@repeat
        frames += frame
        rest = rest.drop(consumed)
    }
    return restoreBrowseDestinationForHomeState(destinationSaved) to frames
}

private fun saveBrowseStackFrame(frame: BrowseStackFrame): List<String> {
    val destination = saveBrowseDestinationForHomeState(frame.browseDestination)
    return listOf(
        frame.section.name,
        frame.searchOpen.toString(),
        frame.searchQuery,
        frame.activePlaylistId.orEmpty(),
        destination.size.toString(),
    ) + destination
}

private fun restoreBrowseStackFrame(saved: List<String>): Pair<BrowseStackFrame, Int>? {
    if (saved.size < 5) return null
    val destinationSize = saved[4].toIntOrNull()?.coerceAtLeast(0) ?: return null
    val consumed = 5 + destinationSize
    if (saved.size < consumed) return null
    val destination = restoreBrowseDestinationForHomeState(saved.drop(5).take(destinationSize))
    val frame = BrowseStackFrame(
        section = runCatching { HomeSection.valueOf(saved[0]) }.getOrNull() ?: HomeSection.Songs,
        searchOpen = saved[1].toBoolean(),
        searchQuery = saved[2],
        activePlaylistId = saved[3].takeIf { it.isNotEmpty() },
        browseDestination = destination,
    )
    return frame to consumed
}

private fun saveBrowseDestinationForHomeState(destination: BrowseDestination): List<String> =
    when (destination) {
        BrowseDestination.Root -> listOf("root", "")
        is BrowseDestination.Artist -> listOf("artist", destination.name)
        is BrowseDestination.Album -> if (destination.key.legacyTitleOnly) {
            listOf("album", destination.title)
        } else {
            listOf("album", destination.key.title, destination.key.albumArtist)
        }
        is BrowseDestination.Folder -> listOf(
            "folder",
            destination.depth.toString(),
        ) + destination.scopePathSegments
    }

private fun restoreBrowseDestinationForHomeState(saved: List<String>): BrowseDestination =
    when (saved.getOrNull(0)) {
        "artist" -> BrowseDestination.Artist(saved.getOrNull(1).orEmpty())
        "album" -> if (saved.size >= 3) {
            BrowseDestination.Album(
                AlbumBrowseKey(
                    title = saved.getOrNull(1).orEmpty(),
                    albumArtist = saved.getOrNull(2).orEmpty(),
                ),
            )
        } else {
            BrowseDestination.Album(saved.getOrNull(1).orEmpty())
        }
        "folder" -> BrowseDestination.Folder(
            depth = saved.getOrNull(1)?.toIntOrNull() ?: 0,
            scopePathSegments = saved.drop(2),
        )
        else -> BrowseDestination.Root
    }

private fun String?.toBoolean(): Boolean = this == "true"
