package com.mica.music.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mica.music.data.Song
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.coverColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistCoverSongSheet(
    songs: List<Song>,
    selectedSongId: String?,
    onSelect: (Song) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetBackground = if (MicaTheme.colors.isDark) {
        HifiPalette.MicaFogDarkEnd
    } else {
        HifiPalette.MicaFogStart
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBackground,
        scrimColor = Color.Black.copy(alpha = if (MicaTheme.colors.isDark) 0.72f else 0.45f),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "选择歌曲封面",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
                modifier = Modifier.padding(horizontal = HifiSpacing.lg),
            )
            CoverSongChoiceRow(
                title = "使用默认封面",
                subtitle = "清除当前自定义封面",
                selected = selectedSongId == null,
                onClick = onClear,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = HifiSpacing.lg,
                    end = HifiSpacing.lg,
                    bottom = HifiSpacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.xxs),
            ) {
                items(songs, key = { it.id }) { song ->
                    CoverSongChoiceRow(
                        title = song.title,
                        subtitle = song.artist,
                        coverUri = song.albumArtUri,
                        fallbackColor = song.coverColor,
                        selected = song.id == selectedSongId,
                        onClick = { onSelect(song) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverSongChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    coverUri: String? = null,
    fallbackColor: androidx.compose.ui.graphics.Color = MicaTheme.colors.surfaceCard,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
    ) {
        SongCover(
            albumArtUri = coverUri,
            fallbackColor = fallbackColor,
            contentDescription = title,
            modifier = Modifier.size(44.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MicaTheme.typography.bodyMd,
                color = if (selected) MicaTheme.colors.accent else MicaTheme.colors.textPrimary,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}
