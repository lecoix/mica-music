package com.mica.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.BrowseGroup
import com.mica.music.data.FolderBrowseGroup
import com.mica.music.data.LibraryBrowse
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.SongDetails
import com.mica.music.data.SortDirection
import com.mica.music.ui.components.AlphabetFastScroller
import com.mica.music.ui.components.BrowseGroupRow
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.components.SongListPanel
import com.mica.music.ui.components.SongRow
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

sealed class BrowseDestination {
    data object Root : BrowseDestination()
    data class Artist(val name: String) : BrowseDestination()
    data class Album(val title: String) : BrowseDestination()
    data class Folder(
        val depth: Int,
        val scopePathSegments: List<String> = emptyList(),
    ) : BrowseDestination()
}

data class HomeNavigationIntent(
    val section: HomeSection,
    val browseDestination: BrowseDestination,
)

private fun browseDestinationDepth(destination: BrowseDestination): Int = when (destination) {
    BrowseDestination.Root -> 0
    is BrowseDestination.Folder -> 1 + destination.depth
    else -> 1
}

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
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    albumSortField: AlbumBrowseSortField = AlbumBrowseSortField.TITLE,
    albumSortDirection: SortDirection = SortDirection.ASC,
    albumGridColumns: Int = 1,
    artistSortDirection: SortDirection = SortDirection.ASC,
    artistGridColumns: Int = 1,
    listBottomPadding: Dp = 0.dp,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val artistListState = rememberLazyListState()
    val albumListState = rememberLazyListState()
    val folderListState = rememberLazyListState()

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
                            onSelect = { onDestinationChange(BrowseDestination.Artist(it)) },
                            sortDirection = artistSortDirection,
                            gridColumns = artistGridColumns,
                            listBottomPadding = listBottomPadding,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    is BrowseDestination.Artist -> {
                        val songListState = rememberBrowseDetailSongListState("artist:${dest.name}")
                        val songs = library.songsForArtist(dest.name)
                        ArtistDetailPanel(
                            artistName = dest.name,
                            songs = songs,
                            currentSongId = currentSongId,
                            isPlaying = isPlaying,
                            onQueueSongs = onQueueSongs,
                            onAppendSongsToQueue = onAppendSongsToQueue,
                            onSongClick = onSongClick,
                            onSongOpenMenu = onSongOpenMenu,
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
                            onSelect = { onDestinationChange(BrowseDestination.Album(it)) },
                            sortField = albumSortField,
                            sortDirection = albumSortDirection,
                            gridColumns = albumGridColumns,
                            listBottomPadding = listBottomPadding,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    is BrowseDestination.Album -> {
                        val songListState = rememberBrowseDetailSongListState("album:${dest.title}")
                        val songs = library.songsForAlbum(dest.title)
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
                listBottomPadding = listBottomPadding,
                modifier = modifier,
            )
        }
        HomeSection.Folders -> {
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
        else -> Unit
    }
}

@Composable
private fun rememberBrowseDetailSongListState(key: String): LazyListState =
    rememberSaveable(key, saver = LazyListState.Saver) { LazyListState() }

private fun List<String>.scopeForFolderDepth(depth: Int): List<String> = when {
    depth <= 0 -> emptyList()
    size > depth -> take(depth)
    else -> this
}

private data class ArtistAlbumSection(
    val title: String,
    val year: Int,
    val albumArtUri: String?,
    val coverColorArgb: Int,
    val songs: List<Song>,
)

@Composable
private fun ArtistDetailPanel(
    artistName: String,
    songs: List<Song>,
    currentSongId: String?,
    isPlaying: Boolean,
    onQueueSongs: (List<Song>) -> Unit,
    onAppendSongsToQueue: (List<Song>) -> Unit,
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

    val albumSections = remember(songs) { artistAlbumSections(songs) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = listBottomPadding),
    ) {
        item("artistHeader") {
            ArtistDetailHeader(
                artistName = artistName,
                songs = songs,
                albumSections = albumSections,
                onPlayAll = {
                    onQueueSongs(songs)
                    songs.firstOrNull()?.let { onSongClick(it.id) }
                },
                onShuffle = {
                    val shuffled = songs.shuffled()
                    onQueueSongs(shuffled)
                    shuffled.firstOrNull()?.let { onSongClick(it.id) }
                },
                onAddToQueue = { onAppendSongsToQueue(songs) },
            )
        }
        albumSections.forEach { section ->
            item("albumHeader:${section.title}") {
                ArtistAlbumHeader(section = section)
            }
            itemsIndexed(section.songs, key = { _, song -> "artistSong:${song.id}" }) { trackIndex, song ->
                val isCurrent = currentSongId == song.id
                SongRow(
                    song = song,
                    trackNumber = song.trackNumber.takeIf { it > 0 }?.toString()?.padStart(2, '0')
                        ?: (trackIndex + 1).toString().padStart(2, '0'),
                    trailingLabel = song.durationLabel,
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && isPlaying,
                    onClick = {
                        onQueueSongs(songs)
                        onSongClick(song.id)
                    },
                    onLongClick = { onSongOpenMenu(song) },
                )
            }
        }
    }
}

