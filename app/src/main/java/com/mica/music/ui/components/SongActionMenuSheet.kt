package com.mica.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.data.AlbumBrowseKey
import com.mica.music.data.ArtistNames
import com.mica.music.data.PlaylistStore
import com.mica.music.data.Song
import com.mica.music.data.UserPlaylist
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.coverColor

enum class SongMenuAction {
    AddToPlaylist,
    PlayNext,
    Share,
    EditTags,
    SongInfo,
    LyricsOffset,
    RemoveFromPlaylist,
    Delete,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionMenuSheet(
    song: Song,
    onDismiss: () -> Unit,
    onAction: (SongMenuAction) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (AlbumBrowseKey) -> Unit,
    fromPlaylistId: String? = null,
    showSleepTimer: Boolean = false,
    sleepTimerLabel: String = "睡眠定时",
    onSleepTimerClick: (() -> Unit)? = null,
    showPlaybackTuning: Boolean = false,
    playbackTuningLabel: String = "速度 / 音高",
    onPlaybackTuningClick: (() -> Unit)? = null,
    showLyricsOffset: Boolean = false,
    showLibraryActions: Boolean = true,
    landscape: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MicaTheme.colors.isDark
    val sheetBackground = if (isDark) HifiPalette.MicaFogDarkEnd else HifiPalette.MicaFogStart
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.72f

    if (landscape) {
        PlayerSidePanel(
            onDismiss = onDismiss,
            containerColor = sheetBackground,
            scrimColor = Color.Black.copy(alpha = if (isDark) 0.42f else 0.28f),
            paneTitle = "歌曲操作",
        ) {
            LandscapeSongActionMenu(
                song = song,
                onDismiss = onDismiss,
                onAction = onAction,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
                fromPlaylistId = fromPlaylistId,
                showSleepTimer = showSleepTimer,
                sleepTimerLabel = sleepTimerLabel,
                onSleepTimerClick = onSleepTimerClick,
                showPlaybackTuning = showPlaybackTuning,
                playbackTuningLabel = playbackTuningLabel,
                onPlaybackTuningClick = onPlaybackTuningClick,
                showLyricsOffset = showLyricsOffset,
                showLibraryActions = showLibraryActions,
            )
        }
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBackground,
        scrimColor = Color.Black.copy(alpha = if (isDark) 0.72f else 0.45f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .padding(bottom = HifiSpacing.xl),
        ) {
            SongMenuHeader(
                song = song,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
            )
            HorizontalDivider(color = MicaTheme.colors.divider, thickness = HifiSize.dividerHairline)
            if (showLibraryActions) {
            SongMenuItem(
                icon = Icons.Outlined.PlaylistAdd,
                label = "添加到歌单",
                onClick = { onAction(SongMenuAction.AddToPlaylist) },
            )
            SongMenuItem(
                icon = Icons.Outlined.SkipNext,
                label = "下一首播放",
                onClick = { onAction(SongMenuAction.PlayNext) },
            )
            if (showSleepTimer && onSleepTimerClick != null) {
                SongMenuItem(
                    icon = Icons.Outlined.Bedtime,
                    label = sleepTimerLabel,
                    onClick = onSleepTimerClick,
                )
            }
            if (showPlaybackTuning && onPlaybackTuningClick != null) {
                SongMenuItem(
                    icon = Icons.Outlined.Speed,
                    label = playbackTuningLabel,
                    onClick = onPlaybackTuningClick,
                )
            }
            if (showLyricsOffset) {
                SongMenuItem(
                    icon = Icons.Outlined.Tune,
                    label = "歌词偏移",
                    onClick = { onAction(SongMenuAction.LyricsOffset) },
                )
            }
            SongMenuItem(
                icon = Icons.Outlined.Share,
                label = "分享",
                onClick = { onAction(SongMenuAction.Share) },
            )
            SongMenuItem(
                icon = Icons.Outlined.Edit,
                label = "使用Lyrico编辑音乐标签",
                onClick = { onAction(SongMenuAction.EditTags) },
            )
            SongMenuItem(
                icon = Icons.Outlined.Info,
                label = "歌曲信息",
                onClick = { onAction(SongMenuAction.SongInfo) },
            )
            if (fromPlaylistId != null) {
                SongMenuItem(
                    icon = Icons.Outlined.PlaylistRemove,
                    label = "从此歌单中移除",
                    onClick = { onAction(SongMenuAction.RemoveFromPlaylist) },
                )
            }
            SongMenuItem(
                icon = Icons.Outlined.Delete,
                label = "删除音乐",
                tint = MicaTheme.colors.like,
                onClick = { onAction(SongMenuAction.Delete) },
            )
            }
        }
    }
}

@Composable
private fun SongMenuHeader(
    song: Song,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (AlbumBrowseKey) -> Unit,
) {
    val artistDisplay = ArtistNames.normalizeDisplay(song.artist)
    val albumDisplay = song.album.ifBlank { "未知专辑" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
    ) {
        SongCover(
            albumArtUri = song.albumArtUri,
            fallbackColor = song.coverColor,
            contentDescription = song.title,
            decodeTarget = CoverDecodeTarget.forCompactCover(),
            modifier = Modifier.size(HifiSize.coverMd),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artistDisplay,
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = HifiSpacing.xxs)
                    .clickable {
                        val name = ArtistNames.split(song.artist).firstOrNull() ?: artistDisplay
                        onArtistClick(name)
                    },
            )
            Text(
                text = albumDisplay,
                style = MicaTheme.typography.bodySm,
                color = MicaTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = HifiSpacing.xxs)
                    .clickable { onAlbumClick(AlbumBrowseKey.fromSong(song)) },
            )
        }
    }
}

