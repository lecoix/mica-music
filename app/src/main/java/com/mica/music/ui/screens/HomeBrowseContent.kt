package com.mica.music.ui.screens

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.imaging.CoverDecodeTarget
import androidx.compose.ui.unit.sp
import com.mica.music.data.AlbumBrowseKey
import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.ArtistNames
import com.mica.music.data.BrowseGroup
import com.mica.music.data.BrowseGroupPresentation
import com.mica.music.data.BrowseListInfoVisibility
import com.mica.music.data.FolderBrowseGroup
import com.mica.music.data.FolderBrowseMode
import com.mica.music.data.LibraryBrowseDetails
import com.mica.music.data.MusicLibrary
import com.mica.music.data.ReleaseDates
import com.mica.music.data.Song
import com.mica.music.data.SongDetails
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.data.SongTrailingInfo
import com.mica.music.data.SortDirection
import com.mica.music.data.preferences.LibraryZoomPage
import com.mica.music.data.preferences.LibraryZoomPreferences
import com.mica.music.ui.components.AlphabetFastScroller
import com.mica.music.ui.components.EmptyStatePresets
import com.mica.music.ui.components.BrowseGroupRow
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.components.SongListPanel
import com.mica.music.ui.components.SongRow
import com.mica.music.ui.components.songListColumnsFor
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.zoom.PinchZoomItemRect
import com.mica.music.ui.zoom.compensatedTextMeasureWidth
import com.mica.music.ui.zoom.visiblePinchZoomItemRects
import com.mica.music.ui.zoom.calculatePinchZoomItemMorph
import com.mica.music.ui.zoom.pinchZoomItemBoundsMorph
import com.mica.music.ui.zoom.pinchZoomGesture
import com.mica.music.ui.zoom.rememberPinchZoomGridAnchorCoordinator
import com.mica.music.ui.zoom.rememberPinchZoomState
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.coverColor
import com.mica.music.ui.screens.home.BrowseDestination
import com.mica.music.ui.screens.home.HomeSection
import com.mica.music.ui.screens.home.browseDestinationDepth
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.flow.collectLatest

private const val BrowseDetailDebugTag = "[DEBUG-BROWSE-DETAIL-4F7C]"

private val FolderSongInfoVisibility = SongListInfoVisibility(showSongPlayCount = false)
private val RecentSongInfoVisibility = FolderSongInfoVisibility.copy(
    trailingInfo = SongTrailingInfo.PLAY_COUNT,
)