@Composable
private fun ArtistDetailHeader(
    artistName: String,
    songs: List<Song>,
    albumSections: List<ArtistAlbumSection>,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = HifiSpacing.lg,
                top = 0.dp,
                end = HifiSpacing.lg,
                bottom = HifiSpacing.xl,
            ),
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
    ) {
        Text(
            text = "ARTIST",
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
        )
        Text(
            text = artistName,
            style = MicaTheme.typography.display.copy(fontSize = 44.sp, lineHeight = 52.sp),
            color = MicaTheme.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = artistStatsLine(songs, albumSections),
            style = MicaTheme.typography.monoSm,
            color = MicaTheme.colors.textTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ArtistAlbumStrip(albumSections)
        ArtistActionRow(
            onPlayAll = onPlayAll,
            onShuffle = onShuffle,
            onAddToQueue = onAddToQueue,
        )
        Text(
            text = "歌曲",
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
        )
    }
}

@Composable
private fun ArtistAlbumStrip(albumSections: List<ArtistAlbumSection>) {
    if (albumSections.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(albumSections, key = { it.title }) { album ->
            SongCover(
                albumArtUri = album.albumArtUri,
                fallbackColor = Color(album.coverColorArgb),
                contentDescription = album.title,
                modifier = Modifier
                    .width(76.dp)
                    .aspectRatio(1f),
            )
        }
    }
}

@Composable
private fun ArtistActionRow(
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistActionText("播放全部", emphasized = true, onClick = onPlayAll, modifier = Modifier.weight(1f))
        ArtistActionDivider()
        ArtistActionText("随机播放", onClick = onShuffle, modifier = Modifier.weight(1f))
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
private fun ArtistAlbumHeader(section: ArtistAlbumSection) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = HifiSpacing.xs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = section.title,
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (section.year > 0) {
                Text(
                    text = section.year.toString(),
                    style = MicaTheme.typography.bodyMd,
                    color = MicaTheme.colors.textTertiary,
                )
            }
        }
    }
}

private fun artistAlbumSections(songs: List<Song>): List<ArtistAlbumSection> {
    val buckets = linkedMapOf<String, MutableList<Song>>()
    songs.forEach { song ->
        buckets.getOrPut(song.album.ifBlank { "未知专辑" }) { mutableListOf() }.add(song)
    }
    return buckets.map { (album, albumSongs) ->
        val artworkSong = albumSongs.firstOrNull { !it.albumArtUri.isNullOrBlank() } ?: albumSongs.first()
        ArtistAlbumSection(
            title = album,
            year = albumSongs.map { it.year }.filter { it > 0 }.maxOrNull() ?: 0,
            albumArtUri = artworkSong.albumArtUri,
            coverColorArgb = artworkSong.coverColorArgb,
            songs = albumSongs.sortedWith(
                compareBy<Song> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                    .thenBy { it.title.lowercase() },
            ),
        )
    }.sortedWith(compareByDescending<ArtistAlbumSection> { it.year > 0 }.thenByDescending { it.year })
}

private fun artistStatsLine(
    songs: List<Song>,
    albumSections: List<ArtistAlbumSection>,
): String {
    val formats = songs.map { it.formatLabel }
        .filter { it.isNotBlank() }
        .distinct()
        .take(4)
        .joinToString(" / ")
        .ifBlank { "未知格式" }
    val totalSize = SongDetails.formatFileSize(songs.sumOf { it.sizeBytes.coerceAtLeast(0L) })
    return "${songs.size} 首歌曲 · ${albumSections.size} 张专辑 · $totalSize · $formats"
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
    onSelect: (String) -> Unit,
    sortDirection: SortDirection,
    gridColumns: Int,
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val groups = remember(library.songs, sortDirection) {
        LibraryBrowse.sortArtistGroups(library.artistGroups(), sortDirection)
    }
    if (groups.isEmpty()) {
        EmptyBrowseHint("暂无歌手", modifier)
        return
    }
    BrowseGroupList(
        groups = groups,
        listState = listState,
        gridColumns = gridColumns,
        onSelect = onSelect,
        fastScrollLabels = groups.map { it.title },
        fastScrollDescending = sortDirection == SortDirection.DESC,
        listBottomPadding = listBottomPadding,
        modifier = modifier,
    )
}

