package com.mica.music.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import com.mica.music.data.SleepTimerController
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.screens.NowPlayingContent
import com.mica.music.ui.screens.rememberNowPlayingActions

@Composable
fun PlayerSheetHost(
    library: MusicLibrary,
    playerController: PlayerController,
    sleepTimer: SleepTimerController,
    uiSettings: AppUiSettings,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSongDetail: (String) -> Unit = {},
    onBrowseArtist: (String) -> Unit = {},
    onBrowseAlbum: (String) -> Unit = {},
    onLocateCurrentSong: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
) {
    val surfaceState = playerController.playbackSurfaceState
    val progressState = playerController.playbackProgressState
    val queueState = playerController.playbackQueueState
    val actions = rememberNowPlayingActions(playerController, uiSettings)
    val song = surfaceState.currentSong ?: return
    val motionEnabled = rememberMicaMotionEnabled()
    val expansion = remember { Animatable(if (expanded) 1f else 0f) }

    LaunchedEffect(expanded, motionEnabled) {
        expansion.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs),
        )
    }

    val progress = expansion.value.coerceIn(0f, 1f)
    val showFullPlayer = expanded || progress > 0.01f

    BackHandler(enabled = showFullPlayer) {
        onExpandedChange(false)
    }

    Box(modifier.fillMaxSize()) {
        if (showFullPlayer) {
            val scrimInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress }
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable(
                        indication = null,
                        interactionSource = scrimInteraction,
                        onClick = {},
                    ),
            )
            val contentInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = progress
                        translationY = (1f - progress) * 96.dp.toPx()
                    }
                    .clickable(
                        indication = null,
                        interactionSource = contentInteraction,
                        onClick = {},
                    ),
            ) {
                NowPlayingContent(
                    library = library,
                    surfaceState = surfaceState,
                    progressState = progressState,
                    queueState = queueState,
                    sleepTimer = sleepTimer,
                    actions = actions,
                    uiSettings = uiSettings,
                    onClose = { onExpandedChange(false) },
                    onOpenEqualizer = onOpenEqualizer,
                    onOpenSongDetail = onOpenSongDetail,
                    onBrowseArtist = onBrowseArtist,
                    onBrowseAlbum = onBrowseAlbum,
                    contentPadding = contentPadding,
                )
            }
        }

        if (!expanded || progress < 0.99f) {
            MiniPlayer(
                style = uiSettings.miniPlayerStyle,
                song = song,
                isPlaying = surfaceState.isPlaying,
                onPlayPause = actions.togglePlay,
                onNext = actions.next,
                onExpand = { onExpandedChange(true) },
                onLongPress = onLocateCurrentSong,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        alpha = (1f - progress).coerceIn(0f, 1f)
                    },
            )
        }
    }
}