@Composable
internal fun HomeBrowseContent(
    section: HomeSection,
    destination: BrowseDestination,
    onDestinationChange: (BrowseDestination) -> Unit,
    onFolderPageChange: (depth: Int, scopePathSegments: List<String>) -> Unit = { _, _ -> },
    library: MusicLibrary,
    currentSongId: String?,
    isPlaying: Boolean,
    onQueueSongs: (List<Song>) -> Unit,
    onAppendSongsToQueue: (List<Song>) -> Unit = {},
    onAddSongsToPlaylist: (List<Song>) -> Unit = {},
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    onAlbumClick: (AlbumBrowseKey) -> Unit = {},
    albumSortField: AlbumBrowseSortField = AlbumBrowseSortField.TITLE,
    albumSortDirection: SortDirection = SortDirection.ASC,
    albumGridColumns: Int = 1,
    onAlbumGridColumnsChange: (Int) -> Unit = {},
    browseListInfoVisibility: BrowseListInfoVisibility = BrowseListInfoVisibility(),
    artistSortField: ArtistBrowseSortField = ArtistBrowseSortField.TITLE,
    artistSortDirection: SortDirection = SortDirection.ASC,
    artistGridColumns: Int = 1,
    onArtistGridColumnsChange: (Int) -> Unit = {},
    folderBrowseMode: FolderBrowseMode = FolderBrowseMode.HIERARCHY,
    artistListState: LazyListState,
    artistGridState: LazyGridState,
    albumListState: LazyListState,
    albumGridState: LazyGridState,
    listBottomPadding: Dp = 0.dp,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val folderListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    if (library.isLoadingCachedLibrary && library.songs.isEmpty()) {
        EmptyStatePresets.Scanning(progressLabel = "正在加载本地曲库…")
        return
    }

    if (!library.hasScanned && library.songs.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "请先扫描曲库",
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textTertiary,
            )
        }
        return
    }

    when (section) {
        HomeSection.Artists -> {
            AnimatedContent(
                targetState = destination,
                modifier = modifier,
                transitionSpec = MicaMotion.directionalPaneTransition(motionEnabled, ::browseDestinationDepth),
                label = "artistBrowse",
            ) { dest ->
                when (dest) {
                    is BrowseDestination.Root -> {
                        ArtistGroupList(
                            library = library,
                            listState = artistListState,
                            gridState = artistGridState,
                            onSelect = { onDestinationChange(BrowseDestination.Artist(it)) },
                            sortField = artistSortField,
                            sortDirection = artistSortDirection,
                            gridColumns = artistGridColumns,
                            onGridColumnsChange = onArtistGridColumnsChange,
                            motionEnabled = motionEnabled,
                            listBottomPadding = listBottomPadding,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    is BrowseDestination.Artist -> {
                        val songListState = rememberBrowseDetailSongListState("artist:${dest.name}")
                        val songs = timedBrowseDetail("artist filter", "artist=${dest.name}", library.songs.size) {
                            library.songsForArtist(dest.name)
                        }
                        ArtistDetailPanel(
                            artistName = dest.name,
                            songs = songs,
                            currentSongId = currentSongId,
                            isPlaying = isPlaying,
                            onQueueSongs = onQueueSongs,
                            onAppendSongsToQueue = onAppendSongsToQueue,
                            onAddSongsToPlaylist = onAddSongsToPlaylist,
                            onSongClick = onSongClick,
                            onSongOpenMenu = onSongOpenMenu,
                            onAlbumClick = onAlbumClick,
                            emptyMessage = "该歌手下暂无歌曲",
                            listState = songListState,
                            listBottomPadding = listBottomPadding,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> Unit
                }
            }
        }
        HomeSection.Albums -> {
            AnimatedContent(
                targetState = destination,
                modifier = modifier,
                transitionSpec = MicaMotion.directionalPaneTransition(motionEnabled, ::browseDestinationDepth),
                label = "albumBrowse",
            ) { dest ->
                when (dest) {
                    is BrowseDestination.Root -> {
                        AlbumGroupList(
                            library = library,
                            listState = albumListState,
                            gridState = albumGridState,
                            onSelect = { onDestinationChange(BrowseDestination.Album(it)) },
                            sortField = albumSortField,
                            sortDirection = albumSortDirection,
                            gridColumns = albumGridColumns,
                            onGridColumnsChange = onAlbumGridColumnsChange,
                            infoVisibility = browseListInfoVisibility,
                            motionEnabled = motionEnabled,
                            listBottomPadding = listBottomPadding,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    is BrowseDestination.Album -> {
                        val songListState = rememberBrowseDetailSongListState("album:${dest.key.storageKey}")
                        val songs = timedBrowseDetail("album filter", "album=${dest.key.storageKey}", library.songs.size) {
                            library.songsForAlbum(dest.key)
                        }
                        AlbumDetailPanel(
                            albumTitle = dest.key.title,
                            songs = songs,
                            currentSongId = currentSongId,
                            isPlaying = isPlaying,
                            onQueueSongs = onQueueSongs,
                            onAppendSongsToQueue = onAppendSongsToQueue,
                            onAddSongsToPlaylist = onAddSongsToPlaylist,
                            onSongClick = onSongClick,
                            onSongOpenMenu = onSongOpenMenu,
                            emptyMessage = "该专辑下暂无歌曲",
                            listState = songListState,
                            listBottomPadding = listBottomPadding,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> Unit
                }
            }
        }
        HomeSection.Recent -> {
            val songs = library.recentSongs()
            SongListPanel(
                songs = songs,
                library = library,
                currentSongId = currentSongId,
                isPlaying = isPlaying,
                onSongClick = { songId ->
                    onQueueSongs(songs)
                    onSongClick(songId)
                },
                onSongOpenMenu = onSongOpenMenu,
                fastScrollSortField = null,
                emptyMessage = "暂无播放记录",
                infoVisibility = RecentSongInfoVisibility,
                listBottomPadding = listBottomPadding,
                zoomPage = LibraryZoomPage.RECENT,
                modifier = modifier,
            )
        }
        HomeSection.Folders -> {
            when (folderBrowseMode) {
                FolderBrowseMode.MUSIC_FOLDERS -> MusicFoldersBrowse(
                    destination = destination,
                    library = library,
                    currentSongId = currentSongId,
                    isPlaying = isPlaying,
                    folderListState = folderListState,
                    onFolderPageChange = onFolderPageChange,
                    onDestinationChange = onDestinationChange,
                    onQueueSongs = onQueueSongs,
                    onSongClick = onSongClick,
                    onSongOpenMenu = onSongOpenMenu,
                    listBottomPadding = listBottomPadding,
                    modifier = modifier,
                )
                FolderBrowseMode.HIERARCHY -> HierarchyFoldersBrowse(
                    destination = destination,
                    library = library,
                    currentSongId = currentSongId,
                    isPlaying = isPlaying,
                    folderListState = folderListState,
                    onFolderPageChange = onFolderPageChange,
                    onDestinationChange = onDestinationChange,
                    onQueueSongs = onQueueSongs,
                    onSongClick = onSongClick,
                    onSongOpenMenu = onSongOpenMenu,
                    listBottomPadding = listBottomPadding,
                    modifier = modifier,
                )
            }
        }
        else -> Unit
    }
}

@Composable
private fun MusicFoldersBrowse(
    destination: BrowseDestination,
    library: MusicLibrary,
    currentSongId: String?,
    isPlaying: Boolean,
    folderListState: LazyListState,
    onFolderPageChange: (depth: Int, scopePathSegments: List<String>) -> Unit,
    onDestinationChange: (BrowseDestination) -> Unit,
    onQueueSongs: (List<Song>) -> Unit,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    listBottomPadding: Dp,
    modifier: Modifier,
) {
    val scopePathSegments = (destination as? BrowseDestination.Folder)?.scopePathSegments.orEmpty()
    if (scopePathSegments.isEmpty()) {
        val groups = library.musicFolderGroups()
        if (groups.isEmpty()) {
            EmptyBrowseHint("暂无音乐文件夹", modifier)
            return
        }
        FolderContentList(
            groups = groups,
            songs = emptyList(),
            currentSongId = currentSongId,
            isPlaying = isPlaying,
            listState = folderListState,
            onSelect = { group ->
                // depth=0 + non-empty scope: one system-back returns to Root (see navigateBrowseBack).
                onFolderPageChange(0, group.pathSegments)
                onDestinationChange(
                    BrowseDestination.Folder(
                        depth = 0,
                        scopePathSegments = group.pathSegments,
                    ),
                )
            },
            onSongClick = onSongClick,
            onSongOpenMenu = onSongOpenMenu,
            listBottomPadding = listBottomPadding,
            fastScrollLabels = groups.map { it.title },
            forceListLayout = true,
            modifier = modifier,
        )
        return
    }

    val songs = library.songsInFolder(scopePathSegments)
    SongListPanel(
        songs = songs,
        library = library,
        currentSongId = currentSongId,
        isPlaying = isPlaying,
        onSongClick = { songId ->
            onQueueSongs(songs)
            onSongClick(songId)
        },
        onSongOpenMenu = onSongOpenMenu,
        emptyMessage = "该文件夹下暂无歌曲",
        infoVisibility = FolderSongInfoVisibility,
        listBottomPadding = listBottomPadding,
        zoomPage = LibraryZoomPage.FOLDERS,
        modifier = modifier,
    )
}

@Composable
private fun HierarchyFoldersBrowse(
    destination: BrowseDestination,
    library: MusicLibrary,
    currentSongId: String?,
    isPlaying: Boolean,
    folderListState: LazyListState,
    onFolderPageChange: (depth: Int, scopePathSegments: List<String>) -> Unit,
    onDestinationChange: (BrowseDestination) -> Unit,
    onQueueSongs: (List<Song>) -> Unit,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    listBottomPadding: Dp,
    modifier: Modifier,
) {
    val folderDestination = destination as? BrowseDestination.Folder
        ?: BrowseDestination.Folder(depth = 0)
    val maxFolderDepth = library.maxFolderDepth()
    val pageCount = when {
        maxFolderDepth <= 0 -> 1
        folderDestination.scopePathSegments.isEmpty() -> maxFolderDepth
        else -> maxOf(maxFolderDepth, folderDestination.scopePathSegments.size + 1)
    }
    val currentPage = folderDestination.depth.coerceIn(0, pageCount - 1)
    val pagerState = rememberPagerState(initialPage = currentPage) { pageCount }
    val programmaticScroll = remember { mutableStateOf(false) }

    LaunchedEffect(currentPage, pageCount) {
        if (pagerState.currentPage != currentPage) {
            programmaticScroll.value = true
            try {
                pagerState.animateScrollToPage(currentPage)
            } finally {
                programmaticScroll.value = false
            }
        }
    }

    LaunchedEffect(pagerState, folderDestination.scopePathSegments) {
        snapshotFlow {
            Triple(
                pagerState.currentPage,
                pagerState.isScrollInProgress,
                programmaticScroll.value,
            )
        }.collect { (page, scrolling, isProgrammatic) ->
            if (!scrolling && !isProgrammatic && page != folderDestination.depth) {
                onDestinationChange(
                    BrowseDestination.Folder(
                        depth = page,
                        scopePathSegments = folderDestination.scopePathSegments.scopeForFolderDepth(page),
                    ),
                )
            }
        }
    }

    LaunchedEffect(pagerState, folderDestination.scopePathSegments) {
        snapshotFlow { pagerState.currentPage }
            .collect { page ->
                onFolderPageChange(
                    page,
                    folderDestination.scopePathSegments.scopeForFolderDepth(page),
                )
            }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
    ) { page ->
        val scopePathSegments = folderDestination.scopePathSegments.scopeForFolderDepth(page)
        FolderDepthPage(
            depth = page,
            scopePathSegments = scopePathSegments,
            library = library,
            currentSongId = currentSongId,
            isPlaying = isPlaying,
            onQueueSongs = onQueueSongs,
            rootListState = folderListState,
            onFolderSelect = { group ->
                onFolderPageChange(group.pathSegments.size, group.pathSegments)
                onDestinationChange(
                    BrowseDestination.Folder(
                        depth = group.pathSegments.size,
                        scopePathSegments = group.pathSegments,
                    ),
                )
            },
            onSongClick = onSongClick,
            onSongOpenMenu = onSongOpenMenu,
            listBottomPadding = listBottomPadding,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun rememberBrowseDetailSongListState(key: String): LazyListState =
    rememberSaveable(key, saver = LazyListState.Saver) { LazyListState() }

private inline fun <T> timedBrowseDetail(
    stage: String,
    target: String,
    inputCount: Int,
    block: () -> T,
): T {
    val startedMs = SystemClock.elapsedRealtime()
    return block().also { result ->
        val resultCount = when (result) {
            is Collection<*> -> result.size
            is LibraryBrowseDetails.AlbumDetail -> result.orderedSongs.size
            is BrowseGroupPresentation -> result.groups.size
            else -> -1
        }
        DiagnosticLog.event(
            "LibraryUi",
            "$BrowseDetailDebugTag stage=\"$stage\" durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                "input=$inputCount result=$resultCount $target",
        )
    }
}

private fun logBrowseGroupList(
    stage: String,
    groups: List<BrowseGroup>,
    gridColumns: Int,
    fastScrollLabels: List<String>?,
) {
    DiagnosticLog.event(
        "LibraryUi",
        "$BrowseDetailDebugTag stage=\"$stage\" groups=${groups.size} " +
            "gridColumns=${gridColumns.coerceIn(1, 4)} fastScrollLabels=${fastScrollLabels?.size ?: 0}",
    )
}

private fun List<String>.scopeForFolderDepth(depth: Int): List<String> = when {
    depth <= 0 -> emptyList()
    size > depth -> take(depth)
    else -> this
}

@Composable
private fun AlbumDetailPanel(
    albumTitle: String,
    songs: List<Song>,
    currentSongId: String?,
    isPlaying: Boolean,
    onQueueSongs: (List<Song>) -> Unit,
    onAppendSongsToQueue: (List<Song>) -> Unit,
    onAddSongsToPlaylist: (List<Song>) -> Unit,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    emptyMessage: String,
    listState: LazyListState,
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) {
        EmptyBrowseHint(emptyMessage, modifier)
        return
    }

    val detail = remember(songs) {
        timedBrowseDetail("album detail", "album=$albumTitle", songs.size) {
            LibraryBrowseDetails.albumDetail(songs)
        }
    }
    val orderedSongs = detail.orderedSongs
    val header: @Composable () -> Unit = {
        AlbumDetailHeader(
            albumTitle = albumTitle,
            songs = orderedSongs,
            onPlayAll = {
                onQueueSongs(orderedSongs)
                orderedSongs.firstOrNull()?.let { onSongClick(it.id) }
            },
            onAddToPlaylist = { onAddSongsToPlaylist(orderedSongs) },
            onAddToQueue = { onAppendSongsToQueue(orderedSongs) },
        )
    }
    val discLabel: @Composable (Int) -> Unit = { discNumber ->
        Text(
            text = "DISC $discNumber",
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.padding(
                start = HifiSpacing.lg,
                top = HifiSpacing.md,
                end = HifiSpacing.lg,
                bottom = HifiSpacing.xs,
            ),
        )
    }
    val songRow: @Composable (Int, Song) -> Unit = { trackIndex, song ->
        val isCurrent = currentSongId == song.id
        SongRow(
            song = song,
            trackNumber = song.trackNumber.takeIf { it > 0 }?.toString()?.padStart(2, '0')
                ?: (trackIndex + 1).toString().padStart(2, '0'),
            trailingLabel = song.durationLabel,
            isCurrent = isCurrent,
            isPlaying = isCurrent && isPlaying,
            showCover = false,
            subtitleOverride = ArtistNames.normalizeDisplay(song.artist),
            onClick = {
                onQueueSongs(orderedSongs)
                onSongClick(song.id)
            },
            onLongClick = { onSongOpenMenu(song) },
        )
    }
    val copyright: @Composable (String) -> Unit = { label ->
        Text(
            text = label,
            style = MicaTheme.typography.bodySm,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.padding(
                start = HifiSpacing.lg,
                top = HifiSpacing.md,
                end = HifiSpacing.lg,
                bottom = HifiSpacing.sm,
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }

    DetailZoomHost(
        page = LibraryZoomPage.ALBUM_DETAIL,
        listState = listState,
        listBottomPadding = listBottomPadding,
        modifier = modifier,
    ) { itemMorph ->
        item(key = "albumHeader", span = { GridItemSpan(maxLineSpan) }) {
            Box(modifier = itemMorph("albumHeader")) { header() }
        }
        detail.discSections.forEach { section ->
            section.discNumber?.let { discNumber ->
                item(
                    key = "albumDisc:$discNumber",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Box(modifier = itemMorph("albumDisc:$discNumber")) { discLabel(discNumber) }
                }
            }
            gridItemsIndexed(
                items = section.songs,
                key = { _, song -> "albumSong:${song.id}" },
            ) { trackIndex, song ->
                Box(modifier = itemMorph("albumSong:${song.id}")) {
                    songRow(trackIndex, song)
                }
            }
        }
        detail.copyright?.let { label ->
            item(key = "albumCopyright", span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = itemMorph("albumCopyright")) { copyright(label) }
            }
        }
    }

}

@Composable
private fun AlbumDetailHeader(
    albumTitle: String,
    songs: List<Song>,
    onPlayAll: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    val artworkSong = remember(songs) {
        songs.firstOrNull { !it.albumArtUri.isNullOrBlank() } ?: songs.first()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = HifiSpacing.lg,
                top = HifiSpacing.xl,
                end = HifiSpacing.lg,
                bottom = HifiSpacing.xl,
            ),
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val coverSize = maxWidth * 0.42f
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongCover(
                    albumArtUri = artworkSong.albumArtUri,
                    fallbackColor = artworkSong.coverColor,
                    contentDescription = albumTitle,
                    modifier = Modifier.size(coverSize),
                )
                Spacer(Modifier.width(HifiSpacing.lg))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(coverSize),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Text(
                        text = "ALBUM",
                        style = MicaTheme.typography.caption,
                        color = MicaTheme.colors.textTertiary,
                    )
                    Text(
                        text = albumTitle,
                        style = MicaTheme.typography.titleMd.copy(fontSize = 18.sp, lineHeight = 24.sp),
                        color = MicaTheme.colors.textPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = albumStatsLine(songs),
                        style = MicaTheme.typography.monoSm,
                        color = MicaTheme.colors.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        ArtistActionRow(
            onPlayAll = onPlayAll,
            onAddToPlaylist = onAddToPlaylist,
            onAddToQueue = onAddToQueue,
        )
    }
}

private fun albumStatsLine(songs: List<Song>): String =
    listOfNotNull(
        ReleaseDates.earliestFullDate(songs).let { releaseDate ->
            ReleaseDates.displayLabel(ReleaseDates.aggregateYear(songs, releaseDate), releaseDate)
                .takeIf { it.isNotBlank() }
        },
        "${songs.size} 首",
        totalDurationLabel(songs.sumOf { it.durationSec.coerceAtLeast(0) }),
    ).joinToString(" · ")

private fun totalDurationLabel(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun ArtistDetailPanel(
    artistName: String,
    songs: List<Song>,
    currentSongId: String?,
    isPlaying: Boolean,
    onQueueSongs: (List<Song>) -> Unit,
    onAppendSongsToQueue: (List<Song>) -> Unit,
    onAddSongsToPlaylist: (List<Song>) -> Unit,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    onAlbumClick: (AlbumBrowseKey) -> Unit,
    emptyMessage: String,
    listState: LazyListState,
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) {
        EmptyBrowseHint(emptyMessage, modifier)
        return
    }

    val albumSections = remember(songs) {
        timedBrowseDetail("artist detail", "artist=$artistName", songs.size) {
            LibraryBrowseDetails.artistAlbumSections(songs)
        }
    }
    val displayedSongs = remember(albumSections) { albumSections.flatMap { it.songs } }
    val header: @Composable () -> Unit = {
        ArtistDetailHeader(
            artistName = artistName,
            songs = songs,
            albumSections = albumSections,
            onPlayAll = {
                onQueueSongs(displayedSongs)
                displayedSongs.firstOrNull()?.let { onSongClick(it.id) }
            },
            onAddToPlaylist = { onAddSongsToPlaylist(displayedSongs) },
            onAddToQueue = { onAppendSongsToQueue(displayedSongs) },
        )
    }
    val discLabel: @Composable (Int) -> Unit = { discNumber ->
        Text(
            text = "DISC $discNumber",
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.padding(
                start = HifiSpacing.lg,
                top = HifiSpacing.md,
                end = HifiSpacing.lg,
                bottom = HifiSpacing.xs,
            ),
        )
    }
    val songRow: @Composable (Int, Song) -> Unit = { trackIndex, song ->
        val isCurrent = currentSongId == song.id
        SongRow(
            song = song,
            trackNumber = song.trackNumber.takeIf { it > 0 }?.toString()?.padStart(2, '0')
                ?: (trackIndex + 1).toString().padStart(2, '0'),
            trailingLabel = song.durationLabel,
            isCurrent = isCurrent,
            isPlaying = isCurrent && isPlaying,
            showCover = false,
            compact = true,
            onClick = {
                onQueueSongs(displayedSongs)
                onSongClick(song.id)
            },
            onLongClick = { onSongOpenMenu(song) },
        )
    }

    DetailZoomHost(
        page = LibraryZoomPage.ARTIST_DETAIL,
        listState = listState,
        listBottomPadding = listBottomPadding,
        modifier = modifier,
    ) { itemMorph ->
        item(key = "artistHeader", span = { GridItemSpan(maxLineSpan) }) {
            Box(modifier = itemMorph("artistHeader")) { header() }
        }
        albumSections.forEach { section ->
            item(
                key = "albumHeader:${section.key.storageKey}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Box(modifier = itemMorph("albumHeader:${section.key.storageKey}")) {
                    ArtistAlbumHeader(section = section, onAlbumClick = onAlbumClick)
                }
            }
            section.discSections.forEach { discSection ->
                discSection.discNumber?.let { discNumber ->
                    item(
                        key = "artistDisc:${section.key.storageKey}:$discNumber",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        Box(modifier = itemMorph("artistDisc:${section.key.storageKey}:$discNumber")) {
                            discLabel(discNumber)
                        }
                    }
                }
                gridItemsIndexed(
                    items = discSection.songs,
                    key = { _, song -> "artistSong:${song.id}" },
                ) { trackIndex, song ->
                    Box(modifier = itemMorph("artistSong:${song.id}")) {
                        songRow(trackIndex, song)
                    }
                }
            }
        }
    }

}

private data class DetailZoomPreset(
    val id: String,
    val columns: Int,
)

private val DetailZoomOrder = listOf(
    DetailZoomPreset(id = "dense_two_columns", columns = 2),
    DetailZoomPreset(id = "normal_one_column", columns = 1),
)

@Composable
private fun DetailZoomHost(
    page: LibraryZoomPage,
    listState: LazyListState,
    listBottomPadding: Dp,
    modifier: Modifier,
    content: LazyGridScope.((Any) -> Modifier) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val motionEnabled = rememberMicaMotionEnabled()
    val validIds = remember { DetailZoomOrder.mapTo(linkedSetOf()) { it.id } }
    val initialId = remember(context, page) {
        LibraryZoomPreferences.presetId(
            context = context,
            page = page,
            defaultId = if (landscape) DetailZoomOrder.first().id else DetailZoomOrder.last().id,
            validIds = validIds,
        )
    }
    val initialIndex = DetailZoomOrder.indexOfFirst { it.id == initialId }.coerceAtLeast(0)
    val states = List(DetailZoomOrder.size) { index ->
        rememberLazyGridState(
            initialFirstVisibleItemIndex = if (index == initialIndex) listState.firstVisibleItemIndex else 0,
            initialFirstVisibleItemScrollOffset = if (index == initialIndex) listState.firstVisibleItemScrollOffset else 0,
        )
    }
    val zoomState = rememberPinchZoomState(
        presetCount = DetailZoomOrder.size,
        initialIndex = initialIndex,
        externalIndex = initialIndex,
        motionEnabled = motionEnabled,
        stateKey = page,
        onSettledIndexChanged = { index ->
            LibraryZoomPreferences.setPresetId(
                context = context,
                page = page,
                presetId = DetailZoomOrder[index].id,
                validIds = validIds,
            )
        },
    )
    val segment = zoomState.segment
    val anchorCoordinator = rememberPinchZoomGridAnchorCoordinator(
        states = states,
        initialAlignedIndex = initialIndex,
        stateKey = page,
    )

    LaunchedEffect(segment.lowerIndex, segment.upperIndex, anchorCoordinator.anchor, zoomState.gestureActive) {
        anchorCoordinator.alignPresetPair(segment.lowerIndex, segment.upperIndex)
        zoomState.markGestureGeometryReady()
    }
    LaunchedEffect(zoomState.settledIndex, page) {
        val settled = zoomState.settledIndex
        anchorCoordinator.resetTo(settled)
        val state = states[settled]
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) -> listState.scrollToItem(index, offset) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pinchZoomGesture(
                state = zoomState,
                onGestureStart = { _ ->
                    val source = zoomState.settledIndex
                    anchorCoordinator.beginGesture(source)
                },
            ),
    ) {
        val progress = segment.progress
        val transitionActive = segment.lowerIndex != segment.upperIndex
        val dominant = zoomState.dominantIndex
        DetailZoomLayer(
            preset = DetailZoomOrder[segment.lowerIndex],
            state = states[segment.lowerIndex],
            counterpartState = states[segment.upperIndex],
            morphProgress = progress,
            morphFromLower = true,
            transitionActive = transitionActive,
            listBottomPadding = listBottomPadding,
            interactive = segment.lowerIndex == dominant,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (segment.lowerIndex == dominant) 1f else 0f),
            content = content,
        )
        if (transitionActive) {
            DetailZoomLayer(
                preset = DetailZoomOrder[segment.upperIndex],
                state = states[segment.upperIndex],
                counterpartState = states[segment.lowerIndex],
                morphProgress = progress,
                morphFromLower = false,
                transitionActive = true,
                listBottomPadding = listBottomPadding,
                interactive = segment.upperIndex == dominant,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (segment.upperIndex == dominant) 1f else 0f),
                content = content,
            )
        }
    }
}

@Composable
private fun DetailZoomLayer(
    preset: DetailZoomPreset,
    state: LazyGridState,
    counterpartState: LazyGridState,
    morphProgress: Float,
    morphFromLower: Boolean,
    transitionActive: Boolean,
    listBottomPadding: Dp,
    interactive: Boolean,
    modifier: Modifier,
    content: LazyGridScope.((Any) -> Modifier) -> Unit,
) {
    val currentRects = state.visiblePinchZoomItemRects()
    val counterpartRects = counterpartState.visiblePinchZoomItemRects()
    val itemMorph: (Any) -> Modifier = { key ->
        val morph = calculatePinchZoomItemMorph(
            current = currentRects[key],
            counterpart = counterpartRects[key],
            progress = morphProgress,
            fromLower = morphFromLower,
            transitionActive = transitionActive,
        )
        Modifier.pinchZoomItemBoundsMorph(morph)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(preset.columns),
        state = state,
        userScrollEnabled = interactive,
        contentPadding = PaddingValues(bottom = listBottomPadding),
        modifier = modifier,
    ) {
        content(itemMorph)
    }
}

@Composable
private fun ArtistDetailHeader(
    artistName: String,
    songs: List<Song>,
    albumSections: List<LibraryBrowseDetails.ArtistAlbumSection>,
    onPlayAll: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    val artworkSong = remember(songs) {
        songs.firstOrNull { !it.albumArtUri.isNullOrBlank() } ?: songs.first()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = HifiSpacing.lg,
                top = HifiSpacing.xl,
                end = HifiSpacing.lg,
                bottom = HifiSpacing.xl,
            ),
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
        ) {
            SongCover(
                albumArtUri = artworkSong.albumArtUri,
                fallbackColor = artworkSong.coverColor,
                contentDescription = artistName,
                modifier = Modifier.size(112.dp),
            )
            Text(
                text = artistName,
                style = MicaTheme.typography.titleMd.copy(fontSize = 20.sp, lineHeight = 26.sp),
                color = MicaTheme.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = artistStatsLine(songs, albumSections),
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ArtistActionRow(
            onPlayAll = onPlayAll,
            onAddToPlaylist = onAddToPlaylist,
            onAddToQueue = onAddToQueue,
        )
    }
}

@Composable
private fun ArtistActionRow(
    onPlayAll: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistActionText("播放全部", emphasized = true, onClick = onPlayAll, modifier = Modifier.weight(1f))
        ArtistActionDivider()
        ArtistActionText("加入歌单", onClick = onAddToPlaylist, modifier = Modifier.weight(1f))
        ArtistActionDivider()
        ArtistActionText("加入队列", onClick = onAddToQueue, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ArtistActionText(
    label: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier.height(44.dp)) {
        Text(
            text = label,
            style = MicaTheme.typography.bodyMd,
            color = if (emphasized) MicaTheme.colors.accent else MicaTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtistActionDivider() {
    Spacer(
        modifier = Modifier
            .width(1.dp)
            .height(24.dp)
            .background(MicaTheme.colors.divider),
    )
}

@Composable
private fun ArtistAlbumHeader(
    section: LibraryBrowseDetails.ArtistAlbumSection,
    onAlbumClick: (AlbumBrowseKey) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = HifiSpacing.lg,
                end = HifiSpacing.lg,
                top = HifiSpacing.md,
                bottom = HifiSpacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SongCover(
            albumArtUri = section.albumArtUri,
            fallbackColor = Color(section.coverColorArgb),
            contentDescription = section.title,
            decodeTarget = CoverDecodeTarget.forCompactCover(),
            modifier = Modifier
                .width(68.dp)
                .aspectRatio(1f),
        )
        Spacer(Modifier.width(HifiSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.title,
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onAlbumClick(section.key) },
            )
            Text(
                text = buildList {
                    ReleaseDates.displayLabel(section.year, section.releaseDate)
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                    add("${section.songs.size} 首")
                }.joinToString(" · "),
                style = MicaTheme.typography.bodySm,
                color = MicaTheme.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun artistStatsLine(
    songs: List<Song>,
    albumSections: List<LibraryBrowseDetails.ArtistAlbumSection>,
): String {
    val totalSize = SongDetails.formatFileSize(songs.sumOf { it.sizeBytes.coerceAtLeast(0L) })
    val totalDuration = totalDurationLabel(songs.sumOf { it.durationSec.coerceAtLeast(0) })
    return "${songs.size} 首歌曲 · ${albumSections.size} 张专辑 · $totalSize · $totalDuration"
}

@Composable
private fun FolderDepthPage(
    depth: Int,
    scopePathSegments: List<String>,
    library: MusicLibrary,
    currentSongId: String?,
    isPlaying: Boolean,
    onQueueSongs: (List<Song>) -> Unit,
    rootListState: LazyListState,
    onFolderSelect: (FolderBrowseGroup) -> Unit,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val groups = library.folderGroupsAtDepth(depth, scopePathSegments)
    val songsInScope = if (scopePathSegments.isNotEmpty() && scopePathSegments.size == depth) {
        library.songsInFolder(scopePathSegments)
    } else {
        emptyList()
    }
    when {
        groups.isNotEmpty() || songsInScope.isNotEmpty() -> {
            val listState = if (depth == 0 && scopePathSegments.isEmpty()) {
                rootListState
            } else {
                rememberBrowseDetailSongListState(
                    "folderPage:$depth:${scopePathSegments.joinToString("/")}",
                )
            }
            FolderContentList(
                groups = groups,
                songs = songsInScope,
                currentSongId = currentSongId,
                isPlaying = isPlaying,
                listState = listState,
                onSelect = onFolderSelect,
                onSongClick = { songId ->
                    val queue = if (scopePathSegments.isNotEmpty()) {
                        library.songsForFolder(scopePathSegments)
                    } else {
                        songsInScope
                    }
                    onQueueSongs(queue)
                    onSongClick(songId)
                },
                onSongOpenMenu = onSongOpenMenu,
                listBottomPadding = listBottomPadding,
                forceListLayout = depth == 0 && scopePathSegments.isEmpty(),
                modifier = modifier,
            )
        }
        scopePathSegments.isNotEmpty() -> {
            val songs = library.songsForFolder(scopePathSegments)
            SongListPanel(
                songs = songs,
                library = library,
                currentSongId = currentSongId,
                isPlaying = isPlaying,
                onSongClick = { songId ->
                    onQueueSongs(songs)
                    onSongClick(songId)
                },
                onSongOpenMenu = onSongOpenMenu,
                emptyMessage = "该文件夹下暂无歌曲",
                infoVisibility = FolderSongInfoVisibility,
                listBottomPadding = listBottomPadding,
                zoomPage = LibraryZoomPage.FOLDERS,
                modifier = modifier,
            )
        }
        else -> EmptyBrowseHint("暂无文件夹", modifier)
    }
}

@Composable
private fun ArtistGroupList(
    library: MusicLibrary,
    listState: LazyListState,
    gridState: LazyGridState,
    onSelect: (String) -> Unit,
    sortField: ArtistBrowseSortField,
    sortDirection: SortDirection,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    motionEnabled: Boolean,
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val presentation = remember(library.songs, library.artistSplitRevision, sortField, sortDirection) {
        timedBrowseDetail("artist groups", "sort=$sortField/$sortDirection", library.songs.size) {
            library.artistGroupPresentation(sortField, sortDirection)
        }
    }
    LaunchedEffect(presentation) { library.prewarmBrowseGroupCache() }
    val groups = presentation.groups
    if (groups.isEmpty()) {
        EmptyBrowseHint("暂无艺术家", modifier)
        return
    }
    val fastScrollLabels = presentation.fastScrollIndex?.labels
    val fastScrollSectionTargets = presentation.fastScrollIndex?.sectionTargets
    logBrowseGroupList(
        stage = "artist group list",
        groups = groups,
        gridColumns = gridColumns,
        fastScrollLabels = fastScrollLabels,
    )
    BrowseGroupList(
        groups = groups,
        listState = listState,
        gridState = gridState,
        gridColumns = gridColumns,
        onGridColumnsChange = onGridColumnsChange,
        motionEnabled = motionEnabled,
        onSelect = { group -> onSelect(group.title) },
        gridTitleMaxLines = 1,
        fastScrollLabels = fastScrollLabels,
        fastScrollSectionTargets = fastScrollSectionTargets,
        fastScrollDescending = sortDirection == SortDirection.DESC,
        listBottomPadding = listBottomPadding,
        modifier = modifier,
    )
}

@Composable
private fun AlbumGroupList(
    library: MusicLibrary,
    listState: LazyListState,
    gridState: LazyGridState,
    onSelect: (AlbumBrowseKey) -> Unit,
    sortField: AlbumBrowseSortField,
    sortDirection: SortDirection,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    infoVisibility: BrowseListInfoVisibility,
    motionEnabled: Boolean,
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val presentation = remember(library.songs, library.artistSplitRevision, sortField, sortDirection) {
        timedBrowseDetail("album groups", "sort=$sortField/$sortDirection", library.songs.size) {
            library.albumGroupPresentation(sortField, sortDirection)
        }
    }
    LaunchedEffect(presentation) { library.prewarmBrowseGroupCache() }
    val groups = presentation.groups
    if (groups.isEmpty()) {
        EmptyBrowseHint("暂无专辑", modifier)
        return
    }
    val fastScrollLabels = presentation.fastScrollIndex?.labels
    val fastScrollSectionTargets = presentation.fastScrollIndex?.sectionTargets
    logBrowseGroupList(
        stage = "album group list",
        groups = groups,
        gridColumns = gridColumns,
        fastScrollLabels = fastScrollLabels,
    )
    BrowseGroupList(
        groups = groups,
        listState = listState,
        gridState = gridState,
        gridColumns = gridColumns,
        onGridColumnsChange = onGridColumnsChange,
        motionEnabled = motionEnabled,
        onSelect = { group ->
            onSelect(
                AlbumBrowseKey.fromStorageKey(group.key)
                    ?: AlbumBrowseKey(group.title, group.artist),
            )
        },
        rowSubtitle = { group -> albumRowSubtitle(group, infoVisibility) },
        gridTitleMaxLines = 1,
        fastScrollLabels = fastScrollLabels,
        fastScrollSectionTargets = fastScrollSectionTargets,
        fastScrollDescending = sortDirection == SortDirection.DESC,
        listBottomPadding = listBottomPadding,
        modifier = modifier,
    )
}

@Composable
private fun BrowseGroupList(
    groups: List<BrowseGroup>,
    listState: LazyListState,
    gridState: LazyGridState,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    motionEnabled: Boolean,
    onSelect: (BrowseGroup) -> Unit,
    rowSubtitle: (BrowseGroup) -> String = { it.subtitle },
    fastScrollLabels: List<String>? = groups.map { it.title },
    fastScrollSectionTargets: Map<String, Int>? = null,
    fastScrollDescending: Boolean = false,
    gridTitleMaxLines: Int = 2,
    listBottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    // Stable layout identity (column count) is intentionally separate from gesture order.
    // Reduced Poweramp-like order: normal list -> extra-small grid -> small grid -> large grid.
    val orderedColumns = listOf(1, 4, 3, 2)
    val presetCount = orderedColumns.size
    val externalIndex = orderedColumns.indexOf(gridColumns.coerceIn(1, 4)).coerceAtLeast(0)
    val initialFirstVisibleIndex = if (externalIndex == 0) {
        listState.firstVisibleItemIndex
    } else {
        gridState.firstVisibleItemIndex
    }
    val initialFirstVisibleOffset = if (externalIndex == 0) {
        listState.firstVisibleItemScrollOffset
    } else {
        gridState.firstVisibleItemScrollOffset
    }
    val states = List(presetCount) { index ->
        rememberLazyGridState(
            initialFirstVisibleItemIndex = if (index == externalIndex) initialFirstVisibleIndex else 0,
            initialFirstVisibleItemScrollOffset = if (index == externalIndex) initialFirstVisibleOffset else 0,
        )
    }
    val zoomState = rememberPinchZoomState(
        presetCount = presetCount,
        initialIndex = externalIndex,
        externalIndex = externalIndex,
        motionEnabled = motionEnabled,
        onSettledIndexChanged = { onGridColumnsChange(orderedColumns[it]) },
    )
    val anchorCoordinator = rememberPinchZoomGridAnchorCoordinator(
        states = states,
        initialAlignedIndex = externalIndex,
    )
    var previousExternalIndex by remember { mutableStateOf(externalIndex) }
    val segment = zoomState.segment

    LaunchedEffect(segment.lowerIndex, segment.upperIndex, anchorCoordinator.anchor, zoomState.gestureActive) {
        anchorCoordinator.alignPresetPair(segment.lowerIndex, segment.upperIndex)
        zoomState.markGestureGeometryReady()
    }

    LaunchedEffect(externalIndex) {
        if (externalIndex != previousExternalIndex) {
            val sourceIndex = previousExternalIndex
            anchorCoordinator.alignExternalPreset(sourceIndex, externalIndex)
            previousExternalIndex = externalIndex
        }
    }

    LaunchedEffect(zoomState.settledIndex) {
        val index = zoomState.settledIndex
        anchorCoordinator.resetTo(index)
        val state = states[index]
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collectLatest { (itemIndex, offset) ->
                if (index == 0) {
                    listState.scrollToItem(itemIndex, offset)
                } else {
                    gridState.scrollToItem(itemIndex, offset)
                }
            }
    }

    val configuration = LocalConfiguration.current
    val landscapeWindow =
        songListColumnsFor(configuration.screenWidthDp, configuration.screenHeightDp) > 1

    val zoomContent: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pinchZoomGesture(
                    state = zoomState,
                    onGestureStart = { _ ->
                        val source = zoomState.settledIndex
                        anchorCoordinator.beginGesture(source)
                    },
                ),
        ) {
            val progress = segment.progress
            val transitionActive = segment.lowerIndex != segment.upperIndex
            val dominant = zoomState.dominantIndex

            // Mirror SongListPanel: every preset world stays measured from the first frame. These
            // layers are invisible geometry/interaction oracles; the single overlay below owns all
            // visible covers/text. Keeping all four worlds warm prevents a first-use empty frame.
            orderedColumns.forEachIndexed { index, columns ->
                BrowseGroupGeometryLayer(
                    groups = groups,
                    state = states[index],
                    columns = columns,
                    onSelect = onSelect,
                    gridTitleMaxLines = gridTitleMaxLines,
                    listBottomPadding = listBottomPadding,
                    interactive = index == dominant,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (index == dominant) 1f else 0f)
                        .graphicsLayer { alpha = 0f },
                )
            }
            BrowseGroupMorphOverlay(
                groups = groups,
                lowerState = states[segment.lowerIndex],
                upperState = states[segment.upperIndex],
                lowerColumns = orderedColumns[segment.lowerIndex],
                upperColumns = orderedColumns[segment.upperIndex],
                progress = progress,
                transitionActive = transitionActive,
                rowSubtitle = rowSubtitle,
                gridTitleMaxLines = gridTitleMaxLines,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .zIndex(2f),
            )
        }
    }

    if (fastScrollLabels == null) {
        Box(modifier = modifier.fillMaxSize()) { zoomContent() }
    } else {
        AlphabetFastScroller(
            labels = fastScrollLabels,
            sectionTargetsOverride = fastScrollSectionTargets,
            scrollToIndex = { states[zoomState.dominantIndex].scrollToItem(it) },
            descending = fastScrollDescending,
            fullHeightOverlay = landscapeWindow,
            modifier = modifier.fillMaxSize(),
        ) {
            zoomContent()
        }
    }
}

@Composable
private fun BrowseGroupGeometryLayer(
    groups: List<BrowseGroup>,
    state: LazyGridState,
    columns: Int,
    onSelect: (BrowseGroup) -> Unit,
    gridTitleMaxLines: Int,
    listBottomPadding: Dp,
    interactive: Boolean,
    modifier: Modifier,
) {
    val normalizedColumns = columns.coerceIn(1, 4)
    LazyVerticalGrid(
        columns = GridCells.Fixed(normalizedColumns),
        state = state,
        modifier = modifier,
        userScrollEnabled = interactive,
        contentPadding = if (normalizedColumns > 1) {
            PaddingValues(
                start = HifiSpacing.lg,
                end = HifiSpacing.lg,
                bottom = listBottomPadding,
            )
        } else {
            PaddingValues(bottom = listBottomPadding)
        },
        horizontalArrangement = if (normalizedColumns > 1) {
            Arrangement.spacedBy(HifiSpacing.md)
        } else {
            Arrangement.Start
        },
        verticalArrangement = if (normalizedColumns > 1) {
            Arrangement.spacedBy(HifiSpacing.lg)
        } else {
            Arrangement.Top
        },
    ) {
        gridItemsIndexed(groups, key = { _, group -> group.key }) { _, group ->
            if (normalizedColumns == 1) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(HifiSize.listRowHeight)
                        .clickable(enabled = interactive) { onSelect(group) },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = interactive) { onSelect(group) },
                    verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                ) {
                    Spacer(Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(
                        text = group.title,
                        style = MicaTheme.typography.bodyMd,
                        maxLines = gridTitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${group.songCount} 首",
                        style = MicaTheme.typography.bodySm,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private data class BrowseGroupMorphRecord(
    val group: BrowseGroup,
    val subtitle: String,
    val lowerRect: PinchZoomItemRect,
    val upperRect: PinchZoomItemRect,
    val displayRect: PinchZoomItemRect,
)

@Composable
private fun BrowseGroupMorphOverlay(
    groups: List<BrowseGroup>,
    lowerState: LazyGridState,
    upperState: LazyGridState,
    lowerColumns: Int,
    upperColumns: Int,
    progress: Float,
    transitionActive: Boolean,
    rowSubtitle: (BrowseGroup) -> String,
    gridTitleMaxLines: Int,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val p = if (transitionActive) progress.coerceIn(0f, 1f) else 0f
    val safeLowerColumns = lowerColumns.coerceIn(1, 4)
    val safeUpperColumns = upperColumns.coerceIn(1, 4)
    val lowerInfos = lowerState.layoutInfo.visibleItemsInfo
    val upperInfos = upperState.layoutInfo.visibleItemsInfo
    val candidateIndices = buildSet {
        lowerInfos.forEach { add(it.index) }
        upperInfos.forEach { add(it.index) }
    }.sorted()

    val lowerHSpacing = with(density) { (if (safeLowerColumns > 1) HifiSpacing.md else 0.dp).toPx() }
    val lowerVSpacing = with(density) { (if (safeLowerColumns > 1) HifiSpacing.lg else 0.dp).toPx() }
    val upperHSpacing = with(density) { (if (safeUpperColumns > 1) HifiSpacing.md else 0.dp).toPx() }
    val upperVSpacing = with(density) { (if (safeUpperColumns > 1) HifiSpacing.lg else 0.dp).toPx() }
    // LazyGridItemInfo offsets are relative to the padded content area. The visible overlay is in
    // viewport coordinates, so restore the grid's start inset just like SongListPanel does.
    val lowerCrossInset = with(density) { (if (safeLowerColumns > 1) HifiSpacing.lg else 0.dp).toPx() }
    val upperCrossInset = with(density) { (if (safeUpperColumns > 1) HifiSpacing.lg else 0.dp).toPx() }

    val records = candidateIndices.mapNotNull { index ->
        val group = groups.getOrNull(index) ?: return@mapNotNull null
        val lower = lowerInfos.firstOrNull { it.index == index }?.toBrowsePinchZoomRect(lowerCrossInset)
            ?: extrapolateBrowseRect(
                visible = lowerInfos,
                index = index,
                columns = safeLowerColumns,
                horizontalSpacingPx = lowerHSpacing,
                verticalSpacingPx = lowerVSpacing,
                crossAxisInsetPx = lowerCrossInset,
            )
        val upper = upperInfos.firstOrNull { it.index == index }?.toBrowsePinchZoomRect(upperCrossInset)
            ?: extrapolateBrowseRect(
                visible = upperInfos,
                index = index,
                columns = safeUpperColumns,
                horizontalSpacingPx = upperHSpacing,
                verticalSpacingPx = upperVSpacing,
                crossAxisInsetPx = upperCrossInset,
            )
        if (lower == null || upper == null) null else BrowseGroupMorphRecord(
            group = group,
            subtitle = rowSubtitle(group),
            lowerRect = lower,
            upperRect = upper,
            displayRect = interpolateBrowseRect(lower, upper, p),
        )
    }

    Layout(
        modifier = modifier,
        content = {
            records.forEach { record ->
                androidx.compose.runtime.key(record.group.key) {
                    BrowseGroupSceneItem(
                        group = record.group,
                        subtitle = record.subtitle,
                        lowerColumns = lowerColumns,
                        upperColumns = upperColumns,
                        lowerWidthPx = record.lowerRect.widthPx,
                        upperWidthPx = record.upperRect.widthPx,
                        progress = p,
                        transitionActive = transitionActive,
                        gridTitleMaxLines = gridTitleMaxLines,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth.coerceAtLeast(constraints.minWidth)
        val height = constraints.maxHeight.coerceAtLeast(constraints.minHeight)
        val placeables = measurables.mapIndexed { index, measurable ->
            val rect = records[index].displayRect
            measurable.measure(
                androidx.compose.ui.unit.Constraints.fixed(
                    rect.widthPx.toInt().coerceAtLeast(1),
                    rect.heightPx.toInt().coerceAtLeast(1),
                ),
            )
        }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val rect = records[index].displayRect
                placeable.placeRelative(rect.leftPx.toInt(), rect.topPx.toInt())
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridItemInfo.toBrowsePinchZoomRect(
    crossAxisInsetPx: Float = 0f,
): PinchZoomItemRect = PinchZoomItemRect(
    leftPx = offset.x.toFloat() + crossAxisInsetPx,
    topPx = offset.y.toFloat(),
    widthPx = size.width.toFloat(),
    heightPx = size.height.toFloat(),
)

private fun extrapolateBrowseRect(
    visible: List<androidx.compose.foundation.lazy.grid.LazyGridItemInfo>,
    index: Int,
    columns: Int,
    horizontalSpacingPx: Float,
    verticalSpacingPx: Float,
    crossAxisInsetPx: Float = 0f,
): PinchZoomItemRect? {
    val safeColumns = columns.coerceAtLeast(1)
    val anchor = visible.minByOrNull { kotlin.math.abs(it.index - index) } ?: return null
    val anchorRow = anchor.index / safeColumns
    val anchorColumn = anchor.index % safeColumns
    val targetRow = index / safeColumns
    val targetColumn = index % safeColumns
    val xStep = anchor.size.width.toFloat() + horizontalSpacingPx
    val yStep = anchor.size.height.toFloat() + verticalSpacingPx
    return PinchZoomItemRect(
        leftPx = anchor.offset.x + crossAxisInsetPx + (targetColumn - anchorColumn) * xStep,
        topPx = anchor.offset.y + (targetRow - anchorRow) * yStep,
        widthPx = anchor.size.width.toFloat(),
        heightPx = anchor.size.height.toFloat(),
    )
}

private data class BrowseChildScene(
    val coverX: Float,
    val coverY: Float,
    val coverSize: Float,
    val titleX: Float,
    val titleY: Float,
    val titleScale: Float,
    val titleVisibleWidth: Float,
    val subtitleX: Float,
    val subtitleY: Float,
    val subtitleScale: Float,
    val subtitleVisibleWidth: Float,
    val arrowX: Float,
    val arrowY: Float,
    val arrowAlpha: Float,
)

@Composable
private fun BrowseGroupSceneItem(
    group: BrowseGroup,
    subtitle: String,
    lowerColumns: Int,
    upperColumns: Int,
    lowerWidthPx: Float,
    upperWidthPx: Float,
    progress: Float,
    transitionActive: Boolean,
    gridTitleMaxLines: Int,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val p = if (transitionActive) progress.coerceIn(0f, 1f) else 0f
    val lowerScene = browseChildScene(
        columns = lowerColumns,
        itemWidthPx = lowerWidthPx,
        screenWidthDp = configuration.screenWidthDp,
        density = density,
    )
    val upperScene = browseChildScene(
        columns = upperColumns,
        itemWidthPx = upperWidthPx,
        screenWidthDp = configuration.screenWidthDp,
        density = density,
    )
    val coverSize = browseLerp(lowerScene.coverSize, upperScene.coverSize, p).coerceAtLeast(1f)
    val coverX = browseLerp(lowerScene.coverX, upperScene.coverX, p)
    val coverY = browseLerp(lowerScene.coverY, upperScene.coverY, p)
    val titleX = browseLerp(lowerScene.titleX, upperScene.titleX, p)
    val titleY = browseLerp(lowerScene.titleY, upperScene.titleY, p)
    val titleScale = browseLerp(lowerScene.titleScale, upperScene.titleScale, p).coerceAtLeast(0.1f)
    val titleVisibleWidth = browseLerp(lowerScene.titleVisibleWidth, upperScene.titleVisibleWidth, p).coerceAtLeast(1f)
    val subtitleX = browseLerp(lowerScene.subtitleX, upperScene.subtitleX, p)
    val subtitleY = browseLerp(lowerScene.subtitleY, upperScene.subtitleY, p)
    val subtitleScale = browseLerp(lowerScene.subtitleScale, upperScene.subtitleScale, p).coerceAtLeast(0.1f)
    val subtitleVisibleWidth = browseLerp(lowerScene.subtitleVisibleWidth, upperScene.subtitleVisibleWidth, p).coerceAtLeast(1f)
    val arrowX = browseLerp(lowerScene.arrowX, upperScene.arrowX, p)
    val arrowY = browseLerp(lowerScene.arrowY, upperScene.arrowY, p)
    val arrowAlpha = browseLerp(lowerScene.arrowAlpha, upperScene.arrowAlpha, p).coerceIn(0f, 1f)

    Layout(
        content = {
            // One SongCover node survives list <-> grid and adjacent grid transitions.
            SongCover(
                albumArtUri = group.albumArtUri,
                fallbackColor = Color(group.coverColorArgb),
                contentDescription = group.title,
                decodeTarget = CoverDecodeTarget.forCompactCover(),
                stableMemoryCacheKey = group.albumArtUri,
                crossfadeMillis = 0,
                allowPreviousImageUnderlay = false,
                publishHoldoverOnSuccess = false,
            )
            Text(
                text = group.title,
                style = MicaTheme.typography.bodyLg,
                color = MicaTheme.colors.textPrimary,
                maxLines = gridTitleMaxLines.coerceAtLeast(1),
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MicaTheme.typography.bodySm,
                color = MicaTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MicaTheme.colors.textTertiary,
                modifier = Modifier.size(HifiSize.iconMd),
            )
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth.coerceAtLeast(constraints.minWidth)
        val height = constraints.maxHeight.coerceAtLeast(constraints.minHeight)

        // Measure media/text once against a segment-stable base, then use uniform child scale.
        // This follows Poweramp's scene `scale + layout_compensateScale` model and never
        // non-uniformly stretches a cover or glyph raster.
        val coverBase = maxOf(lowerScene.coverSize, upperScene.coverSize).toInt().coerceAtLeast(1)
        // Match SongListPanel: the available text width is a scene property and therefore changes
        // continuously during the pinch. Convert visual width back to unscaled measure width so
        // ellipsis/reflow follows the fingers instead of snapping only after settle.
        val titleBaseWidth = compensatedTextMeasureWidth(titleVisibleWidth, titleScale)
        val subtitleBaseWidth = compensatedTextMeasureWidth(subtitleVisibleWidth, subtitleScale)

        val cover = measurables[0].measure(androidx.compose.ui.unit.Constraints.fixed(coverBase, coverBase))
        val title = measurables[1].measure(
            androidx.compose.ui.unit.Constraints(
                minWidth = 0,
                maxWidth = titleBaseWidth,
                minHeight = 0,
                maxHeight = androidx.compose.ui.unit.Constraints.Infinity,
            ),
        )
        val line2 = measurables[2].measure(
            androidx.compose.ui.unit.Constraints(
                minWidth = 0,
                maxWidth = subtitleBaseWidth,
                minHeight = 0,
                maxHeight = androidx.compose.ui.unit.Constraints.Infinity,
            ),
        )
        val arrowSize = with(density) { HifiSize.iconMd.roundToPx() }
        val arrow = measurables[3].measure(androidx.compose.ui.unit.Constraints.fixed(arrowSize, arrowSize))

        layout(width, height) {
            cover.placeWithLayer(coverX.toInt(), coverY.toInt()) {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                val scale = coverSize / coverBase.toFloat()
                scaleX = scale
                scaleY = scale
            }
            title.placeWithLayer(titleX.toInt(), titleY.toInt()) {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                scaleX = titleScale
                scaleY = titleScale
            }
            line2.placeWithLayer(subtitleX.toInt(), subtitleY.toInt()) {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                scaleX = subtitleScale
                scaleY = subtitleScale
            }
            arrow.placeWithLayer(arrowX.toInt(), arrowY.toInt()) {
                alpha = arrowAlpha
            }
        }
    }
}

private fun browseChildScene(
    columns: Int,
    itemWidthPx: Float,
    screenWidthDp: Int,
    density: androidx.compose.ui.unit.Density,
): BrowseChildScene {
    val normalizedColumns = columns.coerceIn(1, 4)
    fun dp(value: Dp): Float = with(density) { value.toPx() }
    val itemWidth = itemWidthPx.coerceAtLeast(1f)
    val listRowHeight = dp(HifiSize.listRowHeight)
    val listCover = dp(HifiSize.coverSm)
    val listCoverX = dp(HifiSpacing.lg + HifiSize.accentBarWidth + HifiSpacing.md)
    val listCoverY = ((listRowHeight - listCover) / 2f).coerceAtLeast(0f)
    val listTextX = listCoverX + listCover + dp(HifiSpacing.md)
    val listTitleY = dp(11.dp)
    val listRightReserve = dp(HifiSpacing.lg + HifiSize.iconMd)
    val listTextWidth = (itemWidth - listTextX - listRightReserve).coerceAtLeast(1f)
    val arrowSize = dp(HifiSize.iconMd)
    val arrowX = (itemWidth - dp(HifiSpacing.lg) - arrowSize).coerceAtLeast(0f)
    val arrowY = ((listRowHeight - arrowSize) / 2f).coerceAtLeast(0f)

    if (normalizedColumns == 1) {
        return BrowseChildScene(
            coverX = listCoverX,
            coverY = listCoverY,
            coverSize = listCover,
            titleX = listTextX,
            titleY = listTitleY,
            titleScale = 1f,
            titleVisibleWidth = listTextWidth,
            subtitleX = listTextX,
            subtitleY = listTitleY + dp(24.dp),
            subtitleScale = 1f,
            subtitleVisibleWidth = listTextWidth,
            arrowX = arrowX,
            arrowY = arrowY,
            arrowAlpha = 1f,
        )
    }

    // Poweramp's public scene resources use ~0.675/0.75 for compact-grid title,
    // ~0.625/0.70 for line2, and ~0.9 for the largest grid. Keep Mica's own
    // endpoints but reproduce that independent scene scaling model.
    val largeGrid = normalizedColumns == 2
    val compactTitleScale = if (screenWidthDp >= 360) 0.75f else 0.675f
    val compactLine2Scale = if (screenWidthDp >= 360) 0.70f else 0.625f
    val titleScale = if (largeGrid) 0.90f else compactTitleScale
    val line2Scale = if (largeGrid) 0.86f else compactLine2Scale
    val textInset = 0f
    val textWidth = (itemWidth - textInset * 2f).coerceAtLeast(1f)
    val gridGap = dp(HifiSpacing.sm)
    val titleY = itemWidth + gridGap
    val visibleTitleLineHeight = dp(24.dp) * titleScale

    return BrowseChildScene(
        coverX = 0f,
        coverY = 0f,
        coverSize = itemWidth,
        titleX = textInset,
        titleY = titleY,
        titleScale = titleScale,
        titleVisibleWidth = textWidth,
        subtitleX = textInset,
        subtitleY = titleY + visibleTitleLineHeight + dp(HifiSpacing.xs),
        subtitleScale = line2Scale,
        subtitleVisibleWidth = textWidth,
        arrowX = arrowX,
        arrowY = arrowY,
        arrowAlpha = 0f,
    )
}

private fun interpolateBrowseRect(
    lower: PinchZoomItemRect,
    upper: PinchZoomItemRect,
    progress: Float,
): PinchZoomItemRect = PinchZoomItemRect(
    leftPx = browseLerp(lower.leftPx, upper.leftPx, progress),
    topPx = browseLerp(lower.topPx, upper.topPx, progress),
    widthPx = browseLerp(lower.widthPx, upper.widthPx, progress).coerceAtLeast(1f),
    heightPx = browseLerp(lower.heightPx, upper.heightPx, progress).coerceAtLeast(1f),
)

private fun browseLerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction.coerceIn(0f, 1f)

internal fun albumRowSubtitle(group: BrowseGroup, visibility: BrowseListInfoVisibility): String =
    listOfNotNull(
        group.artist.takeIf { visibility.showAlbumSubtitleArtist && it.isNotBlank() },
        ReleaseDates.displayLabel(group.year, group.releaseDate)
            .takeIf { visibility.showAlbumSubtitleReleaseDate && it.isNotBlank() },
        "${group.songCount} 首".takeIf { visibility.showAlbumSubtitleSongCount },
    ).joinToString(" · ")

@Composable
private fun FolderContentList(
    groups: List<FolderBrowseGroup>,
    songs: List<Song>,
    currentSongId: String?,
    isPlaying: Boolean,
    listState: LazyListState,
    onSelect: (FolderBrowseGroup) -> Unit,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    listBottomPadding: Dp = 0.dp,
    fastScrollLabels: List<String>? = null,
    forceListLayout: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val columns = songListColumnsFor(configuration.screenWidthDp, configuration.screenHeightDp)
    val landscapeWindow = columns > 1
    val gridState = rememberLazyGridState()
    val groupRow: @Composable (FolderBrowseGroup) -> Unit = { group ->
        BrowseGroupRow(
            title = group.title,
            subtitle = group.subtitle,
            onClick = { onSelect(group) },
        )
    }
    val songRow: @Composable (Song) -> Unit = { song ->
        val isCurrent = currentSongId == song.id
        SongRow(
            song = song,
            isCurrent = isCurrent,
            isPlaying = isCurrent && isPlaying,
            onClick = { onSongClick(song.id) },
            onLongClick = { onSongOpenMenu(song) },
            infoVisibility = FolderSongInfoVisibility,
        )
    }

    if (columns > 1 && !forceListLayout) {
        if (fastScrollLabels == null) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = listBottomPadding),
            ) {
                gridItems(
                    items = groups,
                    key = { it.pathSegments.joinToString("/") },
                    span = { GridItemSpan(maxLineSpan) },
                ) { group ->
                    groupRow(group)
                }
                gridItems(songs, key = { "song:${it.id}" }) { song ->
                    songRow(song)
                }
            }
        } else {
            AlphabetFastScroller(
                labels = fastScrollLabels,
                scrollToIndex = { gridState.scrollToItem(it) },
                fullHeightOverlay = landscapeWindow,
                modifier = modifier.fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = listBottomPadding),
                ) {
                    gridItems(
                        items = groups,
                        key = { it.pathSegments.joinToString("/") },
                        span = { GridItemSpan(maxLineSpan) },
                    ) { group ->
                        groupRow(group)
                    }
                    gridItems(songs, key = { "song:${it.id}" }) { song ->
                        songRow(song)
                    }
                }
            }
        }
    } else if (fastScrollLabels == null) {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            items(groups, key = { it.pathSegments.joinToString("/") }) { group ->
                groupRow(group)
            }
            items(songs, key = { "song:${it.id}" }) { song ->
                songRow(song)
            }
        }
    } else {
        AlphabetFastScroller(
            labels = fastScrollLabels,
            scrollToIndex = { listState.scrollToItem(it) },
            fullHeightOverlay = landscapeWindow,
            modifier = modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = listBottomPadding),
            ) {
                items(groups, key = { it.pathSegments.joinToString("/") }) { group ->
                    groupRow(group)
                }
                items(songs, key = { "song:${it.id}" }) { song ->
                    songRow(song)
                }
            }
        }
    }
}

@Composable
private fun EmptyBrowseHint(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MicaTheme.typography.bodyMd,
            color = MicaTheme.colors.textTertiary,
        )
    }
}
