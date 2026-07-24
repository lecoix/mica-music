package com.mica.music.ui.screens.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.AppUiSettings
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.PlaybackQueueState
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.Song
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.system.homeStatusBarTopPadding
import com.mica.music.ui.theme.HifiTypography
import com.mica.music.ui.theme.LocalCoverDisplayMode
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.util.TrackSwitchPerformance
import kotlinx.coroutines.delay

private const val LyricsLayoutShiftDelayOnCloseMs = 220
private const val CoverLetterboxFadeMs = 480

internal class PlayerPageUiModel(
    val song: Song,
    val queue: List<Song>,
    val currentIndex: Int,
    val isPlaying: Boolean,
    private val layoutInput: PlayerPageLayoutInput,
    private val density: Density,
    private val typography: HifiTypography,
) {
    fun frameFor(panelHeight: Dp): PlayerPageFrame =
        PlayerPageLayoutEngine.computeFrame(
            input = layoutInput.copy(panelHeight = panelHeight),
            density = density,
            typography = typography,
        )

    fun lyricsFrameFor(panelHeight: Dp): PlayerPageFrame =
        PlayerPageLayoutEngine.computeFrame(
            input = layoutInput.copy(
                panelHeight = panelHeight,
                lyricsExpanded = true,
                lyricsProgress = 1f,
                lyricsChromeFade = 1f,
                immersiveLower = false,
                immersiveProgress = 0f,
            ),
            density = density,
            typography = typography,
        )
}

@Composable
internal fun rememberPlayerPageUiModel(
    surfaceState: PlaybackSurfaceState,
    queueState: PlaybackQueueState,
    uiSettings: AppUiSettings,
    lyricsExpanded: Boolean,
    screenHeight: Dp,
    screenWidth: Dp,
    coverAspectRatio: Float,
    coverSwitching: Boolean,
    coverFlowMode: PlayerCoverFlowMode = uiSettings.playerCoverFlowMode,
    immersiveAllowed: Boolean = true,
): PlayerPageUiModel? {
    val song = surfaceState.currentSong ?: return null
    val motionEnabled = rememberMicaMotionEnabled()
    val density = LocalDensity.current
    val typography = MicaTheme.typography

    var spectrumDeferred by remember { mutableStateOf(false) }
    val immersiveLower = immersiveAllowed &&
        uiSettings.playerImmersiveLower &&
        coverFlowMode.supportsImmersiveLower
    val coverFlowModeEnabled = ParticleCoverThemePolicy.coverFlowStageEnabled(coverFlowMode)
    val photoStackMode = coverFlowMode.usesPhotoStack
    val useCoverEdgeProgress = resolveUseCoverEdgeProgress(
        mode = coverFlowMode,
        coverFlowModeEnabled = coverFlowModeEnabled,
        coverEdgeProgressSetting = uiSettings.coverEdgeProgress,
        standardCoverEdgeProgress = uiSettings.useCoverEdgeProgressNow(),
    )

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

    val coverFlowTargetAvailable =
        coverFlowModeEnabled &&
            queueState.queue.isNotEmpty() &&
            !lyricsExpanded &&
            !immersiveLower
    val coverFlowProgress by animateFloatAsState(
        targetValue = if (coverFlowTargetAvailable) 1f else 0f,
        animationSpec = MicaMotion.tweenFloat(
            motionEnabled,
            if (coverFlowTargetAvailable) MicaMotion.DurationLongMs else MicaMotion.DurationMediumMs,
        ),
        label = "coverFlowProgress",
        finishedListener = { finalValue ->
            TrackSwitchPerformance.mark(
                "ui-cover-flow-progress-end",
                "value=$finalValue available=$coverFlowTargetAvailable",
            )
        },
    )

    LaunchedEffect(song.id, coverFlowTargetAvailable) {
        TrackSwitchPerformance.mark(
            "ui-cover-flow-progress-start",
            "available=$coverFlowTargetAvailable target=${if (coverFlowTargetAvailable) 1f else 0f}",
        )
    }

    val coverDisplayMode = LocalCoverDisplayMode.current
    val fitOriginal =
        !ParticleCoverThemePolicy.forcesSquareCrop(coverFlowMode) &&
            coverDisplayMode == CoverDisplayMode.FIT_ORIGINAL

    val statusBarTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)

    androidx.compose.runtime.LaunchedEffect(song.id) {
        spectrumDeferred = true
        delay(260)
        spectrumDeferred = false
    }

    val layoutInput = PlayerPageLayoutInput(
        panelHeight = 0.dp,
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
        particleCoverMode = ParticleCoverThemePolicy.particleCoverMode(coverFlowMode),
        photoStackMode = photoStackMode,
        fitOriginal = fitOriginal,
        coverAspectRatio = coverAspectRatio,
        spectrumSettingEnabled = uiSettings.spectrumEnabled,
        spectrumDeferred = spectrumDeferred,
        coverSwitching = coverSwitching,
    )

    return PlayerPageUiModel(
        song = song,
        queue = queueState.queue,
        currentIndex = queueState.currentIndex,
        isPlaying = surfaceState.isPlaying,
        layoutInput = layoutInput,
        density = density,
        typography = typography,
    )
}

internal fun resolveUseCoverEdgeProgress(
    mode: PlayerCoverFlowMode,
    coverFlowModeEnabled: Boolean,
    coverEdgeProgressSetting: Boolean,
    standardCoverEdgeProgress: Boolean,
): Boolean = when {
    mode == PlayerCoverFlowMode.CUSTOM_STANDARD -> false
    mode.usesPhotoStack -> true
    ParticleCoverThemePolicy.isParticleCover(mode) || coverFlowModeEnabled -> coverEdgeProgressSetting
    else -> standardCoverEdgeProgress
}
