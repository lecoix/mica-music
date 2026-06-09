package com.mica.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.mica.music.data.AppUiSettings
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.PlaybackProgressState
import com.mica.music.data.PlaybackQueueState
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerController
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.Song
import com.mica.music.data.TrackSkipDirection
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

@Stable
data class NowPlayingUiState(
    val song: Song,
    val displayedCoverSong: Song,
    val displayedCoverIndex: Int,
    val coverHoldoverAlbumArtUri: String?,
    val queue: List<Song>,
    val currentIndex: Int,
    val lowerBackground: PlayerLowerBackgroundMode,
    val coverFlowMode: PlayerCoverFlowMode,
    val coverEdgeProgress: Boolean,
    val immersiveLower: Boolean,
    val spectrumSettingEnabled: Boolean,
) {
    val coverFlowModeEnabled: Boolean
        get() = coverFlowMode != PlayerCoverFlowMode.STANDARD

    val coverSwitching: Boolean
        get() = displayedCoverSong.id != song.id
}

@Stable
internal class StableCoverState(initialSong: Song, initialIndex: Int) {
    var displayedSong by mutableStateOf(initialSong)
        private set

    var displayedIndex by mutableIntStateOf(initialIndex)
        private set

    var pendingSong by mutableStateOf<Song?>(null)
        private set

    var pendingIndex by mutableIntStateOf(initialIndex)
        private set

    var coverHoldoverAlbumArtUri by mutableStateOf(initialSong.albumArtUri)
        private set

    fun retarget(song: Song, index: Int, waitForArtwork: Boolean) {
        if (song.id == displayedSong.id) {
            pendingSong = null
            displayedIndex = index
            pendingIndex = index
            return
        }
        if (!waitForArtwork) {
            coverHoldoverAlbumArtUri = displayedSong.albumArtUri
            displayedSong = song
            displayedIndex = index
            pendingSong = null
            pendingIndex = index
            return
        }
        pendingIndex = index
        if (song.albumArtUri.isNullOrBlank()) {
            coverHoldoverAlbumArtUri = displayedSong.albumArtUri
            displayedSong = song
            displayedIndex = index
            pendingSong = null
        } else {
            pendingSong = song
        }
    }

    fun markTargetReady(song: Song, index: Int) {
        val pending = pendingSong ?: return
        if (pending.id != song.id) return
        coverHoldoverAlbumArtUri = displayedSong.albumArtUri
        displayedSong = pending
        displayedIndex = pendingIndex
        pendingSong = null
        pendingIndex = displayedIndex
    }

    fun markDisplayedCoverDrawn(song: Song) {
        if (displayedSong.id == song.id) {
            coverHoldoverAlbumArtUri = song.albumArtUri
        }
    }
}

data class NowPlayingActions(
    val syncPosition: () -> Unit,
    val consumeSkipDirection: () -> TrackSkipDirection?,
    val setSeekUiActive: (Boolean) -> Unit,
    val seekToMs: (Int) -> Unit,
    val playQueueIndex: (Int) -> Unit,
    val moveQueueItem: (Int, Int) -> Unit,
    val removeQueueItem: (Int) -> Unit,
    val togglePlay: () -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
    val cyclePlaybackQueueMode: () -> Unit,
    val toggleImmersiveLower: () -> Unit,
)

@Composable
fun rememberNowPlayingActions(
    playerController: PlayerController,
    uiSettings: AppUiSettings,
): NowPlayingActions =
    remember(playerController, uiSettings) {
        NowPlayingActions(
            syncPosition = playerController::syncPosition,
            consumeSkipDirection = playerController::consumeTrackSkipDirection,
            setSeekUiActive = playerController::setAlacSeekUiActive,
            seekToMs = playerController::seekToMs,
            playQueueIndex = playerController::playSong,
            moveQueueItem = playerController::moveInQueue,
            removeQueueItem = playerController::removeFromQueue,
            togglePlay = playerController::togglePlay,
            previous = playerController::previous,
            next = playerController::next,
            cyclePlaybackQueueMode = playerController::cyclePlaybackQueueMode,
            toggleImmersiveLower = uiSettings::togglePlayerImmersiveLower,
        )
    }

