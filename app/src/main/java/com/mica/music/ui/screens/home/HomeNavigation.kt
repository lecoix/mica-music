package com.mica.music.ui.screens.home

internal sealed interface HomePaneKey {
    data object Search : HomePaneKey
    data object Songs : HomePaneKey
    data object Analysis : HomePaneKey
    data object Folders : HomePaneKey
    data class Playlist(val id: String) : HomePaneKey
    data class Browse(
        val section: HomeSection,
        val destination: BrowseDestination,
    ) : HomePaneKey
}

data class HomeNavigationSnapshot(
    val section: HomeSection,
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val browseDestination: BrowseDestination = BrowseDestination.Root,
    val returnSection: HomeSection = HomeSection.Songs,
    val activePlaylistId: String? = null,
    val songMultiSelectActive: Boolean = false,
    val selectedSongIds: Set<String> = emptySet(),
)

data class HomeNavigationBackResult(
    val snapshot: HomeNavigationSnapshot,
    val hideKeyboard: Boolean = false,
)

/** 主页分区栈深度：用于前进/返回滑动方向。 */
internal fun homePaneDepth(key: HomePaneKey): Int = when (key) {
    HomePaneKey.Songs, HomePaneKey.Search -> 0
    is HomePaneKey.Browse -> homeSectionOrder(key.section) * 10 + browseDestinationDepth(key.destination)
    HomePaneKey.Folders -> homeSectionOrder(HomeSection.Folders) * 10
    HomePaneKey.Analysis -> homeSectionOrder(HomeSection.LibraryAnalysis) * 10
    is HomePaneKey.Playlist -> homeSectionOrder(HomeSection.Playlist) * 10
}

internal fun browseDestinationDepth(destination: BrowseDestination): Int = when (destination) {
    BrowseDestination.Root -> 0
    is BrowseDestination.Folder -> 1 + destination.depth
    else -> 1
}

internal fun homeSectionOrder(section: HomeSection): Int = when (section) {
    HomeSection.Songs -> 0
    HomeSection.Artists -> 1
    HomeSection.Albums -> 2
    HomeSection.Folders -> 3
    HomeSection.Recent -> 4
    HomeSection.Playlist -> 5
    HomeSection.LibraryAnalysis -> 6
    HomeSection.Settings -> 7
}

internal fun resolveHomePaneKey(
    searchOpen: Boolean,
    section: HomeSection,
    activePlaylistId: String?,
    browseDestination: BrowseDestination,
): HomePaneKey = when {
    searchOpen -> HomePaneKey.Search
    section == HomeSection.Songs -> HomePaneKey.Songs
    section == HomeSection.Folders -> HomePaneKey.Folders
    section == HomeSection.LibraryAnalysis -> HomePaneKey.Analysis
    section == HomeSection.Playlist && activePlaylistId != null -> HomePaneKey.Playlist(activePlaylistId)
    section == HomeSection.Artists ||
        section == HomeSection.Albums ||
        section == HomeSection.Recent ->
        HomePaneKey.Browse(section, browseDestination)
    else -> HomePaneKey.Songs
}

fun canNavigateBack(snapshot: HomeNavigationSnapshot): Boolean =
    snapshot.songMultiSelectActive ||
        snapshot.searchOpen ||
        snapshot.browseDestination != BrowseDestination.Root ||
        snapshot.section == HomeSection.Recent ||
        snapshot.section == HomeSection.LibraryAnalysis

fun showFolderMenuButton(section: HomeSection, searchOpen: Boolean): Boolean =
    section == HomeSection.Folders && !searchOpen

fun visibleBrowseDestination(
    section: HomeSection,
    browseDestination: BrowseDestination,
    folderVisibleDepth: Int,
    folderVisibleScope: List<String>,
): BrowseDestination = if (section == HomeSection.Folders) {
    BrowseDestination.Folder(
        depth = folderVisibleDepth,
        scopePathSegments = folderVisibleScope,
    )
} else {
    browseDestination
}

fun navigateBrowseBack(destination: BrowseDestination): BrowseDestination = when (destination) {
    is BrowseDestination.Folder -> {
        val previousDepth = destination.depth - 1
        if (previousDepth < 0) {
            BrowseDestination.Root
        } else {
            BrowseDestination.Folder(
                depth = previousDepth,
                scopePathSegments = destination.scopePathSegments.take(previousDepth),
            )
        }
    }
    else -> BrowseDestination.Root
}

fun navigateBack(snapshot: HomeNavigationSnapshot): HomeNavigationBackResult = when {
    snapshot.songMultiSelectActive -> HomeNavigationBackResult(
        snapshot.copy(songMultiSelectActive = false, selectedSongIds = emptySet()),
    )
    snapshot.searchOpen -> HomeNavigationBackResult(
        snapshot.copy(searchOpen = false, searchQuery = ""),
        hideKeyboard = true,
    )
    snapshot.browseDestination != BrowseDestination.Root -> HomeNavigationBackResult(
        snapshot.copy(browseDestination = navigateBrowseBack(snapshot.browseDestination)),
    )
    snapshot.section == HomeSection.Recent || snapshot.section == HomeSection.LibraryAnalysis ->
        HomeNavigationBackResult(
            snapshot.copy(section = snapshot.returnSection, activePlaylistId = null),
        )
    else -> HomeNavigationBackResult(snapshot)
}

fun consumeNavigationIntent(
    snapshot: HomeNavigationSnapshot,
    intent: HomeNavigationIntent,
): HomeNavigationSnapshot =
    snapshot.copy(
        searchOpen = false,
        searchQuery = "",
        activePlaylistId = null,
        section = intent.section,
        browseDestination = intent.browseDestination,
    )

fun navigateToAlbum(
    snapshot: HomeNavigationSnapshot,
    albumTitle: String,
): HomeNavigationSnapshot =
    snapshot.copy(
        section = HomeSection.Albums,
        browseDestination = BrowseDestination.Album(albumTitle),
    )

fun shouldClearSongMultiSelect(section: HomeSection, searchOpen: Boolean): Boolean =
    section != HomeSection.Songs || searchOpen
