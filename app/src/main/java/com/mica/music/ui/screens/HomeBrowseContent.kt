package com.mica.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.FolderBrowseGroup
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import com.mica.music.data.Song
import com.mica.music.ui.components.BrowseGroupRow
import com.mica.music.ui.components.SongRow
import com.mica.music.ui.components.SongListPanel
import com.mica.music.ui.motion.MicaMotion
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
    playerController: PlayerController,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    listBottomPadding: Dp = 0.dp,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // 列表滚动状态在 Root / 详情切换间保留，避免返回时回到顶部
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
                            listBottomPadding = listBottomPadding,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    is BrowseDestination.Artist -> {
                        val songListState = rememberBrowseDetailSongListState("artist:${dest.name}")
                        val songs = library.songsForArtist(dest.name)
                        SongListPanel(
                            songs = songs,
                            library = library,
                            playerController = playerController,
                            onSongClick = { songId ->
                                playerController.setQueue(songs)
                                onSongClick(songId)
                            },
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
                            playerController = playerController,
                            onSongClick = { songId ->
                                playerController.setQueue(songs)
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
                playerController = playerController,
                onSongClick = { songId ->
                    playerController.setQueue(songs)
                    onSongClick(songId)
                },
                onSongOpenMenu = onSongOpenMenu,
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
                    playerController = playerController,
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

/** 按歌手/专辑 key 保存详情歌曲列表滚动，返回同一详情时恢复位置。 */
@Composable
private fun rememberBrowseDetailSongListState(key: String): LazyListState =
    rememberSaveable(key, saver = LazyListState.Saver) { LazyListState() }

private fun List<String>.scopeForFolderDepth(depth: Int): List<String> = when {
    depth <= 0 -> emptyList()
    size > depth -> take(depth)
    else -> this
}

@Composable
private fun FolderDepthPage(
    depth: Int,
    scopePathSegments: List<String>,
    library: MusicLibrary,
    playerController: PlayerController,
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
                playerController = playerController,
                listState = listState,
                onSelect = onFolderSelect,
                onSongClick = { songId ->
                    val queue = if (scopePathSegments.isNotEmpty()) {
                        library.songsForFolder(scopePathSegments)
                    } else {
                        songsInScope
                    }
                    playerController.setQueue(queue)
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
                playerController = playerController,
                onSongClick = { songId ->
                    playerController.setQueue(songs)
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
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val groups = library.artistGroups()
    if (groups.isEmpty()) {
        EmptyBrowseHint("暂无歌手", modifier)
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = listBottomPadding),
    ) {
        items(groups, key = { it.title }) { group ->
            BrowseGroupRow(
                title = group.title,
                subtitle = group.subtitle,
                onClick = { onSelect(group.title) },
            )
        }
    }
}

@Composable
private fun AlbumGroupList(
    library: MusicLibrary,
    listState: LazyListState,
    onSelect: (String) -> Unit,
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val groups = library.albumGroups()
    if (groups.isEmpty()) {
        EmptyBrowseHint("暂无专辑", modifier)
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = listBottomPadding),
    ) {
        items(groups, key = { it.title }) { group ->
            BrowseGroupRow(
                title = group.title,
                subtitle = "${group.subtitle} · ${group.songCount} 首",
                onClick = { onSelect(group.title) },
            )
        }
    }
}

@Composable
private fun FolderContentList(
    groups: List<FolderBrowseGroup>,
    songs: List<Song>,
    playerController: PlayerController,
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
            val isCurrent = playerController.currentSong?.id == song.id
            SongRow(
                song = song,
                isCurrent = isCurrent,
                isPlaying = isCurrent && playerController.isPlaying,
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