@Composable
fun NowPlayingScreen(
    playerController: PlayerController,
    uiSettings: AppUiSettings,
    onClose: () -> Unit,
    onOpenEqualizer: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    coverContentAlpha: Float = 1f,
    onCoverBoundsChanged: (Rect?) -> Unit = {},
) {
    NowPlayingContent(
        surfaceState = playerController.playbackSurfaceState,
        progressState = playerController.playbackProgressState,
        queueState = playerController.playbackQueueState,
        actions = rememberNowPlayingActions(playerController, uiSettings),
        uiSettings = uiSettings,
        onClose = onClose,
        onOpenEqualizer = onOpenEqualizer,
        contentPadding = contentPadding,
        coverContentAlpha = coverContentAlpha,
        onCoverBoundsChanged = onCoverBoundsChanged,
    )
}

@Composable
fun NowPlayingContent(
    surfaceState: PlaybackSurfaceState,
    progressState: PlaybackProgressState,
    queueState: PlaybackQueueState,
    actions: NowPlayingActions,
    uiSettings: AppUiSettings,
    onClose: () -> Unit,
    onOpenEqualizer: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    coverContentAlpha: Float = 1f,
    onCoverBoundsChanged: (Rect?) -> Unit = {},
) {
    val song = surfaceState.currentSong
    if (song == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    var queueSheetOpen by remember { mutableStateOf(false) }
    var lyricsExpanded by remember { mutableStateOf(false) }
    val stableCoverState = remember {
        StableCoverState(song, queueState.currentIndex)
    }

    LaunchedEffect(song.id) {
        lyricsExpanded = false
    }

    LaunchedEffect(song.id, queueState.currentIndex, uiSettings.playerCoverFlowMode) {
        stableCoverState.retarget(
            song = song,
            index = queueState.currentIndex,
            waitForArtwork = uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.STANDARD,
        )
    }

    LaunchedEffect(actions, surfaceState.isPlaying, surfaceState.alacStreamActive) {
        actions.syncPosition()
        if (!surfaceState.isPlaying || surfaceState.alacStreamActive) return@LaunchedEffect
        while (true) {
            delay(500)
            actions.syncPosition()
        }
    }

    NowPlayingTrackWipe(
        targetSong = song,
        consumeSkipDirection = actions.consumeSkipDirection,
        modifier = Modifier.fillMaxSize(),
        enabled = false,
    ) { activeSong ->
        val lowerBackground = uiSettings.playerLowerBackground
        val appearance = rememberPlayerScreenAppearance(activeSong, lowerBackground)
        val coverColor = appearance.coverColor
        val contentColors = appearance.contentColors
        val hifiBadgeColors = appearance.hifiBadgeColors
        val artworkJunction = appearance.artworkJunction
        val useCoverEdgeProgress = uiSettings.useCoverEdgeProgressNow()
        val immersiveLower = uiSettings.playerImmersiveLower
        val seekState = rememberPlaybackSeekState(
            progressState = progressState,
            onSeekUiActiveChanged = actions.setSeekUiActive,
            onSeekToMs = actions.seekToMs,
        )
        val motionEnabled = rememberMicaMotionEnabled()
        val spectrumSettingEnabled = uiSettings.spectrumEnabled
        val coverFlowMode = uiSettings.playerCoverFlowMode
        val uiState = NowPlayingUiState(
            song = activeSong,
            displayedCoverSong = stableCoverState.displayedSong,
            displayedCoverIndex = stableCoverState.displayedIndex,
            coverHoldoverAlbumArtUri = stableCoverState.coverHoldoverAlbumArtUri,
            queue = queueState.queue,
            currentIndex = queueState.currentIndex,
            lowerBackground = lowerBackground,
            coverFlowMode = coverFlowMode,
            coverEdgeProgress = useCoverEdgeProgress,
            immersiveLower = immersiveLower,
            spectrumSettingEnabled = spectrumSettingEnabled,
        )
        var spectrumDeferred by remember { mutableStateOf(false) }
        LaunchedEffect(activeSong.id) {
            spectrumDeferred = true
            delay(260)
            spectrumDeferred = false
        }

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
            uiState.spectrumSettingEnabled &&
                !spectrumDeferred &&
                !uiState.coverSwitching &&
                immersiveProgress <= ImmersiveProgressEpsilon
        BackHandler(enabled = lyricsExpanded) {
            lyricsExpanded = false
        }
        BackHandler(enabled = !lyricsExpanded) {
            onClose()
        }

        val lyricsLayoutActive = lyricsTransition.layoutActive
        val coverEdgeOnPlaySurface = lyricsTransition.coverEdgeOnPlaySurface
        val coverFlowAvailable =
            uiState.coverFlowModeEnabled &&
                uiState.queue.isNotEmpty() &&
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
                queue = queueState.queue,
                currentIndex = queueState.currentIndex,
                isPlaying = surfaceState.isPlaying,
                onDismiss = { queueSheetOpen = false },
                onPlayAt = actions.playQueueIndex,
                onMove = actions.moveQueueItem,
                onRemove = actions.removeQueueItem,
            )
        }

        var coverZoneStop by remember { mutableFloatStateOf(0.4f) }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val screenHeight = maxHeight

            NowPlayingBackground(
                coverColor = coverColor,
                albumArtUri = activeSong.albumArtUri,
                mode = uiState.lowerBackground,
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
                    !uiState.coverFlowModeEnabled && LocalCoverDisplayMode.current == CoverDisplayMode.FIT_ORIGINAL
                val letterboxAlpha = rememberFitOriginalLetterboxAlpha(
                    fitOriginal = fitOriginal,
                    lyricsExpanded = lyricsExpanded,
                    lyricsChromeFade = lyricsChromeFade,
                    motionEnabled = motionEnabled,
                )
                NowPlayingCoverSection(
                    activeSong = activeSong,
                    displayedCoverSong = uiState.displayedCoverSong,
                    coverHoldoverAlbumArtUri = uiState.coverHoldoverAlbumArtUri,
                    coverColor = coverColor,
                    contentColors = contentColors,
                    lowerBackground = uiState.lowerBackground,
                    artworkJunction = artworkJunction,
                    statusBarTop = statusBarTop,
                    screenHeight = screenHeight,
                    lyricsExpanded = lyricsExpanded,
                    lyricsLayoutFocus = lyricsLayoutFocus,
                    lyricsChromeFade = lyricsChromeFade,
                    useCoverEdgeProgress = useCoverEdgeProgress,
                    seekState = seekState,
                    spectrumEnabled = spectrumEnabled,
                    spectrumPlaying = surfaceState.isPlaying,
                    coverFlowModeEnabled = uiState.coverFlowModeEnabled,
                    coverFlowMode = uiState.coverFlowMode,
                    queue = uiState.queue,
                    currentIndex = uiState.currentIndex,
                    displayedCoverIndex = uiState.displayedCoverIndex,
                    coverSwitching = uiState.coverSwitching,
                    coverFlowProgress = coverFlowProgress,
                    letterboxAlpha = letterboxAlpha,
                    onCoverZoneStopChanged = { coverZoneStop = it },
                    onCloseLyrics = { lyricsExpanded = false },
                    onToggleCoverFlow = null,
                    onPlayQueueIndex = actions.playQueueIndex,
                    onTargetCoverReady = {
                        stableCoverState.markTargetReady(activeSong, queueState.currentIndex)
                    },
                    onDisplayedCoverDrawn = { drawnSong ->
                        stableCoverState.markDisplayedCoverDrawn(drawnSong)
                    },
                    coverContentAlpha = coverContentAlpha,
                    onCoverBoundsChanged = { bounds ->
                        if (activeSong.id == surfaceState.currentSong?.id) {
                            onCoverBoundsChanged(bounds)
                        }
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
                        surfaceState = surfaceState,
                        progressState = progressState,
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
                        onCyclePlaybackQueueMode = actions.cyclePlaybackQueueMode,
                        onPrevious = actions.previous,
                        onTogglePlay = actions.togglePlay,
                        onNext = actions.next,
                        onSeekToMs = actions.seekToMs,
                        onToggleImmersive = actions.toggleImmersiveLower,
                        onOpenEqualizer = onOpenEqualizer,
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