@Composable
private fun SongMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MicaTheme.colors.textPrimary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(HifiSize.iconMd),
        )
        Text(
            text = label,
            style = MicaTheme.typography.bodyLg,
            color = tint,
        )
    }
}

private data class LandscapeSongMenuEntry(
    val icon: ImageVector,
    val label: String,
    val tint: Color? = null,
    val onClick: () -> Unit,
)

@Composable
private fun LandscapeSongActionMenu(
    song: Song,
    onDismiss: () -> Unit,
    onAction: (SongMenuAction) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (AlbumBrowseKey) -> Unit,
    fromPlaylistId: String?,
    showSleepTimer: Boolean,
    sleepTimerLabel: String,
    onSleepTimerClick: (() -> Unit)?,
    showPlaybackTuning: Boolean,
    playbackTuningLabel: String,
    onPlaybackTuningClick: (() -> Unit)?,
    showLyricsOffset: Boolean,
    showLibraryActions: Boolean,
) {
    val entries = buildList {
        if (!showLibraryActions) {
            add(LandscapeSongMenuEntry(Icons.Outlined.Share, "分享") {
                onAction(SongMenuAction.Share)
            })
            if (showSleepTimer && onSleepTimerClick != null) {
                add(LandscapeSongMenuEntry(Icons.Outlined.Bedtime, sleepTimerLabel, onClick = onSleepTimerClick))
            }
            if (showPlaybackTuning && onPlaybackTuningClick != null) {
                add(LandscapeSongMenuEntry(Icons.Outlined.Speed, playbackTuningLabel, onClick = onPlaybackTuningClick))
            }
            if (showLyricsOffset) {
                add(LandscapeSongMenuEntry(Icons.Outlined.Tune, "歌词偏移") {
                    onAction(SongMenuAction.LyricsOffset)
                })
            }
        }
        if (showLibraryActions) {
        add(LandscapeSongMenuEntry(Icons.Outlined.PlaylistAdd, "添加到歌单") {
            onAction(SongMenuAction.AddToPlaylist)
        })
        add(LandscapeSongMenuEntry(Icons.Outlined.SkipNext, "下一首播放") {
            onAction(SongMenuAction.PlayNext)
        })
        if (showSleepTimer && onSleepTimerClick != null) {
            add(LandscapeSongMenuEntry(Icons.Outlined.Bedtime, sleepTimerLabel, onClick = onSleepTimerClick))
        }
        if (showPlaybackTuning && onPlaybackTuningClick != null) {
            add(LandscapeSongMenuEntry(Icons.Outlined.Speed, playbackTuningLabel, onClick = onPlaybackTuningClick))
        }
        if (showLyricsOffset) {
            add(LandscapeSongMenuEntry(Icons.Outlined.Tune, "歌词偏移") {
                onAction(SongMenuAction.LyricsOffset)
            })
        }
        add(LandscapeSongMenuEntry(Icons.Outlined.Share, "分享") {
            onAction(SongMenuAction.Share)
        })
        add(LandscapeSongMenuEntry(Icons.Outlined.Edit, "使用 Lyrico 编辑音乐标签") {
            onAction(SongMenuAction.EditTags)
        })
        add(LandscapeSongMenuEntry(Icons.Outlined.Info, "歌曲信息") {
            onAction(SongMenuAction.SongInfo)
        })
        if (fromPlaylistId != null) {
            add(LandscapeSongMenuEntry(Icons.Outlined.PlaylistRemove, "从此歌单中移除") {
                onAction(SongMenuAction.RemoveFromPlaylist)
            })
        }
        add(LandscapeSongMenuEntry(Icons.Outlined.Delete, "删除音乐", MicaTheme.colors.like) {
            onAction(SongMenuAction.Delete)
        })
        }
    }

    Column(modifier = Modifier.fillMaxHeight()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                SongMenuHeader(
                    song = song,
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.padding(end = HifiSpacing.md),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "关闭菜单",
                    tint = MicaTheme.colors.textSecondary,
                )
            }
        }
        HorizontalDivider(color = MicaTheme.colors.divider, thickness = HifiSize.dividerHairline)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(HifiSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
        ) {
            items(entries.size) { index ->
                LandscapeSongMenuItem(entries[index])
            }
        }
    }
}

