package com.mica.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlaylistStore
import com.mica.music.data.PlaybackProgressState
import com.mica.music.data.PlaybackQueueState
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.data.SleepTimerController
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.screens.NowPlayingActions
import com.mica.music.ui.screens.NowPlayingContent

internal enum class PlayerSheetPhase {
    Collapsed,
    Expanded,
    Closing,
}

internal fun playerSheetPhaseForExternalExpanded(
    current: PlayerSheetPhase,
    expanded: Boolean,
    progress: Float,
): PlayerSheetPhase = when {
    expanded -> PlayerSheetPhase.Expanded
    current != PlayerSheetPhase.Collapsed || progress > 0f -> PlayerSheetPhase.Closing
    else -> PlayerSheetPhase.Collapsed
}

internal fun PlayerSheetPhase.keepsOverlayOpen(externalExpanded: Boolean): Boolean =
    externalExpanded || this != PlayerSheetPhase.Collapsed

internal fun playerSheetProgressForPredictiveBack(
    animatedProgress: Float,
    predictiveBackProgress: Float?,
): Float {
    val progress = predictiveBackProgress?.let { 1f - it } ?: animatedProgress
    return progress.coerceIn(0f, 1f)
}

@Composable
fun PlayerSheetHost(
    library: MusicLibrary,
    playlistStore: PlaylistStore,
    surfaceState: PlaybackSurfaceState,
    progressState: PlaybackProgressState,
    queueState: PlaybackQueueState,
    sleepTimer: SleepTimerController,
    actions: NowPlayingActions,
    uiSettings: AppUiSettings,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSongDetail: (String) -> Unit = {},
    onBrowseArtist: (String) -> Unit = {},
    onBrowseAlbum: (String) -> Unit = {},
    onLocateCurrentSong: () -> Unit = {},
    onOverlayFullScreenChange: (Boolean) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    predictiveBackProgress: Float? = null,
) {
    val summarySong = surfaceState.currentSong
    if (summarySong == null) {
        SideEffect { onOverlayFullScreenChange(false) }
        return
    }
    val nextSong = queueState.queue.getOrNull(queueState.currentIndex + 1)
    val song = rememberSongWithLyrics(library, summarySong, nextSong, uiSettings.lyricsSlotPriority)
    val hydratedSurfaceState = surfaceState.copy(currentSong = song)
    val motionEnabled = rememberMicaMotionEnabled()
    val expansion = remember { Animatable(if (expanded) 1f else 0f) }
    var sheetPhase by remember {
        mutableStateOf(if (expanded) PlayerSheetPhase.Expanded else PlayerSheetPhase.Collapsed)
    }

    LaunchedEffect(expanded, motionEnabled, predictiveBackProgress) {
        val backProgress = predictiveBackProgress
        if (backProgress != null) {
            sheetPhase = PlayerSheetPhase.Expanded
            expansion.snapTo(
                playerSheetProgressForPredictiveBack(
                    animatedProgress = expansion.value,
                    predictiveBackProgress = backProgress,
                ),
            )
            return@LaunchedEffect
        }

        val nextPhase = playerSheetPhaseForExternalExpanded(sheetPhase, expanded, expansion.value)
        when (nextPhase) {
            PlayerSheetPhase.Expanded -> {
                sheetPhase = PlayerSheetPhase.Expanded
                expansion.animateTo(
                    targetValue = 1f,
                    animationSpec = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs),
                )
            }
            PlayerSheetPhase.Closing -> {
                sheetPhase = PlayerSheetPhase.Closing
                expansion.animateTo(
                    targetValue = 0f,
                    animationSpec = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs),
                )
                sheetPhase = PlayerSheetPhase.Collapsed
            }
            PlayerSheetPhase.Collapsed -> {
                sheetPhase = PlayerSheetPhase.Collapsed
            }
        }
    }

    val progress = playerSheetProgressForPredictiveBack(expansion.value, predictiveBackProgress)
    val showFullPlayer = sheetPhase.keepsOverlayOpen(expanded) || predictiveBackProgress != null

    LaunchedEffect(showFullPlayer) {
        onOverlayFullScreenChange(showFullPlayer)
    }

    Box(
        if (showFullPlayer) modifier.fillMaxSize() else modifier.fillMaxWidth(),
    ) {
        if (!expanded || progress < 0.99f) {
            MiniPlayer(
                style = uiSettings.miniPlayerStyle,
                song = song,
                isPlaying = surfaceState.isPlaying,
                positionMs = progressState.positionMs,
                onPlayPause = actions.togglePlay,
                onPrevious = actions.previous,
                onNext = actions.next,
                onExpand = { onExpandedChange(true) },
                onLongPress = onLocateCurrentSong,
                miniPlayerLyricsEnabled = uiSettings.miniPlayerLyricsEnabled,
                lyricSplitEnabled = uiSettings.lyricSplitEnabled,
                lyricsBilingualDisplayMode = uiSettings.lyricsBilingualDisplayMode,
                swipeEnabled = uiSettings.miniPlayerSwipeEnabled,
                leftSwipeAction = uiSettings.miniPlayerLeftSwipeAction,
                rightSwipeAction = uiSettings.miniPlayerRightSwipeAction,
                modifier = Modifier
                    .align(Alignment.BottomCenter),
            )
        }

        if (showFullPlayer) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.60f * progress)),
            )
        }

        if (showFullPlayer) {
            val contentInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        clip = true
                        translationY = (1f - progress) * size.height
                    }
                    .clickable(
                        indication = null,
                        interactionSource = contentInteraction,
                        onClick = {},
                    ),
            ) {
                NowPlayingContent(
                    library = library,
                    playlistStore = playlistStore,
                    surfaceState = hydratedSurfaceState,
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
                    handleBackToClose = false,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}
