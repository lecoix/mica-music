package com.mica.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import com.mica.music.data.ArtistNames
import com.mica.music.data.PlaylistStore
import com.mica.music.data.Song
import com.mica.music.data.UserPlaylist
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

enum class SongMenuAction {
    AddToPlaylist,
    PlayNext,
    Share,
    EditTags,
    SongInfo,
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
    onAlbumClick: (String) -> Unit,
    fromPlaylistId: String? = null,
    showSleepTimer: Boolean = false,
    sleepTimerLabel: String = "睡眠定时",
    onSleepTimerClick: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MicaTheme.colors.isDark
    val sheetBackground = if (isDark) HifiPalette.MicaFogDarkEnd else HifiPalette.MicaFogStart
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.72f

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
            SongMenuItem(
                icon = Icons.Outlined.Share,
                label = "分享",
                onClick = { onAction(SongMenuAction.Share) },
            )
            SongMenuItem(
                icon = Icons.Outlined.Edit,
                label = "使用音乐标签编辑应用",
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

@Composable
private fun SongMenuHeader(
    song: Song,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
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
                    .clickable { onAlbumClick(albumDisplay) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    song: Song,
    playlistStore: PlaylistStore,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MicaTheme.colors.isDark
    val sheetBackground = if (isDark) HifiPalette.MicaFogDarkEnd else HifiPalette.MicaFogStart
    var showCreate by remember { mutableStateOf(false) }
    val playlists = playlistStore.playlists

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBackground,
        scrimColor = Color.Black.copy(alpha = if (isDark) 0.72f else 0.45f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = HifiSpacing.xl),
        ) {
            Text(
                text = "添加到歌单",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
                modifier = Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
            )
            Text(
                text = song.title,
                style = MicaTheme.typography.bodySm,
                color = MicaTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = HifiSpacing.lg),
            )
            SongMenuItem(
                icon = Icons.Outlined.Add,
                label = if (showCreate) "取消新建" else "新建歌单",
                onClick = { showCreate = !showCreate },
            )
            if (showCreate) {
                Text(
                    text = "输入名称后创建歌单并加入此曲；也可在侧栏「新建歌单」管理。",
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
                        playlistStore.addSongToPlaylist(playlist.id, song.id)
                        onCreated("已添加到「${playlist.name}」")
                        onDismiss()
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
                LazyColumn {
                    items(playlists, key = { it.id }) { playlist ->
                        PlaylistPickRow(
                            playlist = playlist,
                            onClick = {
                                playlistStore.addSongToPlaylist(playlist.id, song.id)
                                onCreated("已添加到「${playlist.name}」")
                                onDismiss()
                            },
                        )
                    }
                }
            }
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
