package com.mica.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.mica.music.data.AppUiSettings
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerController
import com.mica.music.ui.components.NowPlayingTrackWipe
import com.mica.music.ui.components.PlaybackQueueSheet
import com.mica.music.ui.components.rememberPlaybackSeekState
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.system.homeStatusBarTopPadding
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalCoverDisplayMode
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.NowPlayingBackground
import com.mica.music.ui.theme.rememberPlayerScreenAppearance
import kotlinx.coroutines.delay

private data class ImmersiveTitleSlideSnapshot(
    val titleOffsetFromLowerTop: Dp,
    val titleSlideEnd: Dp,
)

@Composable
fun NowPlayingScreen(
    playerController: PlayerController,
    uiSettings: AppUiSettings,
    onClose: () -> Unit,
    onOpenEqualizer: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val song = playerController.currentSong
    if (song == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    var queueSheetOpen by remember { mutableStateOf(false) }
    var lyricsExpanded by remember { mutableStateOf(false) }

    NowPlayingTrackWipe(
        targetSong = song,
        consumeSkipDirection = { playerController.consumeTrackSkipDirection() },
        modifier = Modifier.fillMaxSize(),
        enabled = uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.STANDARD,
    ) { activeSong ->
        LaunchedEffect(Unit) {
            while (true) {
                playerController.syncPosition()
                delay(50)
            }
        }

        val lowerBackground = uiSettings.playerLowerBackground
        val appearance = rememberPlayerScreenAppearance(activeSong, lowerBackground)
        val coverColor = appearance.coverColor
        val contentColors = appearance.contentColors
        val hifiBadgeColors = appearance.hifiBadgeColors
        val artworkJunction = appearance.artworkJunction
        val useCoverEdgeProgress = uiSettings.useCoverEdgeProgressNow()
        val immersiveLower = uiSettings.playerImmersiveLower
        val seekState = rememberPlaybackSeekState(playerController)
        val motionEnabled = rememberMicaMotionEnabled()
        val spectrumSettingEnabled = uiSettings.spectrumEnabled
        val coverFlowMode = uiSettings.playerCoverFlowMode
        val coverFlowModeEnabled = coverFlowMode != PlayerCoverFlowMode.STANDARD

        val lyricsTransition = rememberLyricsFocusTransition(
            lyricsExpanded = lyricsExpanded,
            useCoverEdgeProgress = useCoverEdgeProgress,
            motionEnabled = motionEnabled,
        )
        val lyricsChromeFade = lyricsTransition.chromeFade
        val lyricsLayoutFocus = lyricsTransition.layoutFocus
        val immersiveProgress by animateFloatAsState(
            targetValue = if (immersiveLower) 1f else 0f,
            animationSpec = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationLongMs),
            label = "immersiveProgress",
        )
        val spectrumEnabled =
            spectrumSettingEnabled && immersiveProgress <= ImmersiveProgressEpsilon
        BackHandler(enabled = lyricsExpanded) {
            lyricsExpanded = false
        }
        BackHandler(enabled = !lyricsExpanded) {
            onClose()
        }

        val lyricsLayoutActive = lyricsTransition.layoutActive
        val coverEdgeOnPlaySurface = lyricsTransition.coverEdgeOnPlaySurface
        val coverFlowAvailable =
            coverFlowModeEnabled &&
                playerController.songQueue.isNotEmpty() &&
                !lyricsExpanded &&
                !immersiveLower &&
                lyricsLayoutFocus < 0.01f
        val coverFlowProgress by animateFloatAsState(
            targetValue = if (coverFlowAvailable) 1f else 0f,
            animationSpec = MicaMotion.tweenFloat(
                motionEnabled,
                if (coverFlowAvailable) MicaMotion.DurationLongMs else MicaMotion.DurationMediumMs,
            ),
            label = "coverFlowProgress",
        )

        if (queueSheetOpen) {
            PlaybackQueueSheet(
                queue = playerController.songQueue,
                currentIndex = playerController.currentIndex,
                isPlaying = playerController.isPlaying,
                onDismiss = { queueSheetOpen = false },
                onPlayAt = { playerController.playSong(it) },
                onMove = { from, to -> playerController.moveInQueue(from, to) },
                onRemove = { index -> playerController.removeFromQueue(index) },
            )
        }

        var coverZoneStop by remember { mutableFloatStateOf(0.4f) }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val screenHeight = maxHeight

            NowPlayingBackground(
                coverColor = coverColor,
                albumArtUri = activeSong.albumArtUri,
                mode = lowerBackground,
                coverZoneStop = coverZoneStop,
                modifier = Modifier.matchParentSize(),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                val statusBarTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)
                val fitOriginal =
                    !coverFlowModeEnabled && LocalCoverDisplayMode.current == CoverDisplayMode.FIT_ORIGINAL
                val letterboxAlpha = rememberFitOriginalLetterboxAlpha(
                    fitOriginal = fitOriginal,
                    lyricsExpanded = lyricsExpanded,
                    lyricsChromeFade = lyricsChromeFade,
                    motionEnabled = motionEnabled,
                )
                NowPlayingCoverSection(
                    activeSong = activeSong,
                    coverColor = coverColor,
                    contentColors = contentColors,
                    lowerBackground = lowerBackground,
                    artworkJunction = artworkJunction,
                    statusBarTop = statusBarTop,
                    screenHeight = screenHeight,
                    lyricsExpanded = lyricsExpanded,
                    lyricsLayoutFocus = lyricsLayoutFocus,
                    lyricsChromeFade = lyricsChromeFade,
                    useCoverEdgeProgress = useCoverEdgeProgress,
                    seekState = seekState,
                    spectrumEnabled = spectrumEnabled,
                    spectrumPlaying = playerController.isPlaying,
                    coverFlowModeEnabled = coverFlowModeEnabled,
                    coverFlowMode = coverFlowMode,
                    queue = playerController.songQueue,
                    currentIndex = playerController.currentIndex,
                    coverFlowProgress = coverFlowProgress,
                    letterboxAlpha = letterboxAlpha,
                    onCoverZoneStopChanged = { coverZoneStop = it },
                    onCloseLyrics = { lyricsExpanded = false },
                    onToggleCoverFlow = null,
                    onPlayQueueIndex = {
                        playerController.playSong(it)
                    },
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    val panelHeight = maxHeight
                    val immersiveInTransition =
                        immersiveLower || immersiveProgress > ImmersiveProgressEpsilon
                    val lowerLayoutFrozen = immersiveInTransition || lyricsLayoutActive
                    val usePlaySurfaceLayout = useCoverEdgeProgress && !lyricsExpanded
                    val metaLayoutModeLive = if (usePlaySurfaceLayout) {
                        PlayerLowerLayoutMode.COVER_EDGE_PROGRESS
                    } else {
                        PlayerLowerLayoutMode.STANDARD
                    }
                    val metaLayoutMode = if (!lowerLayoutFrozen) {
                        metaLayoutModeLive
                    } else {
                        remember(panelHeight, useCoverEdgeProgress, lowerLayoutFrozen, usePlaySurfaceLayout) {
                            metaLayoutModeLive
                        }
                    }
                    val lowerLayout = rememberPlayerLowerLayout(
                        panelHeight = panelHeight,
                        layoutMode = metaLayoutMode,
                        immersiveProgress = immersiveProgress,
                        useCoverEdgeProgressSetting = coverEdgeOnPlaySurface,
                        lyricsFocus = lyricsLayoutFocus,
                        lyricsCoverMorphEndFocus = LyricsCoverMorphEndFocus,
                        freezeSpacing = lowerLayoutFrozen,
                    )
                    val density = LocalDensity.current
                    val typography = MicaTheme.typography
                    val spacing = lowerLayout.spacing
                    val layoutChromeHeight = lowerLayout.chromeHeight
                    val edgeAnchor = rememberPlayerLowerPanelEdgeAnchorState(
                        useCoverEdgeProgress = useCoverEdgeProgress,
                        activeSongId = activeSong.id,
                        lyricsExpanded = lyricsExpanded,
                        lyricsLayoutFocus = lyricsLayoutFocus,
                        lyricsChromeFade = lyricsChromeFade,
                        layoutChromeHeight = layoutChromeHeight,
                        spacing = spacing,
                    )

                    val infoLine = with(density) { typography.monoMd.lineHeight.toDp() }
                    val titleLine = with(density) { typography.titleLg.lineHeight.toDp() }
                    val subtitleLine = with(density) { typography.bodyMd.lineHeight.toDp() }
                    val titleBlockHeight = titleLine + HifiSpacing.sm + subtitleLine * 2
                    val immersiveLayoutSnapshot = remember(
                        immersiveInTransition,
                        activeSong.id,
                        panelHeight,
                        spacing.afterCover,
                        spacing.afterInfo,
                        lowerLayout.chromeHeightAtFullImmersive,
                        titleBlockHeight,
                    ) {
                        if (!immersiveInTransition) {
                            null
                        } else {
                            val titleOffset = spacing.afterCover + infoLine + spacing.afterInfo
                            val metaEnd = panelHeight - lowerLayout.chromeHeightAtFullImmersive
                            ImmersiveTitleSlideSnapshot(
                                titleOffsetFromLowerTop = titleOffset,
                                titleSlideEnd = maxOf(
                                    0.dp,
                                    metaEnd / 2 - titleOffset - titleBlockHeight / 2,
                                ),
                            )
                        }
                    }
                    val titleSlideDown = lerpDp(
                        0.dp,
                        immersiveLayoutSnapshot?.titleSlideEnd ?: 0.dp,
                        immersiveProgress,
                    )

                    PlayerLowerPanelSection(
                        playerController = playerController,
                        activeSong = activeSong,
                        lyrics = activeSong.lyrics,
                        colors = contentColors,
                        hifiBadgeColors = hifiBadgeColors,
                        lowerBackground = lowerBackground,
                        spacing = spacing,
                        seekState = seekState,
                        edgeAnchor = edgeAnchor,
                        useCoverEdgeProgress = useCoverEdgeProgress,
                        coverEdgeOnPlaySurface = coverEdgeOnPlaySurface,
                        spectrumEnabled = spectrumEnabled,
                        lyricsExpanded = lyricsExpanded,
                        lyricsChromeFade = lyricsChromeFade,
                        lyricsLayoutFocus = lyricsLayoutFocus,
                        immersiveLower = immersiveLower,
                        immersiveProgress = immersiveProgress,
                        titleSlideDown = titleSlideDown,
                        lyricLineSlots = spacing.lyricLineSlots,
                        onToggleImmersive = { uiSettings.togglePlayerImmersiveLower() },
                        onOpenLyrics = {
                            edgeAnchor.onOpenLyrics()
                            lyricsExpanded = true
                        },
                        onOpenQueue = { queueSheetOpen = true },
                    )
                }
            }
        }
    }
}