@Composable
private fun AlbumGroupList(
    library: MusicLibrary,
    listState: LazyListState,
    onSelect: (String) -> Unit,
    sortField: AlbumBrowseSortField,
    sortDirection: SortDirection,
    gridColumns: Int,
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val groups = remember(library.songs, sortField, sortDirection) {
        LibraryBrowse.sortAlbumGroups(library.albumGroups(), sortField, sortDirection)
    }
    if (groups.isEmpty()) {
        EmptyBrowseHint("暂无专辑", modifier)
        return
    }
    BrowseGroupList(
        groups = groups,
        listState = listState,
        gridColumns = gridColumns,
        onSelect = onSelect,
        rowSubtitle = ::albumRowSubtitle,
        fastScrollLabels = when (sortField) {
            AlbumBrowseSortField.TITLE -> groups.map { it.title }
            AlbumBrowseSortField.ARTIST -> groups.map { it.artist }
            AlbumBrowseSortField.YEAR,
            AlbumBrowseSortField.SONG_COUNT,
            -> null
        },
        fastScrollDescending = sortDirection == SortDirection.DESC,
        listBottomPadding = listBottomPadding,
        modifier = modifier,
    )
}

@Composable
private fun BrowseGroupList(
    groups: List<BrowseGroup>,
    listState: LazyListState,
    gridColumns: Int,
    onSelect: (String) -> Unit,
    rowSubtitle: (BrowseGroup) -> String = { it.subtitle },
    fastScrollLabels: List<String>? = groups.map { it.title },
    fastScrollDescending: Boolean = false,
    listBottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val columns = gridColumns.coerceIn(1, 4)
    if (columns > 1) {
        val gridState = rememberLazyGridState()
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
                gridItems(groups, key = { it.title }) { group ->
                    BrowseGroupGridTile(
                        group = group,
                        onClick = { onSelect(group.title) },
                    )
                }
            }
        } else {
            AlphabetFastScroller(
                labels = fastScrollLabels,
                scrollToIndex = { gridState.scrollToItem(it) },
                descending = fastScrollDescending,
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
                    gridItems(groups, key = { it.title }) { group ->
                        BrowseGroupGridTile(
                            group = group,
                            onClick = { onSelect(group.title) },
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
            items(groups, key = { it.title }) { group ->
                BrowseGroupRow(
                    title = group.title,
                    subtitle = rowSubtitle(group),
                    albumArtUri = group.albumArtUri,
                    fallbackColor = Color(group.coverColorArgb),
                    onClick = { onSelect(group.title) },
                )
            }
        }
        return
    }

    AlphabetFastScroller(
        labels = fastScrollLabels,
        scrollToIndex = { listState.scrollToItem(it) },
        descending = fastScrollDescending,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            items(groups, key = { it.title }) { group ->
                BrowseGroupRow(
                    title = group.title,
                    subtitle = rowSubtitle(group),
                    albumArtUri = group.albumArtUri,
                    fallbackColor = Color(group.coverColorArgb),
                    onClick = { onSelect(group.title) },
                )
            }
        }
    }
}

private fun albumRowSubtitle(group: BrowseGroup): String =
    listOfNotNull(
        group.artist.takeIf { it.isNotBlank() },
        group.year.takeIf { it > 0 }?.toString(),
        "${group.songCount} 首",
    ).joinToString(" · ")

@Composable
private fun BrowseGroupGridTile(
    group: BrowseGroup,
    onClick: () -> Unit,
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
            maxLines = 2,
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
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = listBottomPadding),
    ) {
        items(groups, key = { it.pathSegments.joinToString("/") }) { group ->
            BrowseGroupRow(
                title = group.title,
                subtitle = group.subtitle,
                onClick = { onSelect(group) },
            )
        }
        items(songs, key = { "song:${it.id}" }) { song ->
            val isCurrent = currentSongId == song.id
            SongRow(
                song = song,
                isCurrent = isCurrent,
                isPlaying = isCurrent && isPlaying,
                onClick = { onSongClick(song.id) },
                onLongClick = { onSongOpenMenu(song) },
            )
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
