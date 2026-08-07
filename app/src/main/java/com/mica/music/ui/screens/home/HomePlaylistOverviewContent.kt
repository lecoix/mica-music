package com.mica.music.ui.screens.home

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mica.music.data.Song
import com.mica.music.data.UserPlaylist
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.coverColor
import java.io.File

internal enum class PlaylistOverviewAction {
    OPEN,
    RENAME,
    CHOOSE_SONG_COVER,
    IMPORT_COVER,
    EXPORT,
    DELETE,
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomePlaylistOverviewContent(
    playlists: List<UserPlaylist>,
    resolveSong: (String) -> Song?,
    onAction: (UserPlaylist, PlaylistOverviewAction) -> Unit,
    onCreatePlaylist: () -> Unit,
    onImportPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playlists.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "还没有歌单",
                style = MicaTheme.typography.bodyLg,
                color = MicaTheme.colors.textSecondary,
            )
            Spacer(Modifier.size(HifiSpacing.md))
            PlaylistOverviewActions(
                onCreatePlaylist = onCreatePlaylist,
                onImportPlaylist = onImportPlaylist,
                horizontalArrangement = Arrangement.spacedBy(
                    HifiSpacing.lg,
                    Alignment.CenterHorizontally,
                ),
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = HifiSpacing.lg,
            vertical = HifiSpacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
    ) {
        item {
            PlaylistOverviewActions(
                onCreatePlaylist = onCreatePlaylist,
                onImportPlaylist = onImportPlaylist,
                modifier = Modifier.padding(bottom = HifiSpacing.sm),
            )
        }
        items(playlists, key = { it.id }) { playlist ->
            PlaylistOverviewRow(
                playlist = playlist,
                coverSong = playlist.coverSongId?.let(resolveSong),
                onAction = { action -> onAction(playlist, action) },
            )
        }
    }
}

@Composable
private fun PlaylistOverviewActions(
    onCreatePlaylist: () -> Unit,
    onImportPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(HifiSpacing.lg),
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverviewAction(
            icon = Icons.Outlined.Add,
            label = "新建歌单",
            onClick = onCreatePlaylist,
        )
        OverviewAction(
            icon = Icons.Outlined.FileDownload,
            label = "导入歌单",
            onClick = onImportPlaylist,
        )
    }
}

@Composable
private fun OverviewAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = HifiSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MicaTheme.colors.accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MicaTheme.typography.bodyMd,
            color = MicaTheme.colors.accent,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistOverviewRow(
    playlist: UserPlaylist,
    coverSong: Song?,
    onAction: (PlaylistOverviewAction) -> Unit,
) {
    var menuExpanded by remember(playlist.id) { mutableStateOf(false) }
    val coverUri = playlist.customCoverPath?.let { path ->
        runCatching { Uri.fromFile(File(path)).toString() }.getOrNull()
    } ?: coverSong?.albumArtUri
    val fallbackColor = coverSong?.coverColor ?: MicaTheme.colors.surfaceCard

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onAction(PlaylistOverviewAction.OPEN) },
                    onLongClick = { menuExpanded = true },
                )
                .padding(vertical = HifiSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongCover(
                albumArtUri = coverUri,
                fallbackColor = fallbackColor,
                contentDescription = playlist.name,
                modifier = Modifier.size(64.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = HifiSpacing.md),
            ) {
                Text(
                    text = playlist.name,
                    style = MicaTheme.typography.bodyLg,
                    color = MicaTheme.colors.textPrimary,
                    maxLines = 1,
                )
                Text(
                    text = "${playlist.songIds.size} 首",
                    style = MicaTheme.typography.monoSm,
                    color = MicaTheme.colors.textTertiary,
                    modifier = Modifier.padding(top = HifiSpacing.xxs),
                )
            }
            Icon(
                imageVector = Icons.Outlined.PlaylistPlay,
                contentDescription = null,
                tint = MicaTheme.colors.textTertiary,
                modifier = Modifier.size(22.dp),
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            PlaylistOverviewAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = action.label,
                            style = MicaTheme.typography.bodyMd,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onAction(action)
                    },
                )
            }
        }
    }
}

private val PlaylistOverviewAction.label: String
    get() = when (this) {
        PlaylistOverviewAction.OPEN -> "打开歌单"
        PlaylistOverviewAction.RENAME -> "重命名"
        PlaylistOverviewAction.CHOOSE_SONG_COVER -> "选择歌曲封面"
        PlaylistOverviewAction.IMPORT_COVER -> "导入封面"
        PlaylistOverviewAction.EXPORT -> "导出歌单"
        PlaylistOverviewAction.DELETE -> "删除歌单"
    }
