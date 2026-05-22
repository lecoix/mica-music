package com.mica.music.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.mica.music.data.ArtistNames
import com.mica.music.data.Song
import com.mica.music.ui.components.HiFiInfoRow
import com.mica.music.ui.components.HiResIndicator
import com.mica.music.ui.components.MarqueeTitleText
import com.mica.music.ui.components.textLineHeightDp
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors

@Composable
internal fun HiFiBadgeSection(
    song: Song,
    colors: PlayerContentColors,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg),
    ) {
        HiFiInfoRow(
            format = song.metadata.containerName,
            quality = song.sampleRateLabel,
            bitrate = song.bitrateLabel,
            modifier = Modifier.weight(1f),
            textColor = colors.tertiary,
        )
        if (song.isHiRes) {
            HiResIndicator()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SongTitleSection(
    title: String,
    artist: String,
    album: String,
    isBuffering: Boolean,
    playbackError: String?,
    colors: PlayerContentColors,
    immersiveProgress: Float,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val titleStyle = MicaTheme.typography.titleLg
    val titleLineHeight = textLineHeightDp(titleStyle)
    val artistLine = when {
        !playbackError.isNullOrBlank() -> playbackError
        isBuffering -> "Buffering..."
        else -> ArtistNames.normalizeDisplay(artist)
    }
    val fullSubtitle = when {
        !playbackError.isNullOrBlank() -> playbackError
        isBuffering -> "Buffering..."
        else -> "${ArtistNames.normalizeDisplay(artist)} - $album"
    }
    val isError = !playbackError.isNullOrBlank()
    val subtitleColor = if (isError) MicaTheme.colors.like else colors.secondary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg)
            .then(
                if (onLongPress != null) {
                    Modifier.combinedClickable(
                        onClick = onClick ?: {},
                        onLongClick = onLongPress,
                    )
                } else if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        MarqueeTitleText(
            text = title,
            style = titleStyle,
            color = colors.primary,
            lineHeight = titleLineHeight,
        )
        Box(Modifier.fillMaxWidth()) {
            Text(
                text = fullSubtitle,
                style = MicaTheme.typography.bodyMd,
                color = subtitleColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = 1f - immersiveProgress },
            )
            Text(
                text = artistLine,
                style = MicaTheme.typography.bodyMd,
                color = subtitleColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = immersiveProgress },
            )
        }
    }
}
