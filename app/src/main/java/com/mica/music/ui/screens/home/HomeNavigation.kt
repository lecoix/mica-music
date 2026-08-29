package com.mica.music.ui.screens.home

import com.mica.music.data.AlbumBrowseKey
internal sealed interface HomePaneKey {
    data object Search : HomePaneKey
    data object Songs : HomePaneKey
    data object Remote : HomePaneKey
    data object Analysis : HomePaneKey
    data object Folders : HomePaneKey
    data object PlaylistOverview : HomePaneKey
    data class Playlist(val id: String) : HomePaneKey
    data class Browse(
        val section: HomeSection,
        val destination: BrowseDestination,
    ) : HomePaneKey
}

/** 浏览返回栈帧：离开当前页面前保存的可恢复导航态（不含文件夹 depth 链）。 */
data class BrowseStackFrame(
    val section: HomeSection,
    val browseDestination: BrowseDestination = BrowseDestination.Root,
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val activePlaylistId: String? = null,
)

data class HomeNavigationSnapshot(
    val section: HomeSection,
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val browseDestination: BrowseDestination = BrowseDestination.Root,
    val browseStack: List<BrowseStackFrame> = emptyList(),
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
    HomePaneKey.Remote -> homeSectionOrder(HomeSection.Remote) * 10
    is HomePaneKey.Browse -> homeSectionOrder(key.section) * 10 + browseDestinationDepth(key.destination)
    HomePaneKey.Folders -> homeSectionOrder(HomeSection.Folders) * 10
    HomePaneKey.Analysis -> homeSectionOrder(HomeSection.LibraryAnalysis) * 10
    HomePaneKey.PlaylistOverview -> homeSectionOrder(HomeSection.Playlist) * 10
    is HomePaneKey.Playlist -> homeSectionOrder(HomeSection.Playlist) * 10 + 1
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
    HomeSection.Remote -> 4
    HomeSection.Recent -> 5
    HomeSection.Playlist -> 6
    HomeSection.LibraryAnalysis -> 7
    HomeSection.Settings -> 8
}

internal fun resolveHomePaneKey(
    searchOpen: Boolean,
    section: HomeSection,
    activePlaylistId: String?,
    browseDestination: BrowseDestination,
): HomePaneKey = when {
    searchOpen -> HomePaneKey.Search
    section == HomeSection.Songs -> HomePaneKey.Songs
    section == HomeSection.Remote -> HomePaneKey.Remote
    section == HomeSection.Folders -> HomePaneKey.Folders
    section == HomeSection.LibraryAnalysis -> HomePaneKey.Analysis
    section == HomeSection.Playlist && activePlaylistId == null -> HomePaneKey.PlaylistOverview
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
        snapshot.browseStack.isNotEmpty() ||
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

fun browseStackFrameFrom(snapshot: HomeNavigationSnapshot): BrowseStackFrame =
    BrowseStackFrame(
        section = snapshot.section,
        browseDestination = snapshot.browseDestination,
        searchOpen = snapshot.searchOpen,
        searchQuery = snapshot.searchQuery,
        activePlaylistId = snapshot.activePlaylistId,
    )

/**
 * 压入当前页并进入目标浏览页。文件夹分区不使用浏览栈（仍靠 depth）。
 * 外部入口请用 [consumeNavigationIntent]（清栈）。
 */
fun pushBrowseDestination(
    snapshot: HomeNavigationSnapshot,
    destination: BrowseDestination,
    section: HomeSection = snapshot.section,
): HomeNavigationSnapshot {
    if (section == HomeSection.Folders || destination is BrowseDestination.Folder) {
        return snapshot.copy(section = section, browseDestination = destination)
    }
    if (snapshot.section == section && snapshot.browseDestination == destination) {
        return snapshot
    }
    return snapshot.copy(
        browseStack = snapshot.browseStack + browseStackFrameFrom(snapshot),
        section = section,
        browseDestination = destination,
        searchOpen = false,
        searchQuery = "",
        activePlaylistId = null,
    )
}

/** 从歌单总览进入详情；只有此入口把总览保留为返回目标。 */
fun navigateToPlaylistFromOverview(
    snapshot: HomeNavigationSnapshot,
    playlistId: String,
): HomeNavigationSnapshot =
    snapshot.copy(
        browseStack = snapshot.browseStack + browseStackFrameFrom(snapshot),
        section = HomeSection.Playlist,
        browseDestination = BrowseDestination.Root,
        searchOpen = false,
        searchQuery = "",
        activePlaylistId = playlistId,
    )

fun navigateBack(snapshot: HomeNavigationSnapshot): HomeNavigationBackResult = when {
    snapshot.songMultiSelectActive -> HomeNavigationBackResult(
        snapshot.copy(songMultiSelectActive = false, selectedSongIds = emptySet()),
    )
    snapshot.searchOpen -> HomeNavigationBackResult(
        snapshot.copy(searchOpen = false, searchQuery = ""),
        hideKeyboard = true,
    )
    snapshot.browseStack.isNotEmpty() -> {
        val frame = snapshot.browseStack.last()
        HomeNavigationBackResult(
            snapshot.copy(
                browseStack = snapshot.browseStack.dropLast(1),
                section = frame.section,
                browseDestination = frame.browseDestination,
                searchOpen = frame.searchOpen,
                searchQuery = frame.searchQuery,
                activePlaylistId = frame.activePlaylistId,
            ),
        )
    }
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
        browseStack = emptyList(),
        section = intent.section,
        browseDestination = intent.browseDestination,
    )

fun navigateToAlbum(
    snapshot: HomeNavigationSnapshot,
    albumKey: AlbumBrowseKey,
): HomeNavigationSnapshot =
    pushBrowseDestination(
        snapshot = snapshot,
        destination = BrowseDestination.Album(albumKey),
        section = HomeSection.Albums,
    )

fun navigateToAlbum(
    snapshot: HomeNavigationSnapshot,
    albumTitle: String,
): HomeNavigationSnapshot = navigateToAlbum(snapshot, AlbumBrowseKey.legacyTitleOnly(albumTitle))

fun navigateToArtist(
    snapshot: HomeNavigationSnapshot,
    artistName: String,
): HomeNavigationSnapshot =
    pushBrowseDestination(
        snapshot = snapshot,
        destination = BrowseDestination.Artist(artistName),
        section = HomeSection.Artists,
    )

fun shouldClearSongMultiSelect(section: HomeSection, searchOpen: Boolean): Boolean =
    section != HomeSection.Songs || searchOpen
