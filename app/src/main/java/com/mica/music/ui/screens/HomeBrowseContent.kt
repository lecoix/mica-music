package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import com.mica.music.data.Song
import com.mica.music.ui.components.BrowseGroupRow
import com.mica.music.ui.components.SongListPanel
import com.mica.music.ui.theme.MicaTheme

internal sealed class BrowseDestination {
    data object Root : BrowseDestination()
    data class Artist(val name: String) : BrowseDestination()
    data class Album(val title: String) : BrowseDestination()
}

@Composable
internal fun HomeBrowseContent(
    section: HomeSection,
    destination: BrowseDestination,
    onDestinationChange: (BrowseDestination) -> Unit,
    library: MusicLibrary,
    playerController: PlayerController,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 列表滚动状态在 Root / 详情切换间保留，避免返回时回到顶部
    val artistListState = rememberLazyListState()
    val albumListState = rememberLazyListState()

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
            when (val dest = destination) {
                is BrowseDestination.Root -> {
                    ArtistGroupList(
                        library = library,
                        listState = artistListState,
                        onSelect = { onDestinationChange(BrowseDestination.Artist(it)) },
                        modifier = modifier,
                    )
                }
                is BrowseDestination.Artist -> {
                    val songListState = rememberBrowseDetailSongListState("artist:${dest.name}")
                    SongListPanel(
                        songs = library.songsForArtist(dest.name),
                        library = library,
                        playerController = playerController,
                        onSongClick = onSongClick,
                        onSongOpenMenu = onSongOpenMenu,
                        emptyMessage = "该歌手下暂无歌曲",
                        listState = songListState,
                        modifier = modifier,
                    )
                }
                else -> Unit
            }
        }
        HomeSection.Albums -> {
            when (val dest = destination) {
                is BrowseDestination.Root -> {
                    AlbumGroupList(
                        library = library,
                        listState = albumListState,
                        onSelect = { onDestinationChange(BrowseDestination.Album(it)) },
                        modifier = modifier,
                    )
                }
                is BrowseDestination.Album -> {
                    val songListState = rememberBrowseDetailSongListState("album:${dest.title}")
                    SongListPanel(
                        songs = library.songsForAlbum(dest.title),
                        library = library,
                        playerController = playerController,
                        onSongClick = onSongClick,
                        onSongOpenMenu = onSongOpenMenu,
                        emptyMessage = "该专辑下暂无歌曲",
                        listState = songListState,
                        modifier = modifier,
                    )
                }
                else -> Unit
            }
        }
        HomeSection.Recent -> {
            SongListPanel(
                songs = library.recentSongs(),
                library = library,
                playerController = playerController,
                onSongClick = onSongClick,
                onSongOpenMenu = onSongOpenMenu,
                emptyMessage = "暂无播放记录",
                modifier = modifier,
            )
        }
        HomeSection.Favorites -> {
            SongListPanel(
                songs = library.favoriteSongs(),
                library = library,
                playerController = playerController,
                onSongClick = onSongClick,
                onSongOpenMenu = onSongOpenMenu,
                emptyMessage = "还没有收藏的歌曲",
                modifier = modifier,
            )
        }
        else -> Unit
    }
}

/** 按歌手/专辑 key 保存详情歌曲列表滚动，返回同一详情时恢复位置。 */
@Composable
private fun rememberBrowseDetailSongListState(key: String): LazyListState =
    rememberSaveable(key, saver = LazyListState.Saver) { LazyListState() }

@Composable
private fun ArtistGroupList(
    library: MusicLibrary,
    listState: LazyListState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = library.artistGroups()
    if (groups.isEmpty()) {
        EmptyBrowseHint("暂无歌手", modifier)
        return
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
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
    modifier: Modifier = Modifier,
) {
    val groups = library.albumGroups()
    if (groups.isEmpty()) {
        EmptyBrowseHint("暂无专辑", modifier)
        return
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
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
private fun EmptyBrowseHint(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MicaTheme.typography.bodyMd,
            color = MicaTheme.colors.textTertiary,
        )
    }
}
