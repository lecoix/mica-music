package com.mica.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.PlaybackQueueMode
import com.mica.music.data.PlayerController
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors

@Composable
internal fun PlayerPlaybackBottomSection(
    playerController: PlayerController,
    colors: PlayerContentColors,
    seekState: PlaybackSeekState,
    showStandardProgress: Boolean,
    afterProgress: Dp,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg),
    ) {
        if (showStandardProgress) {
            PlayerProgressBarSection(seekState, colors)
            Spacer(Modifier.height(afterProgress))
        }
        PlayerPlaybackControlsSection(
            playerController = playerController,
            colors = colors,
            onOpenQueue = onOpenQueue,
        )
    }
}

@Composable
internal fun PlayerProgressBarSection(
    seekState: PlaybackSeekState,
    colors: PlayerContentColors,
    modifier: Modifier = Modifier,
    spectrumEnabled: Boolean = false,
    spectrumPlaying: Boolean = true,
    spectrumAlpha: Float = 1f,
    spectrumHeight: Dp = 56.dp,
) {
    val spectrumProgress = spectrumAlpha.coerceIn(0f, 1f)
    val showSpectrum = spectrumEnabled && spectrumProgress > 0.01f
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (showSpectrum) {
                LivePlayerSpectrumStrip(
                    enabled = true,
                    isPlaying = spectrumPlaying,
                    colors = colors,
                    height = spectrumHeight * spectrumProgress,
                    alpha = spectrumProgress,
                    modifier = Modifier.graphicsLayer {
                        translationY = -15.dp.toPx()
                    },
                )
            }
            HiFiSeekBar(
                value = seekState.sliderValue,
                onValueChange = seekState.onValueChange,
                onValueChangeFinished = seekState.onValueChangeFinished,
                valueRange = seekState.valueRange,
                colors = colors,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatPlaybackTime(seekState.displaySec),
                style = MicaTheme.typography.monoMd,
                color = colors.secondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatPlaybackTime(seekState.totalSec),
                style = MicaTheme.typography.monoMd,
                color = colors.secondary,
            )
        }
    }
}

@Composable
internal fun PlayerPlaybackControlsSection(
    playerController: PlayerController,
    colors: PlayerContentColors,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = playerController.playbackQueueMode
    val modeActive = mode != PlaybackQueueMode.OFF
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = modifier.fillMaxWidth(),
    ) {
        IconButton(
            onClick = { playerController.cyclePlaybackQueueMode() },
            modifier = Modifier.size(HifiSize.touchTarget),
        ) {
            Icon(
                imageVector = playbackQueueModeIcon(mode),
                contentDescription = playbackQueueModeDescription(mode),
                tint = if (modeActive) colors.primary else colors.secondary,
                modifier = Modifier.size(HifiSize.iconLg),
            )
        }
        IconButton(
            onClick = { playerController.previous() },
            modifier = Modifier.size(HifiSize.touchTarget),
        ) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "上一首",
                tint = colors.primary,
                modifier = Modifier.size(HifiSize.iconXl),
            )
        }
        SharpPlayPauseButton(
            isPlaying = playerController.isPlaying,
            onToggle = { playerController.togglePlay() },
            size = HifiSize.iconXxl,
            color = colors.primary,
        )
        IconButton(
            onClick = { playerController.next() },
            modifier = Modifier.size(HifiSize.touchTarget),
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "下一首",
                tint = colors.primary,
                modifier = Modifier.size(HifiSize.iconXl),
            )
        }
        IconButton(
            onClick = onOpenQueue,
            modifier = Modifier.size(HifiSize.touchTarget),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.QueueMusic,
                contentDescription = "播放列表",
                tint = colors.secondary,
                modifier = Modifier.size(HifiSize.iconLg),
            )
        }
    }
}

private fun playbackQueueModeIcon(mode: PlaybackQueueMode) = when (mode) {
    PlaybackQueueMode.OFF -> Icons.Outlined.Repeat
    PlaybackQueueMode.REPEAT_ALL -> Icons.Outlined.Repeat
    PlaybackQueueMode.REPEAT_ONE -> Icons.Filled.RepeatOne
    PlaybackQueueMode.SHUFFLE -> Icons.Outlined.Shuffle
}

private fun playbackQueueModeDescription(mode: PlaybackQueueMode) = when (mode) {
    PlaybackQueueMode.OFF -> "顺序播放"
    PlaybackQueueMode.REPEAT_ALL -> "列表循环"
    PlaybackQueueMode.REPEAT_ONE -> "单曲循环"
    PlaybackQueueMode.SHUFFLE -> "随机播放"
}

internal fun formatPlaybackTime(seconds: Int): String =
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
