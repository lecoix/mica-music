package com.mica.music.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.mica.music.data.ArtistNames
import com.mica.music.data.PlaybackTuning
import com.mica.music.data.PlayerInfoVisibility
import com.mica.music.data.HiResBadgeAppearance
import com.mica.music.data.HiResBadgeStyle
import com.mica.music.data.Song
import com.mica.music.data.buildPlayerInfoSegments
import com.mica.music.data.formatPlayerInfoCurrentTime
import com.mica.music.data.millisUntilNextMinuteBoundary
import com.mica.music.ui.components.HiFiInfoRow
import com.mica.music.ui.components.HiResIndicator
import com.mica.music.ui.components.MarqueeTitleText
import com.mica.music.ui.components.textLineHeightDp
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors
import com.mica.music.ui.theme.rememberPlayerInfoRowHeight

@Composable
internal fun HiFiBadgeSection(
    song: Song,
    colors: PlayerContentColors,
    playerInfoVisibility: PlayerInfoVisibility,
    playbackTuning: PlaybackTuning,
    hiResBadgeAppearance: HiResBadgeAppearance,
    modifier: Modifier = Modifier,
) {
    val locale = LocalContext.current.resources.configuration.locales[0]
    var currentTimeLabel by remember { mutableStateOf(formatPlayerInfoCurrentTime(locale = locale)) }
    LaunchedEffect(playerInfoVisibility.showCurrentTime, locale) {
        if (!playerInfoVisibility.showCurrentTime) return@LaunchedEffect
        while (true) {
            currentTimeLabel = formatPlayerInfoCurrentTime(locale = locale)
            delay(millisUntilNextMinuteBoundary())
        }
    }
    val segments = buildPlayerInfoSegments(
        song = song,
        visibility = playerInfoVisibility,
        currentTimeLabel = currentTimeLabel.takeIf { playerInfoVisibility.showCurrentTime },
        playbackTuning = playbackTuning,
    )
    if (segments.isEmpty()) return
    val infoRowHeight = rememberPlayerInfoRowHeight()
    val usesOverflowBadge = song.isHiRes &&
        hiResBadgeAppearance.style == HiResBadgeStyle.CUSTOM_IMAGE &&
        hiResBadgeAppearance.customImagePath != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(infoRowHeight)
            .then(
                if (usesOverflowBadge) {
                    Modifier.graphicsLayer { clip = false }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = HifiSpacing.lg),
    ) {
        HiFiInfoRow(
            segments = segments,
            modifier = Modifier.weight(1f),
            textColor = colors.tertiary,
        )
        if (song.isHiRes) {
            HiResIndicator(
                appearance = hiResBadgeAppearance,
                rowHeight = infoRowHeight,
            )
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
    showAlbum: Boolean = true,
    modifier: Modifier = Modifier,
    contentScale: Float = 1f,
    onLongPress: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val titleStyle = MicaTheme.typography.titleLg.let { style ->
        style.copy(
            fontSize = style.fontSize * contentScale,
            lineHeight = style.lineHeight * contentScale,
        )
    }
    val titleLineHeight = textLineHeightDp(titleStyle)
    val artistLine = when {
        !playbackError.isNullOrBlank() -> playbackError
        isBuffering -> "Buffering..."
        else -> ArtistNames.normalizeDisplay(artist)
    }
    val fullSubtitle = when {
        !playbackError.isNullOrBlank() -> playbackError
        isBuffering -> "Buffering..."
        showAlbum && album.isNotBlank() -> "${ArtistNames.normalizeDisplay(artist)} - $album"
        else -> ArtistNames.normalizeDisplay(artist)
    }
    val isError = !playbackError.isNullOrBlank()
    val subtitleColor = if (isError) MicaTheme.colors.like else colors.secondary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm * contentScale),
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
                style = MicaTheme.typography.bodyMd.let { style ->
                    style.copy(
                        fontSize = style.fontSize * contentScale,
                        lineHeight = style.lineHeight * contentScale,
                    )
                },
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
                style = MicaTheme.typography.bodyMd.let { style ->
                    style.copy(
                        fontSize = style.fontSize * contentScale,
                        lineHeight = style.lineHeight * contentScale,
                    )
                },
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
