package com.mica.music.ui.screens.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.mica.music.data.AppUiSettings
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.PlaybackQueueState
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.Song
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.system.homeStatusBarTopPadding
import com.mica.music.ui.theme.LocalCoverDisplayMode
import com.mica.music.ui.theme.MicaTheme
import kotlinx.coroutines.delay

private const val LyricsLayoutShiftDelayOnCloseMs = 220
private const val CoverLetterboxFadeMs = 480

data class PlayerPageUiModel(
    val song: Song,
    val queue: List<Song>,
    val currentIndex: Int,
    val isPlaying: Boolean,
    val frame: PlayerPageFrame,
)

@Composable
fun rememberPlayerPageUiModel(
    surfaceState: PlaybackSurfaceState,
    queueState: PlaybackQueueState,
    uiSettings: AppUiSettings,
    lyricsExpanded: Boolean,
    panelHeight: Dp,
    screenHeight: Dp,
    screenWidth: Dp,
    coverAspectRatio: Float,
    coverSwitching: Boolean,
): PlayerPageUiModel? {
    val song = surfaceState.currentSong ?: return null
    val motionEnabled = rememberMicaMotionEnabled()
    val density = LocalDensity.current
    val typography = MicaTheme.typography

    var spectrumDeferred by remember { mutableStateOf(false) }
    val useCoverEdgeProgress = uiSettings.useCoverEdgeProgressNow()
    val immersiveLower = uiSettings.playerImmersiveLower
    val coverFlowMode = uiSettings.playerCoverFlowMode
    val coverFlowModeEnabled = coverFlowMode != PlayerCoverFlowMode.STANDARD

    val lyricsChromeFade by animateFloatAsState(
        targetValue = if (lyricsExpanded) 1f else 0f,
        animationSpec = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationLongMs),
        label = "lyricsChromeFade",
    )
    val lyricsProgress by animateFloatAsState(
        targetValue = if (lyricsExpanded) 1f else 0f,
        animationSpec = if (motionEnabled) {
            tween(
                durationMillis = MicaMotion.DurationLongMs,
                delayMillis = if (lyricsExpanded) 0 else LyricsLayoutShiftDelayOnCloseMs,
                easing = MicaMotion.Easing,
            )
        } else {
            tween(0)
        },
        label = "lyricsProgress",
    )
    val immersiveProgress by animateFloatAsState(
        targetValue = if (immersiveLower) 1f else 0f,
        animationSpec = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationLongMs),
        label = "immersiveProgress",
    )

    val coverFlowAvailable =
        coverFlowModeEnabled &&
            queueState.queue.isNotEmpty() &&
            !lyricsExpanded &&
            !immersiveLower &&
            lyricsProgress < 0.01f
    val coverFlowProgress by animateFloatAsState(
        targetValue = if (coverFlowAvailable) 1f else 0f,
        animationSpec = MicaMotion.tweenFloat(
            motionEnabled,
            if (coverFlowAvailable) MicaMotion.DurationLongMs else MicaMotion.DurationMediumMs,
        ),
        label = "coverFlowProgress",
    )

    val coverDisplayMode = LocalCoverDisplayMode.current
    val fitOriginal =
        !coverFlowModeEnabled && coverDisplayMode == CoverDisplayMode.FIT_ORIGINAL

    val statusBarTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)

    androidx.compose.runtime.LaunchedEffect(song.id) {
        spectrumDeferred = true
        delay(260)
        spectrumDeferred = false
    }

    val frame = PlayerPageLayoutEngine.computeFrame(
        input = PlayerPageLayoutInput(
            panelHeight = panelHeight,
            screenHeight = screenHeight,
            screenWidth = screenWidth,
            statusBarTop = statusBarTop,
            lyricsExpanded = lyricsExpanded,
            lyricsProgress = lyricsProgress,
            lyricsChromeFade = lyricsChromeFade,
            immersiveLower = immersiveLower,
            immersiveProgress = immersiveProgress,
            coverFlowProgress = coverFlowProgress,
            coverFlowModeEnabled = coverFlowModeEnabled,
            useCoverEdgeProgress = useCoverEdgeProgress,
            fitOriginal = fitOriginal,
            coverAspectRatio = coverAspectRatio,
            spectrumSettingEnabled = uiSettings.spectrumEnabled,
            spectrumDeferred = spectrumDeferred,
            coverSwitching = coverSwitching,
        ),
        density = density,
        typography = typography,
    )

    return PlayerPageUiModel(
        song = song,
        queue = queueState.queue,
        currentIndex = queueState.currentIndex,
        isPlaying = surfaceState.isPlaying,
        frame = frame,
    )
}
