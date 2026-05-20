package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.mica.music.data.ArtistNames
import com.mica.music.data.Song
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        HorizontalDivider(
            thickness = HifiSize.dividerHairline,
            color = MicaTheme.colors.divider,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(HifiSize.miniPlayerHeight)
                .background(MicaTheme.colors.surfaceGlass) // glass bar behind row
                .clickable(onClick = onExpand)
                .padding(horizontal = HifiSpacing.md),
        ) {
            SongCover(
                albumArtUri = song.albumArtUri,
                fallbackColor = song.coverColor,
                contentDescription = song.title,
                modifier = Modifier.size(HifiSize.coverXs),
            )
            Spacer(Modifier.width(HifiSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MicaTheme.typography.bodyLg,
                    color = MicaTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = ArtistNames.normalizeDisplay(song.artist),
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SharpPlayPauseButton(
                isPlaying = isPlaying,
                onToggle = onPlayPause,
                size = HifiSize.iconLg,
                color = MicaTheme.colors.textPrimary,
            )
            Spacer(Modifier.width(HifiSpacing.sm))
            IconButton(
                onClick = onNext,
                modifier = Modifier.size(HifiSize.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    tint = MicaTheme.colors.textPrimary,
                    modifier = Modifier.size(HifiSize.iconLg),
                )
            }
        }
    }
}