@Composable
private fun LandscapeSongMenuItem(entry: LandscapeSongMenuEntry) {
    val tint = entry.tint ?: MicaTheme.colors.textPrimary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = entry.onClick),
        color = MicaTheme.colors.surfaceGlass,
        shape = androidx.compose.ui.graphics.RectangleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = HifiSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
        ) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(HifiSize.iconMd),
            )
            Text(
                text = entry.label,
                style = MicaTheme.typography.bodyMd,
                color = tint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    songs: List<Song>,
    playlistStore: PlaylistStore,
    addAsCustomOrder: Boolean = false,
    resolveSong: (String) -> Song? = { null },
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
    landscape: Boolean = false,
) {
    if (songs.isEmpty()) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MicaTheme.colors.isDark
    val sheetBackground = if (isDark) HifiPalette.MicaFogDarkEnd else HifiPalette.MicaFogStart
    var showCreate by remember { mutableStateOf(false) }
    val playlists = playlistStore.playlists
    val songIds = songs.map { it.id }
    val subtitle = when (songs.size) {
        1 -> songs.first().title
        else -> "已选 ${songs.size} 首"
    }
    fun addToPlaylist(playlistId: String, playlistName: String) {
        if (addAsCustomOrder) {
            val displayedIds = playlistStore.songsForPlaylist(playlistId, resolveSong).map { it.id }
            playlistStore.appendSongsAsCustomOrder(playlistId, displayedIds, songIds)
        } else {
            playlistStore.addSongsToPlaylist(playlistId, songIds)
        }
        val message = if (songs.size == 1) {
            "已添加到「$playlistName」"
        } else {
            "已将 ${songs.size} 首添加到「$playlistName」"
        }
        onCreated(message)
        onDismiss()
    }

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (landscape) Modifier.fillMaxHeight() else Modifier)
                .padding(bottom = HifiSpacing.xl),
        ) {
            if (landscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = HifiSpacing.lg, end = HifiSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "添加到歌单",
                            style = MicaTheme.typography.titleMd,
                            color = MicaTheme.colors.textPrimary,
                        )
                        Text(
                            text = subtitle,
                            style = MicaTheme.typography.bodySm,
                            color = MicaTheme.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭歌单选择",
                            tint = MicaTheme.colors.textSecondary,
                        )
                    }
                }
                HorizontalDivider(color = MicaTheme.colors.divider)
            } else {
                Text(
                    text = "添加到歌单",
                    style = MicaTheme.typography.titleMd,
                    color = MicaTheme.colors.textPrimary,
                    modifier = Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
                )
                Text(
                    text = subtitle,
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = HifiSpacing.lg),
                )
            }
            SongMenuItem(
                icon = Icons.Outlined.Add,
                label = if (showCreate) "取消新建" else "新建歌单",
                onClick = { showCreate = !showCreate },
            )
            if (showCreate) {
                Text(
                    text = if (songs.size == 1) {
                        "输入名称后创建歌单并加入此曲；也可在侧栏「新建歌单」管理。"
                    } else {
                        "输入名称后创建歌单并加入所选歌曲；也可在侧栏「新建歌单」管理。"
                    },
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textTertiary,
                    modifier = Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.xs),
                )
                SongMenuItem(
                    icon = Icons.Outlined.PlaylistAdd,
                    label = "创建「我的歌单」并添加",
                    onClick = {
                        val playlist = playlistStore.playlists.find { it.name == "我的歌单" }
                            ?: playlistStore.createPlaylist("我的歌单")
                        addToPlaylist(playlist.id, playlist.name)
                    },
                )
            }
            if (playlists.isEmpty() && !showCreate) {
                Text(
                    text = "暂无歌单，请新建",
                    style = MicaTheme.typography.bodyMd,
                    color = MicaTheme.colors.textTertiary,
                    modifier = Modifier.padding(HifiSpacing.lg),
                )
            } else {
                LazyColumn(
                    modifier = if (landscape) Modifier.weight(1f) else Modifier,
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        PlaylistPickRow(
                            playlist = playlist,
                            onClick = { addToPlaylist(playlist.id, playlist.name) },
                        )
                    }
                }
            }
        }
    }

    if (landscape) {
        PlayerSidePanel(
            onDismiss = onDismiss,
            containerColor = sheetBackground,
            scrimColor = Color.Black.copy(alpha = if (isDark) 0.42f else 0.28f),
            paneTitle = "添加到歌单",
            content = content,
        )
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = sheetBackground,
            scrimColor = Color.Black.copy(alpha = if (isDark) 0.72f else 0.45f),
        ) {
            content()
        }
    }
}

@Composable
private fun PlaylistPickRow(
    playlist: UserPlaylist,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = playlist.name,
            style = MicaTheme.typography.bodyLg,
            color = MicaTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${playlist.songIds.size} 首",
            style = MicaTheme.typography.monoSm,
            color = MicaTheme.colors.textTertiary,
        )
    }
}
