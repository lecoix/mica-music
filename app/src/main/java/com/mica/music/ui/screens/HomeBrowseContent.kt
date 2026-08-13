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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import com.mica.music.ui.components.AlphabetFastScroller
import com.mica.music.ui.components.EmptyStatePresets
import com.mica.music.ui.components.BrowseGroupRow
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.components.SongListPanel
import com.mica.music.ui.components.SongRow
import com.mica.music.ui.components.songListColumnsFor
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.coverColor
import com.mica.music.ui.screens.home.BrowseDestination
import com.mica.music.ui.screens.home.HomeSection
import com.mica.music.ui.screens.home.browseDestinationDepth
import com.mica.music.util.DiagnosticLog

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
    artistSortField: ArtistBrowseSortField = ArtistBrowseSortField.TITLE,
    artistSortDirection: SortDirection = SortDirection.ASC,
    artistGridColumns: Int = 1,
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
    val configuration = LocalConfiguration.current
    val columns = songListColumnsFor(configuration.screenWidthDp, configuration.screenHeightDp)
    val gridState = rememberLazyGridState()
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

    if (columns > 1) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            item(key = "albumHeader", span = { GridItemSpan(maxLineSpan) }) { header() }
            detail.discSections.forEach { section ->
                section.discNumber?.let { discNumber ->
                    item(
                        key = "albumDisc:$discNumber",
                        span = { GridItemSpan(maxLineSpan) },
                    ) { discLabel(discNumber) }
                }
                gridItemsIndexed(
                    items = section.songs,
                    key = { _, song -> "albumSong:${song.id}" },
                ) { trackIndex, song ->
                    songRow(trackIndex, song)
                }
            }
            detail.copyright?.let { label ->
                item(key = "albumCopyright", span = { GridItemSpan(maxLineSpan) }) {
                    copyright(label)
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            item("albumHeader") { header() }
            detail.discSections.forEach { section ->
                section.discNumber?.let { discNumber ->
                    item("albumDisc:$discNumber") { discLabel(discNumber) }
                }
                itemsIndexed(
                    items = section.songs,
                    key = { _, song -> "albumSong:${song.id}" },
                ) { trackIndex, song ->
                    songRow(trackIndex, song)
                }
            }
            detail.copyright?.let { label ->
                item("albumCopyright") { copyright(label) }
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
    val configuration = LocalConfiguration.current
    val columns = songListColumnsFor(configuration.screenWidthDp, configuration.screenHeightDp)
    val gridState = rememberLazyGridState()
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

    if (columns > 1) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            item(key = "artistHeader", span = { GridItemSpan(maxLineSpan) }) { header() }
            albumSections.forEach { section ->
                item(
                    key = "albumHeader:${section.key.storageKey}",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    ArtistAlbumHeader(section = section, onAlbumClick = onAlbumClick)
                }
                section.discSections.forEach { discSection ->
                    discSection.discNumber?.let { discNumber ->
                        item(
                    key = "artistDisc:${section.key.storageKey}:$discNumber",
                            span = { GridItemSpan(maxLineSpan) },
                        ) { discLabel(discNumber) }
                    }
                    gridItemsIndexed(
                        items = discSection.songs,
                        key = { _, song -> "artistSong:${song.id}" },
                    ) { trackIndex, song ->
                        songRow(trackIndex, song)
                    }
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            item("artistHeader") { header() }
            albumSections.forEach { section ->
                item("albumHeader:${section.key.storageKey}") {
                    ArtistAlbumHeader(section = section, onAlbumClick = onAlbumClick)
                }
                section.discSections.forEach { discSection ->
                    discSection.discNumber?.let { discNumber ->
                        item("artistDisc:${section.key.storageKey}:$discNumber") {
                            discLabel(discNumber)
                        }
                    }
                    itemsIndexed(
                        items = discSection.songs,
                        key = { _, song -> "artistSong:${song.id}" },
                    ) { trackIndex, song ->
                        songRow(trackIndex, song)
                    }
                }
            }
        }
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
        onSelect = { group ->
            onSelect(
                AlbumBrowseKey.fromStorageKey(group.key)
                    ?: AlbumBrowseKey(group.title, group.artist),
            )
        },
        rowSubtitle = ::albumRowSubtitle,
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
    onSelect: (BrowseGroup) -> Unit,
    rowSubtitle: (BrowseGroup) -> String = { it.subtitle },
    fastScrollLabels: List<String>? = groups.map { it.title },
    fastScrollSectionTargets: Map<String, Int>? = null,
    fastScrollDescending: Boolean = false,
    gridTitleMaxLines: Int = 2,
    listBottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val columns = gridColumns.coerceIn(1, 4)
    val configuration = LocalConfiguration.current
    val landscapeWindow =
        songListColumnsFor(configuration.screenWidthDp, configuration.screenHeightDp) > 1
    if (columns > 1) {
        if (fastScrollLabels == null) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = HifiSpacing.lg,
                    end = HifiSpacing.lg,
                    bottom = listBottomPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.lg),
            ) {
                gridItems(groups, key = { it.key }) { group ->
                    BrowseGroupGridTile(
                        group = group,
                        titleMaxLines = gridTitleMaxLines,
                        onClick = { onSelect(group) },
                    )
                }
            }
        } else {
            AlphabetFastScroller(
                labels = fastScrollLabels,
                sectionTargetsOverride = fastScrollSectionTargets,
                scrollToIndex = { gridState.scrollToItem(it) },
                descending = fastScrollDescending,
                fullHeightOverlay = landscapeWindow,
                modifier = modifier.fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = HifiSpacing.lg,
                        end = HifiSpacing.lg,
                        bottom = listBottomPadding,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(HifiSpacing.lg),
                ) {
                    gridItems(groups, key = { it.key }) { group ->
                        BrowseGroupGridTile(
                            group = group,
                            titleMaxLines = gridTitleMaxLines,
                            onClick = { onSelect(group) },
                        )
                    }
                }
            }
        }
        return
    }

    if (fastScrollLabels == null) {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            items(groups, key = { it.key }) { group ->
                BrowseGroupRow(
                    title = group.title,
                    subtitle = rowSubtitle(group),
                    albumArtUri = group.albumArtUri,
                    fallbackColor = Color(group.coverColorArgb),
                    onClick = { onSelect(group) },
                )
            }
        }
        return
    }

    AlphabetFastScroller(
        labels = fastScrollLabels,
        sectionTargetsOverride = fastScrollSectionTargets,
        scrollToIndex = { listState.scrollToItem(it) },
        descending = fastScrollDescending,
        fullHeightOverlay = landscapeWindow,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            items(groups, key = { it.key }) { group ->
                BrowseGroupRow(
                    title = group.title,
                    subtitle = rowSubtitle(group),
                    albumArtUri = group.albumArtUri,
                    fallbackColor = Color(group.coverColorArgb),
                    onClick = { onSelect(group) },
                )
            }
        }
    }
}

private fun albumRowSubtitle(group: BrowseGroup): String =
    listOfNotNull(
        group.artist.takeIf { it.isNotBlank() },
        ReleaseDates.displayLabel(group.year, group.releaseDate).takeIf { it.isNotBlank() },
        "${group.songCount} 首",
    ).joinToString(" · ")

@Composable
private fun BrowseGroupGridTile(
    group: BrowseGroup,
    onClick: () -> Unit,
    titleMaxLines: Int = 2,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
    ) {
        SongCover(
            albumArtUri = group.albumArtUri,
            fallbackColor = Color(group.coverColorArgb),
            contentDescription = group.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Text(
            text = group.title,
            style = MicaTheme.typography.bodyMd,
            color = MicaTheme.colors.textPrimary,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${group.songCount} 首",
            style = MicaTheme.typography.bodySm,
            color = MicaTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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
