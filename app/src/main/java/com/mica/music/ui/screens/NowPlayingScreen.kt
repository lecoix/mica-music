package com.mica.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mica.music.data.AppUiSettings
import com.mica.music.data.DsdSupport
import com.mica.music.data.MusicLibrary
import com.mica.music.data.LyricsPageTheme
import com.mica.music.data.LyricsSession
import com.mica.music.data.PlaybackProgressState
import com.mica.music.data.PlaybackQueueState
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.ui.screens.player.view.CoverFlowCarouselNavigationBridge
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerLowerComponent
import com.mica.music.data.PlaylistStore
import com.mica.music.data.SleepTimerController
import com.mica.music.data.Song
import com.mica.music.data.SongTitleDisplay
import com.mica.music.data.TrackSkipDirection
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.ui.components.AddToPlaylistSheet
import com.mica.music.ui.components.MicaConfirmDialog
import com.mica.music.ui.components.PlaybackQueueSheet
import com.mica.music.ui.components.PlaybackTuningSheet
import com.mica.music.ui.components.PlayerCoverMaxScreenFraction
import com.mica.music.ui.components.SleepTimerSheet
import com.mica.music.ui.components.DirectionalTrackWipe
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.components.SongActionMenuSheet
import com.mica.music.ui.components.SongMenuAction
import com.mica.music.ui.components.cachedCoverAspectRatio
import com.mica.music.ui.components.formatPlaybackTuningMenuLabel
import com.mica.music.ui.components.rememberPlaybackSeekState
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.screens.player.ParticleCoverPlayerLayer
import com.mica.music.ui.screens.player.landscapeFallbackCoverMode
import com.mica.music.ui.screens.player.landscapeChromeHeight
import com.mica.music.ui.screens.player.landscapePlayerLayoutPlan
import com.mica.music.ui.screens.player.rememberPlayerPageUiModel
import com.mica.music.ui.screens.player.view.PhotoStackCarouselNavigationBridge
import com.mica.music.ui.system.StatusBarEffect
import com.mica.music.ui.system.homeStatusBarTopPadding
import com.mica.music.ui.theme.NowPlayingBackground
import com.mica.music.ui.theme.LocalCoverDisplayMode
import com.mica.music.ui.theme.rememberPlaybackContentColors
import com.mica.music.ui.theme.rememberLyricsContentColors
import com.mica.music.ui.theme.rememberPlayerScreenAppearance
import com.mica.music.ui.theme.relativeLuminance
import com.mica.music.util.TrackSwitchPerformance
import com.mica.music.util.deleteSongEverywhere
import com.mica.music.util.logBackFlow
import com.mica.music.util.openSongInTagEditor
import com.mica.music.util.shareSong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class NowPlayingActions(
    val syncPosition: () -> Unit,
    val setSeekUiActive: (Boolean) -> Unit,
    val seekToMs: (Int) -> Unit,
    val playQueueIndex: (Int) -> Unit,
    val moveQueueItem: (Int, Int) -> Unit,
    val removeQueueItem: (Int) -> Unit,
    val togglePlay: () -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
    val coverFlowPreviousTarget: () -> Int?,
    val coverFlowNextTarget: () -> Int?,
    val cyclePlaybackQueueMode: () -> Unit,
    val toggleImmersiveLower: () -> Unit,
    val toggleLyricsPageImmersive: () -> Unit,
    val insertPlayNext: (Song) -> Unit,
    val setQueue: (List<Song>) -> Unit,
    val setPlaybackSpeed: (Float) -> Unit,
    val setPlaybackPitchSemitones: (Float) -> Unit,
    val resetPlaybackTuning: () -> Unit,
    val peekTrackSkipDirection: () -> TrackSkipDirection?,
    val consumeTrackSkipDirection: () -> TrackSkipDirection?,
)

internal suspend fun pollNowPlayingProgress(
    isPlaying: Boolean,
    intervalMs: Long = 500L,
    syncPosition: () -> Unit,
) {
    syncPosition()
    if (!isPlaying) return
    while (true) {
        delay(intervalMs.coerceAtLeast(50L))
        syncPosition()
    }
}

internal fun nowPlayingProgressPollIntervalMs(hasWordSyncedLyrics: Boolean): Long =
    if (hasWordSyncedLyrics) 100L else 500L

@Composable
fun NowPlayingScreen(
    library: MusicLibrary,
    playlistStore: PlaylistStore,
    surfaceState: PlaybackSurfaceState,
    progressState: PlaybackProgressState,
    queueState: PlaybackQueueState,
    sleepTimer: SleepTimerController,
    actions: NowPlayingActions,
    uiSettings: AppUiSettings,
    onClose: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSongDetail: (String) -> Unit = {},
    onBrowseArtist: (String) -> Unit = {},
    onBrowseAlbum: (String) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    coverContentAlpha: Float = 1f,
    onCoverBoundsChanged: (Rect?) -> Unit = {},
    handleBackToClose: Boolean = true,
) {
    NowPlayingContent(
        library = library,
        playlistStore = playlistStore,
        surfaceState = surfaceState,
        progressState = progressState,
        queueState = queueState,
        sleepTimer = sleepTimer,
        actions = actions,
        uiSettings = uiSettings,
        onClose = onClose,
        onOpenEqualizer = onOpenEqualizer,
        onOpenSongDetail = onOpenSongDetail,
        onBrowseArtist = onBrowseArtist,
        onBrowseAlbum = onBrowseAlbum,
        contentPadding = contentPadding,
        coverContentAlpha = coverContentAlpha,
        onCoverBoundsChanged = onCoverBoundsChanged,
        handleBackToClose = handleBackToClose,
    )
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun NowPlayingContent(
    library: MusicLibrary,
    playlistStore: PlaylistStore,
    surfaceState: PlaybackSurfaceState,
    progressState: PlaybackProgressState,
    queueState: PlaybackQueueState,
    sleepTimer: SleepTimerController,
    actions: NowPlayingActions,
    uiSettings: AppUiSettings,
    onClose: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSongDetail: (String) -> Unit = {},
    onBrowseArtist: (String) -> Unit = {},
    onBrowseAlbum: (String) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    coverContentAlpha: Float = 1f,
    onCoverBoundsChanged: (Rect?) -> Unit = {},
    handleBackToClose: Boolean = true,
) {
    val song = surfaceState.currentSong
    if (song == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val context = LocalContext.current
    val view = LocalView.current
    val keepScreenOn = uiSettings.keepScreenOnWhenPlaying && surfaceState.isPlaying
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var actionMenuSong by remember { mutableStateOf<Song?>(null) }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    var pendingDeleteSong by remember { mutableStateOf<Song?>(null) }
    var queueSheetOpen by rememberSaveable { mutableStateOf(false) }
    var sleepTimerSheetOpen by remember { mutableStateOf(false) }
    var playbackTuningSheetOpen by remember { mutableStateOf(false) }
    var lyricsExpanded by rememberSaveable { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val isLandscapeWindow = configuration.screenWidthDp > configuration.screenHeightDp
    val queueListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState(
            firstVisibleItemIndex = (queueState.currentIndex - 2).coerceAtLeast(0),
            firstVisibleItemScrollOffset = 0,
        )
    }
    val playerOverlayOpen = queueSheetOpen ||
        actionMenuSong != null ||
        addToPlaylistSong != null ||
        sleepTimerSheetOpen ||
        playbackTuningSheetOpen
    var previousLandscapeWindow by remember { mutableStateOf(isLandscapeWindow) }

    LaunchedEffect(isLandscapeWindow) {
        if (previousLandscapeWindow != isLandscapeWindow) {
            actionMenuSong = null
            addToPlaylistSong = null
            pendingDeleteSong = null
            sleepTimerSheetOpen = false
            playbackTuningSheetOpen = false
        }
        previousLandscapeWindow = isLandscapeWindow
    }

    LaunchedEffect(
        song.id,
        handleBackToClose,
        lyricsExpanded,
        queueSheetOpen,
        sleepTimerSheetOpen,
        playbackTuningSheetOpen,
        actionMenuSong,
        addToPlaylistSong,
        pendingDeleteSong,
    ) {
        logBackFlow(
            "page now-playing song=${song.id} handleBackToClose=$handleBackToClose " +
                "lyricsExpanded=$lyricsExpanded queueSheet=$queueSheetOpen " +
                "sleepTimerSheet=$sleepTimerSheetOpen playbackTuningSheet=$playbackTuningSheetOpen " +
                "actionMenu=${actionMenuSong?.id ?: "none"} " +
                "addToPlaylist=${addToPlaylistSong?.id ?: "none"} delete=${pendingDeleteSong?.id ?: "none"}",
        )
    }

    val sleepTimerActive = sleepTimer.isActive
    val sleepTimerMenuLabel = if (sleepTimerActive) {
        sleepTimer.displayTick
        sleepTimer.menuLabel()
    } else {
        "睡眠定时"
    }
    val sleepTimerRemainingLabel = if (sleepTimerActive) {
        sleepTimer.displayTick
        sleepTimer.formatRemaining()
    } else {
        null
    }
    val playbackTuningAvailable = !DsdSupport.isDsdSong(song)

    LaunchedEffect(playbackTuningAvailable) {
        if (!playbackTuningAvailable) {
            playbackTuningSheetOpen = false
        }
    }

    DisposableEffect(sleepTimer) {
        sleepTimer.onExpired = {
            scope.launch {
                snackbarHostState.showSnackbar("睡眠定时已结束，播放已暂停")
            }
        }
        onDispose { sleepTimer.onExpired = null }
    }

    fun openSongActionMenu(target: Song) {
        actionMenuSong = target
    }

    fun handleSongMenuAction(action: SongMenuAction, target: Song) {
        when (action) {
            SongMenuAction.AddToPlaylist -> {
                actionMenuSong = null
                addToPlaylistSong = target
            }
            SongMenuAction.PlayNext -> {
                library.songById(target.id)?.let { actions.insertPlayNext(it) }
                actionMenuSong = null
            }
            SongMenuAction.Share -> {
                if (!shareSong(context, target)) {
                    scope.launch { snackbarHostState.showSnackbar("无法分享此歌曲") }
                }
                actionMenuSong = null
            }
            SongMenuAction.EditTags -> {
                if (!openSongInTagEditor(context, target)) {
                    scope.launch { snackbarHostState.showSnackbar("未找到可用的标签编辑应用") }
                }
                actionMenuSong = null
            }
            SongMenuAction.SongInfo -> {
                actionMenuSong = null
                onOpenSongDetail(target.id)
            }
            SongMenuAction.RemoveFromPlaylist -> actionMenuSong = null
            SongMenuAction.Delete -> {
                actionMenuSong = null
                pendingDeleteSong = target
            }
        }
    }

    fun performDeleteSong(target: Song) {
        scope.launch {
            val result = deleteSongEverywhere(
                context = context,
                song = target,
                currentQueue = queueState.queue,
                removeFromLibrary = library::removeSongFromLibrary,
                removeFromAllPlaylists = playlistStore::removeSongFromAllPlaylists,
                setQueue = { actions.setQueue(it) },
            )
            snackbarHostState.showSnackbar(result.message)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val hasWordSyncedLyrics = remember(song.lyricsDocument) {
        song.lyricsDocument.lines.any { it.tokens.isNotEmpty() }
    }
    LaunchedEffect(actions, surfaceState.isPlaying, lifecycleOwner, hasWordSyncedLyrics) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            pollNowPlayingProgress(
                isPlaying = surfaceState.isPlaying,
                syncPosition = actions.syncPosition,
                intervalMs = nowPlayingProgressPollIntervalMs(hasWordSyncedLyrics),
            )
        }
    }

    val seekState = rememberPlaybackSeekState(
        progressState = progressState,
        onSeekUiActiveChanged = actions.setSeekUiActive,
        onSeekToMs = actions.seekToMs,
    )

    val lowerBackground = uiSettings.playerLowerBackground
    val pendingTrackSkipDirection = remember(song.id) { actions.peekTrackSkipDirection() }
    val portraitTrackWipeDirection = pendingTrackSkipDirection.takeIf {
        uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.STANDARD ||
            uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.CUSTOM_STANDARD
    }
    LaunchedEffect(song.id) {
        actions.consumeTrackSkipDirection()
    }
    val immersiveLower = uiSettings.playerImmersiveLower &&
        uiSettings.playerCoverFlowMode.supportsImmersiveLower
    val preloadBlurredBackground = lowerBackground.usesBlurredArtwork

    LaunchedEffect(song.id, song.albumArtUri, preloadBlurredBackground) {
        TrackSwitchPerformance.mark(
            "compose-song",
            "song=${song.id} background=$preloadBlurredBackground",
        )
        // 播放页封面由 NowPlayingCoverSection 在布局尺寸确定后按目标尺寸预载。
        if (preloadBlurredBackground) {
            MicaImageLoaders.preloadBackground(context, song.albumArtUri)
        }
        TrackSwitchPerformance.mark("compose-preload-requested", "song=${song.id}")
    }

    var coverAspectRatio by remember(song.albumArtUri) {
        mutableFloatStateOf(cachedCoverAspectRatio(song.albumArtUri) ?: 1f)
    }
    var coverMotionActive by remember { mutableStateOf(false) }
    val coverFlowNavigation = remember { CoverFlowCarouselNavigationBridge() }
    val photoStackNavigation = remember { PhotoStackCarouselNavigationBridge() }

    BackHandler(enabled = lyricsExpanded) {
        logBackFlow("back-consume source=now-playing-lyrics song=${song.id}")
        lyricsExpanded = false
    }
    BackHandler(enabled = handleBackToClose && !lyricsExpanded) {
        logBackFlow("back-consume source=now-playing-close song=${song.id}")
        onClose()
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (playerOverlayOpen) Modifier.clearAndSetSemantics { } else Modifier,
                ),
        ) {
            val fullHeight = maxHeight
            val fullWidth = maxWidth
            val bottomInset = contentPadding.calculateBottomPadding()
            val screenHeight = fullHeight - bottomInset
            val screenWidth = fullWidth
            val density = LocalDensity.current
            val landscapePlan = landscapePlayerLayoutPlan(fullWidth.value, screenHeight.value)
            val landscapeMode = landscapePlan != null
            val effectiveCoverFlowMode = if (landscapeMode) {
                landscapeFallbackCoverMode(uiSettings.playerCoverFlowMode)
            } else {
                uiSettings.playerCoverFlowMode
            }
            // Landscape STANDARD: keep the pre–full-page-wipe feel (fade), not directional clip.
            val effectiveTrackWipeDirection = when {
                landscapeMode -> null
                effectiveCoverFlowMode == PlayerCoverFlowMode.STANDARD ||
                    effectiveCoverFlowMode == PlayerCoverFlowMode.CUSTOM_STANDARD -> {
                    pendingTrackSkipDirection
                }
                else -> portraitTrackWipeDirection
            }
            val effectiveImmersiveLower = !landscapeMode && immersiveLower
            val landscapeTopPadding = if (landscapeMode) {
                if (uiSettings.hideStatusBar) 0.dp else homeStatusBarTopPadding(hideStatusBar = false) + 8.dp
            } else {
                0.dp
            }
            val landscapeEdgePadding = landscapePlan?.let { plan ->
                maxOf(plan.horizontalPaddingDp.dp, landscapeTopPadding)
            } ?: 0.dp
            val landscapeCoverSize = landscapePlan?.let { plan ->
                val heightBound = (screenHeight - landscapeEdgePadding * 2f).coerceAtLeast(0.dp)
                val widthBound = (
                    fullWidth - landscapeEdgePadding * 2f - plan.columnGapDp.dp - 280.dp
                ).coerceAtLeast(0.dp)
                minOf(heightBound, widthBound)
            }
            val playerLayoutWidth = landscapeCoverSize ?: screenWidth

            val appearance = rememberPlayerScreenAppearance(song, lowerBackground)
            val playerUiColors = rememberPlaybackContentColors(
                appearance.contentColors,
                uiSettings.playerPageTextColorMode,
            )
            val darkTheme = uiSettings.isDarkTheme()
            StatusBarEffect(
                hideStatusBar = uiSettings.hideStatusBar,
                darkStatusBarIcons = playerStatusBarUsesDarkIcons(
                    coverColor = Color(song.coverColorArgb),
                    lowerBackground = lowerBackground,
                    darkTheme = darkTheme,
                ),
            )

            val lyricsCloudAvailable = uiSettings.lyricsPageTheme == LyricsPageTheme.CLOUD &&
                song.lyricsDocument.lines.any { it.startMs > 0 }
            val lyricsCloudRequested = lyricsExpanded && lyricsCloudAvailable
            val horizontalClassicMounted = usesHorizontalClassicLyricsPage(
                mode = effectiveCoverFlowMode,
                lyricsCloudAvailable = lyricsCloudAvailable,
            )
            val customHorizontalClassicRequested = lyricsExpanded && horizontalClassicMounted
            val classicLyricsExpanded =
                lyricsExpanded && !lyricsCloudAvailable && !customHorizontalClassicRequested
            val useVerticalCloudSplit = lyricsCloudUsesVerticalSplit(effectiveCoverFlowMode)
            val lyricsPageTransition by animateFloatAsState(
                targetValue = if (lyricsCloudRequested || customHorizontalClassicRequested) 1f else 0f,
                animationSpec = tween(
                    durationMillis = if (rememberMicaMotionEnabled()) MicaMotion.DurationLongMs else 0,
                    easing = MicaMotion.Easing,
                ),
                label = "lyricsPageTransition",
            )

            val modelLyricsExpanded = classicLyricsExpanded && !landscapeMode
            val pageModel = rememberPlayerPageUiModel(
                surfaceState = surfaceState,
                queueState = queueState,
                uiSettings = uiSettings,
                lyricsExpanded = modelLyricsExpanded,
                screenHeight = screenHeight,
                screenWidth = playerLayoutWidth,
                coverAspectRatio = coverAspectRatio,
                coverSwitching = coverMotionActive,
                coverFlowMode = effectiveCoverFlowMode,
                immersiveAllowed = !landscapeMode,
            ) ?: return@BoxWithConstraints
            val previewFrame = pageModel.frameFor(screenHeight * 0.45f)
            val customLayout = uiSettings.customPlayerLowerLayout.normalized()
            val customPanelHeight = (
                fullHeight - contentPadding.calculateTopPadding() - contentPadding.calculateBottomPadding()
            ).coerceAtLeast(0.dp)
            val customMetrics = customPlayerLayoutMetrics(
                panelHeightDp = customPanelHeight.value,
                coverBaseHeightDp = previewFrame.cover.blockHeight.value,
                config = customLayout,
            )
            val customCoverVisible = effectiveCoverFlowMode == PlayerCoverFlowMode.CUSTOM_STANDARD &&
                customLayout.isVisible(PlayerLowerComponent.COVER)
            val customCoverFrame = if (customCoverVisible && customMetrics.coverTopDp != null) {
                val scale = customMetrics.coverVisualScale
                previewFrame.cover.copy(
                    width = previewFrame.cover.width * scale,
                    height = previewFrame.cover.height * scale,
                    startPadding = fullWidth * (1f - scale) / 2f + previewFrame.cover.startPadding * scale,
                    topPadding = customMetrics.coverTopDp.dp + previewFrame.cover.topPadding * scale,
                    blockHeight = previewFrame.cover.blockHeight * scale,
                    zoneStop = ((customMetrics.coverTopDp + previewFrame.cover.blockHeight.value * scale) /
                        customPanelHeight.value.coerceAtLeast(1f)).coerceIn(
                        0.12f,
                        PlayerCoverMaxScreenFraction,
                    ),
                )
            } else {
                previewFrame.cover
            }

            val motionEnabled = rememberMicaMotionEnabled()
            val backgroundZoneStop = if (fullHeight.value > 0f) {
                customCoverFrame.zoneStop * (screenHeight.value / fullHeight.value)
            } else {
                customCoverFrame.zoneStop
            }
            // Cover artwork wipes inside CoverSection via DirectionalTrackWipe so video hosts
            // stay mounted on the outgoing layer. Overlay artwork wipe would double-animate.
            // Keep internal wipe on in landscape (null direction → fade). Turning this off would
            // attach playerCoverIncomingWipe and flash alpha=0 for a frame before LaunchedEffect.
            val coverWipeEnabled =
                effectiveCoverFlowMode == PlayerCoverFlowMode.STANDARD ||
                    (effectiveCoverFlowMode == PlayerCoverFlowMode.CUSTOM_STANDARD && customCoverVisible)
            val coverArtworkUsesInternalWipe = coverWipeEnabled
            val coverWipeTarget = PlayerCoverWipeVisual(
                song = song,
                cover = customCoverFrame,
                coverColor = appearance.coverColor,
                backgroundMode = lowerBackground,
                backgroundZoneStop = backgroundZoneStop,
                coverDisplayMode = LocalCoverDisplayMode.current,
            )
            val coverWipeState = rememberPlayerCoverWipeState(
                target = coverWipeTarget,
                direction = effectiveTrackWipeDirection,
                enabled = coverWipeEnabled,
                motionEnabled = motionEnabled,
            )
            LaunchedEffect(
                effectiveCoverFlowMode,
                lowerBackground,
                previewFrame.coverFlowStageActive,
                motionEnabled,
                queueState.queue.size,
            ) {
                TrackSwitchPerformance.updateVisualContext(
                    TrackSwitchPerformance.VisualContext(
                    coverFlowMode = effectiveCoverFlowMode.name,
                        lowerBackground = lowerBackground.name,
                        coverFlowStageActive = previewFrame.coverFlowStageActive,
                        motionEnabled = motionEnabled,
                        queueSize = queueState.queue.size,
                    ),
                )
            }

            val coverFlowStageActive = previewFrame.coverFlowStageActive
            val photoStackStageActive = effectiveCoverFlowMode.usesPhotoStack &&
                previewFrame.photoStack.normalLayerVisible
            val onPlayerNext: () -> Unit = {
                if (coverFlowStageActive) {
                    val target = actions.coverFlowNextTarget()
                    if (target != null) coverFlowNavigation.skipToIndex(target)
                } else if (photoStackStageActive) {
                    val target = actions.coverFlowNextTarget()
                    if (target != null) photoStackNavigation.skipToIndex(target)
                } else {
                    actions.next()
                }
            }
            val onPlayerPrevious: () -> Unit = {
                if (coverFlowStageActive) {
                    val target = actions.coverFlowPreviousTarget()
                    if (target != null) coverFlowNavigation.skipToIndex(target)
                } else if (photoStackStageActive) {
                    val target = actions.coverFlowPreviousTarget()
                    if (target != null) photoStackNavigation.skipToIndex(target)
                } else {
                    actions.previous()
                }
            }
            LaunchedEffect(customCoverVisible) {
                if (!customCoverVisible && effectiveCoverFlowMode == PlayerCoverFlowMode.CUSTOM_STANDARD) {
                    onCoverBoundsChanged(null)
                }
            }
            val externalCoverIncomingWipe: Modifier =
                if (coverArtworkUsesInternalWipe) {
                    Modifier
                } else {
                    Modifier.playerCoverIncomingWipe(
                        state = coverWipeState,
                        target = coverWipeTarget,
                        pendingDirection = effectiveTrackWipeDirection,
                    )
                }
            val coverSection: @Composable (Modifier) -> Unit = { coverModifier ->
                NowPlayingCoverSection(
                    song = pageModel.song,
                    queue = pageModel.queue,
                    currentIndex = pageModel.currentIndex,
                    frame = previewFrame,
                    coverColor = appearance.coverColor,
                    contentColors = playerUiColors,
                    lowerBackground = lowerBackground,
                    artworkJunction = appearance.artworkJunction,
                    seekState = seekState,
                    isPlaying = pageModel.isPlaying,
                    coverFlowMode = effectiveCoverFlowMode,
                    videoAlbumCoverEnabled = uiSettings.videoAlbumCoverEnabled &&
                        (!landscapeMode || uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.STANDARD) &&
                        !lyricsExpanded,
                    trackSkipDirection = effectiveTrackWipeDirection,
                    particleCoverTuning = uiSettings.particleCoverTuning,
                    lyricsExpanded = classicLyricsExpanded,
                    coverContentAlpha = coverContentAlpha,
                    onCoverBoundsChanged = onCoverBoundsChanged,
                    onCoverAspectRatioChanged = { coverAspectRatio = it },
                    onCloseLyrics = { lyricsExpanded = false },
                    onCoverClick = if (
                        effectiveCoverFlowMode == PlayerCoverFlowMode.CUSTOM_STANDARD &&
                        uiSettings.customStandardCoverTapPlayPause
                    ) {
                        actions.togglePlay
                    } else {
                        null
                    },
                    onPlayQueueIndex = { index ->
                        TrackSwitchPerformance.armTrigger("queue-select")
                        actions.playQueueIndex(index)
                    },
                    onPrevious = actions.previous,
                    onNext = actions.next,
                    onCoverLongPress = { openSongActionMenu(song) },
                    onCoverMotionActiveChanged = { coverMotionActive = it },
                    coverFlowNavigation = coverFlowNavigation,
                    photoStackNavigation = photoStackNavigation,
                    screenWidth = screenWidth,
                    stripSongTitleParentheses = uiSettings.stripSongTitleParentheses,
                    modifier = coverModifier,
                )
            }

            NowPlayingBackground(
                coverColor = appearance.coverColor,
                albumArtUri = song.albumArtUri,
                mode = lowerBackground,
                coverZoneStop = backgroundZoneStop,
                modifier = Modifier.fillMaxSize(),
            )
            if (!landscapeMode) {
                OutgoingCoverBackgroundWipe(
                    state = coverWipeState,
                    target = coverWipeTarget,
                    pendingDirection = effectiveTrackWipeDirection,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            val lyricsSession = remember(song.lyricsDocument) { LyricsSession(song.lyricsDocument) }
            val lyricsRenderState = remember(lyricsSession, progressState.positionMs) {
                lyricsSession.snapshotAt(progressState.positionMs)
            }
            val landscapeLowerSection: @Composable (Modifier, Dp, Modifier, Modifier) -> Unit =
                { lowerModifier, panelHeight, titleSharedModifier, chromeSharedModifier ->
                val actualFrame = pageModel.frameFor(panelHeight)
                val landscapeLower = actualFrame.lower.copy(
                    chromeHeight = landscapeChromeHeight(
                        portraitChromeHeight = actualFrame.lower.chromeHeight,
                        portraitControlsBottomPadding = actualFrame.lower.controlsBottomPadding,
                    ),
                    controlsBottomPadding = 0.dp,
                )
                PlayerLowerPanelSection(
                    surfaceState = surfaceState,
                    activeSong = song,
                    lyricsRenderState = lyricsRenderState,
                    autoContentColors = appearance.contentColors,
                    colors = playerUiColors,
                    hifiBadgeColors = appearance.hifiBadgeColors,
                    playerPageTextColorMode = uiSettings.playerPageTextColorMode,
                    lowerBackground = lowerBackground,
                    lower = landscapeLower,
                    seekState = seekState,
                    immersiveLower = false,
                    lyricsPageOpen = false,
                    lyricsPageImmersive = false,
                    lyricsTextColorMode = uiSettings.lyricsPageTextColorMode,
                    lyricsAlignment = uiSettings.lyricsPageAlignment,
                    lyricsFontSizeSp = uiSettings.lyricsPageFontSizeSp,
                    lyricsTranslationFontSizeSp = uiSettings.lyricsPageTranslationFontSizeSp,
                    lyricsLineSpacingDp = uiSettings.lyricsPageLineSpacingDp,
                    lyricsWordAnimationPreset = uiSettings.lyricsWordAnimationPreset,
                    lyricsBilingualDisplayMode = uiSettings.lyricsBilingualDisplayMode,
                    stripSongTitleParentheses = uiSettings.stripSongTitleParentheses,
                    playerInfoVisibility = uiSettings.playerInfoVisibility,
                    playbackTuning = surfaceState.playbackTuning,
                    spectrumEnabled = actualFrame.spectrumEnabled,
                    trackSkipDirection = effectiveTrackWipeDirection,
                    trackWipeMotionEnabled = motionEnabled,
                    titleModifier = titleSharedModifier,
                    chromeModifier = chromeSharedModifier,
                    onCyclePlaybackQueueMode = actions.cyclePlaybackQueueMode,
                    onPrevious = onPlayerPrevious,
                    onTogglePlay = actions.togglePlay,
                    onNext = onPlayerNext,
                    onSeekToMs = actions.seekToMs,
                    onToggleImmersive = {},
                    onToggleLyricsPageImmersive = {},
                    onOpenEqualizer = onOpenEqualizer,
                    onOpenLyrics = { lyricsExpanded = true },
                    onOpenQueue = { queueSheetOpen = true },
                    modifier = lowerModifier,
                )
                }

            if (lyricsCloudAvailable && (lyricsCloudRequested || lyricsPageTransition > 0f)) {
                val cloudColors = rememberLyricsContentColors(
                    appearance.contentColors,
                    uiSettings.lyricsPageTextColorMode,
                )
                LyricsCloudPanel(
                    renderState = lyricsRenderState,
                    isPlaying = surfaceState.isPlaying,
                    isVisible = lyricsCloudRequested,
                    colors = cloudColors,
                    onLineClick = actions.seekToMs,
                    bilingualDisplayMode = uiSettings.lyricsBilingualDisplayMode,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .padding(top = landscapeTopPadding),
                )
            }

            if (horizontalClassicMounted && (customHorizontalClassicRequested || lyricsPageTransition > 0f)) {
                HorizontalClassicLyricsPage(
                    pageModel = pageModel,
                    uiSettings = uiSettings,
                    surfaceState = surfaceState,
                    song = song,
                    lyricsRenderState = lyricsRenderState,
                    autoContentColors = appearance.contentColors,
                    colors = playerUiColors,
                    hifiBadgeColors = appearance.hifiBadgeColors,
                    lowerBackground = lowerBackground,
                    seekState = seekState,
                    actions = actions,
                    contentPadding = contentPadding,
                    onOpenEqualizer = onOpenEqualizer,
                    onOpenQueue = { queueSheetOpen = true },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = if (useVerticalCloudSplit) {
                            0f
                        } else {
                            -with(density) { fullWidth.toPx() } * lyricsPageTransition
                        }
                    },
            ) {
            ParticleCoverPlayerLayer(
                song = pageModel.song,
                frame = previewFrame,
                seekState = seekState,
                screenWidth = fullWidth,
                screenHeight = fullHeight,
                contentPadding = contentPadding,
                motionEnabled = motionEnabled,
                coverColor = appearance.coverColor,
                tuning = uiSettings.particleCoverTuning,
                onAspectRatioChanged = { coverAspectRatio = it },
                onMotionActiveChanged = { coverMotionActive = it },
            )

            if (landscapeMode && lyricsCloudRequested) {
                // The cloud owns the whole landscape surface. Do not retain any cover host behind it.
            } else if (
                landscapePlan != null &&
                !lyricsCloudRequested &&
                !customHorizontalClassicRequested
            ) {
                val coverSize = checkNotNull(landscapeCoverSize)
                val classicCoverSize = minOf(
                    landscapePlan.coverLaneWidthDp,
                    screenHeight.value * 0.50f,
                ).dp
                val lyricsColors = rememberLyricsContentColors(
                    appearance.contentColors,
                    uiSettings.lyricsPageTextColorMode,
                )
                val landscapeSharedBoundsTransform =
                    rememberLandscapeClassicBoundsTransform(motionEnabled)
                SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = classicLyricsExpanded,
                        transitionSpec = MicaMotion.landscapeClassicLyricsTransition(motionEnabled),
                        label = "landscapeClassicLyrics",
                        modifier = Modifier.fillMaxSize(),
                    ) { expanded ->
                        val animatedVisibilityScope = this@AnimatedContent
                        val coverSharedModifier = with(this@SharedTransitionLayout) {
                            Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(
                                    LandscapeClassicSharedKeys.Cover,
                                ),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = landscapeSharedBoundsTransform,
                            )
                        }
                        val titleSharedModifier = with(this@SharedTransitionLayout) {
                            Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(
                                    LandscapeClassicSharedKeys.Title,
                                ),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = landscapeSharedBoundsTransform,
                            )
                        }
                        val chromeSharedModifier = with(this@SharedTransitionLayout) {
                            Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(
                                    LandscapeClassicSharedKeys.Chrome,
                                ),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = landscapeSharedBoundsTransform,
                            )
                        }
                        if (expanded) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(contentPadding)
                                    .padding(
                                        start = landscapePlan.horizontalPaddingDp.dp,
                                        top = landscapeTopPadding,
                                        end = landscapePlan.horizontalPaddingDp.dp,
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(
                                    landscapePlan.columnGapDp.dp,
                                ),
                            ) {
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .width(landscapePlan.coverLaneWidthDp.dp)
                                        .fillMaxHeight(),
                                ) {
                                    val classicFrame = pageModel.frameFor(maxHeight)
                                    val classicLower = classicFrame.lower.copy(
                                        chromeHeight = landscapeChromeHeight(
                                            portraitChromeHeight = classicFrame.lower.chromeHeight,
                                            portraitControlsBottomPadding =
                                                classicFrame.lower.controlsBottomPadding,
                                        ),
                                        controlsBottomPadding = 0.dp,
                                    )
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.SpaceEvenly,
                                    ) {
                                        // Match landscape player: cover fades, title hard-cuts.
                                        DirectionalTrackWipe(
                                            targetState = song,
                                            contentKey = Song::id,
                                            direction = null,
                                            motionEnabled = motionEnabled,
                                            fadeWhenNoDirection = true,
                                            modifier = Modifier
                                                .size(classicCoverSize)
                                                .then(coverSharedModifier)
                                                .onGloballyPositioned {
                                                    onCoverBoundsChanged(it.boundsInRoot())
                                                },
                                        ) { coverSong ->
                                            SongCover(
                                                albumArtUri = coverSong.albumArtUri,
                                                fallbackColor = appearance.coverColor,
                                                contentDescription = coverSong.album,
                                                decodeTarget = CoverDecodeTarget.forSpecialTheme(
                                                    with(density) { classicCoverSize.toPx() },
                                                ),
                                                onAspectRatioChanged = { coverAspectRatio = it },
                                                crossfadeMillis = if (motionEnabled) 200 else 0,
                                                allowPreviousImageUnderlay = false,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                        DirectionalTrackWipe(
                                            targetState = song,
                                            contentKey = Song::id,
                                            direction = null,
                                            motionEnabled = motionEnabled,
                                            modifier = titleSharedModifier,
                                        ) { titleSong ->
                                            SongTitleSection(
                                                title = SongTitleDisplay.displayTitle(
                                                    titleSong.title,
                                                    uiSettings.stripSongTitleParentheses,
                                                ),
                                                artist = titleSong.artist,
                                                album = titleSong.album,
                                                isBuffering = surfaceState.isBuffering,
                                                playbackError = surfaceState.playbackError,
                                                colors = playerUiColors,
                                                immersiveProgress = 0f,
                                                showAlbum = false,
                                            )
                                        }
                                        PlayerLowerPanelChrome(
                                            surfaceState = surfaceState,
                                            colors = playerUiColors,
                                            seekState = seekState,
                                            lower = classicLower,
                                            spectrumEnabled = false,
                                            onCyclePlaybackQueueMode =
                                                actions.cyclePlaybackQueueMode,
                                            onPrevious = onPlayerPrevious,
                                            onTogglePlay = actions.togglePlay,
                                            onNext = onPlayerNext,
                                            onOpenEqualizer = onOpenEqualizer,
                                            onOpenQueue = { queueSheetOpen = true },
                                            modifier = chromeSharedModifier,
                                        )
                                    }
                                }
                                // Remount on track change so LazyListState does not keep the
                                // previous song's scroll offset (avoids a downward jump to anchor).
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                ) {
                                    key(song.id) {
                                        ExpandedLyricsPanel(
                                            renderState = lyricsRenderState,
                                            isPlaying = surfaceState.isPlaying,
                                            colors = lyricsColors,
                                            onLineClick = actions.seekToMs,
                                            lyricsAlignment = uiSettings.lyricsPageAlignment,
                                            lyricsFontSizeSp = uiSettings.lyricsPageFontSizeSp,
                                            lyricsTranslationFontSizeSp =
                                                uiSettings.lyricsPageTranslationFontSizeSp,
                                            lyricsLineSpacingDp =
                                                uiSettings.lyricsPageLineSpacingDp,
                                            lyricsWordAnimationPreset =
                                                uiSettings.lyricsWordAnimationPreset,
                                            bilingualDisplayMode =
                                                uiSettings.lyricsBilingualDisplayMode,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(contentPadding)
                                    .padding(
                                        start = landscapeEdgePadding,
                                        top = landscapeEdgePadding,
                                        end = landscapeEdgePadding,
                                        bottom = landscapeEdgePadding,
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(
                                    landscapePlan.columnGapDp.dp,
                                ),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(coverSize)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.TopStart,
                                ) {
                                    coverSection(
                                        Modifier
                                            .width(coverSize)
                                            .requiredHeight(previewFrame.cover.blockHeight)
                                            .then(coverSharedModifier)
                                            .then(externalCoverIncomingWipe),
                                    )
                                }
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                ) {
                                    landscapeLowerSection(
                                        Modifier.fillMaxSize(),
                                        maxHeight,
                                        titleSharedModifier,
                                        chromeSharedModifier,
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (effectiveCoverFlowMode == PlayerCoverFlowMode.CUSTOM_STANDARD) {
                CustomPlayerPagePanel(
                    config = customLayout,
                    coverBaseHeightDp = previewFrame.cover.blockHeight.value,
                    coverContent = { visualScale ->
                        coverSection(
                            Modifier
                                .requiredHeight(previewFrame.cover.blockHeight)
                                .graphicsLayer {
                                    scaleX = visualScale
                                    scaleY = visualScale
                                }
                                .then(externalCoverIncomingWipe),
                        )
                    },
                    surfaceState = surfaceState,
                    activeSong = song,
                    lyricsRenderState = lyricsRenderState,
                    autoContentColors = appearance.contentColors,
                    colors = playerUiColors,
                    hifiBadgeColors = appearance.hifiBadgeColors,
                    playerPageTextColorMode = uiSettings.playerPageTextColorMode,
                    lowerBackground = lowerBackground,
                    seekState = seekState,
                    lyricsTextColorMode = uiSettings.lyricsPageTextColorMode,
                    bilingualDisplayMode = uiSettings.lyricsBilingualDisplayMode,
                    stripSongTitleParentheses = uiSettings.stripSongTitleParentheses,
                    playerInfoVisibility = uiSettings.playerInfoVisibility,
                    playbackTuning = surfaceState.playbackTuning,
                    spectrumEnabled = previewFrame.spectrumEnabled,
                    trackSkipDirection = effectiveTrackWipeDirection,
                    trackWipeMotionEnabled = motionEnabled,
                    onCyclePlaybackQueueMode = actions.cyclePlaybackQueueMode,
                    onPrevious = onPlayerPrevious,
                    onTogglePlay = actions.togglePlay,
                    onNext = onPlayerNext,
                    onOpenLyrics = { lyricsExpanded = true },
                    onOpenQueue = { queueSheetOpen = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                ) {
                    coverSection(
                        Modifier
                            .then(externalCoverIncomingWipe)
                            .graphicsLayer {
                                translationY = if (useVerticalCloudSplit) {
                                    -with(density) { fullHeight.toPx() } * 1.1f * lyricsPageTransition
                                } else {
                                    0f
                                }
                            },
                    )
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationY = if (useVerticalCloudSplit) {
                                    with(density) { fullHeight.toPx() } * 1.1f * lyricsPageTransition
                                } else {
                                    0f
                                }
                            },
                    ) {
                        val actualFrame = pageModel.frameFor(maxHeight)
                        PlayerLowerPanelSection(
                            surfaceState = surfaceState,
                            activeSong = song,
                            lyricsRenderState = lyricsRenderState,
                            autoContentColors = appearance.contentColors,
                            colors = playerUiColors,
                            hifiBadgeColors = appearance.hifiBadgeColors,
                            playerPageTextColorMode = uiSettings.playerPageTextColorMode,
                            lowerBackground = lowerBackground,
                            lower = actualFrame.lower,
                            seekState = seekState,
                            immersiveLower = effectiveImmersiveLower,
                            lyricsPageOpen = classicLyricsExpanded,
                            lyricsPageImmersive = uiSettings.lyricsPageImmersive,
                            lyricsTextColorMode = uiSettings.lyricsPageTextColorMode,
                            lyricsAlignment = uiSettings.lyricsPageAlignment,
                            lyricsFontSizeSp = uiSettings.lyricsPageFontSizeSp,
                            lyricsTranslationFontSizeSp = uiSettings.lyricsPageTranslationFontSizeSp,
                            lyricsLineSpacingDp = uiSettings.lyricsPageLineSpacingDp,
                            lyricsWordAnimationPreset = uiSettings.lyricsWordAnimationPreset,
                            lyricsBilingualDisplayMode = uiSettings.lyricsBilingualDisplayMode,
                            stripSongTitleParentheses = uiSettings.stripSongTitleParentheses,
                            playerInfoVisibility = uiSettings.playerInfoVisibility,
                            playbackTuning = surfaceState.playbackTuning,
                            spectrumEnabled = actualFrame.spectrumEnabled,
                            trackSkipDirection = effectiveTrackWipeDirection,
                            trackWipeMotionEnabled = motionEnabled,
                            onCyclePlaybackQueueMode = actions.cyclePlaybackQueueMode,
                            onPrevious = onPlayerPrevious,
                            onTogglePlay = actions.togglePlay,
                            onNext = onPlayerNext,
                            onSeekToMs = actions.seekToMs,
                            onToggleImmersive = actions.toggleImmersiveLower,
                            onToggleLyricsPageImmersive = actions.toggleLyricsPageImmersive,
                            onOpenEqualizer = onOpenEqualizer,
                            onOpenLyrics = { lyricsExpanded = true },
                            onOpenQueue = { queueSheetOpen = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
                if (!landscapeMode && !coverArtworkUsesInternalWipe) {
                    OutgoingCoverArtworkWipe(
                        state = coverWipeState,
                        target = coverWipeTarget,
                        pendingDirection = effectiveTrackWipeDirection,
                        contentPadding = contentPadding,
                        coverContentAlpha = coverContentAlpha,
                        modifier = Modifier.graphicsLayer {
                            translationY = if (useVerticalCloudSplit) {
                                -with(density) { fullHeight.toPx() } * 1.1f * lyricsPageTransition
                            } else {
                                0f
                            }
                        },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(
                    if (playerOverlayOpen) Modifier.clearAndSetSemantics { } else Modifier,
                ),
        )

        if (queueSheetOpen) {
            key(isLandscapeWindow) {
                PlaybackQueueSheet(
                    queue = queueState.queue,
                    currentIndex = queueState.currentIndex,
                    isPlaying = surfaceState.isPlaying,
                    onDismiss = { queueSheetOpen = false },
                    onPlayAt = actions.playQueueIndex,
                    onMove = actions.moveQueueItem,
                    onRemove = actions.removeQueueItem,
                    landscape = isLandscapeWindow,
                    listState = queueListState,
                )
            }
        }

        actionMenuSong?.let { menuSong ->
            SongActionMenuSheet(
                song = menuSong,
                onDismiss = { actionMenuSong = null },
                onAction = { handleSongMenuAction(it, menuSong) },
                onArtistClick = { artistName ->
                    actionMenuSong = null
                    onBrowseArtist(artistName)
                },
                onAlbumClick = { albumTitle ->
                    actionMenuSong = null
                    onBrowseAlbum(albumTitle)
                },
                showSleepTimer = true,
                sleepTimerLabel = sleepTimerMenuLabel,
                onSleepTimerClick = {
                    actionMenuSong = null
                    sleepTimerSheetOpen = true
                },
                showPlaybackTuning = playbackTuningAvailable,
                playbackTuningLabel = formatPlaybackTuningMenuLabel(surfaceState.playbackTuning),
                onPlaybackTuningClick = {
                    actionMenuSong = null
                    playbackTuningSheetOpen = true
                },
                landscape = isLandscapeWindow,
            )
        }

        if (sleepTimerSheetOpen) {
            SleepTimerSheet(
                isActive = sleepTimerActive,
                activeRemainingLabel = sleepTimerRemainingLabel,
                onDismiss = { sleepTimerSheetOpen = false },
                onSelectMinutes = { minutes ->
                    sleepTimer.start(minutes)
                    sleepTimerSheetOpen = false
                    scope.launch {
                        val label = com.mica.music.data.SleepTimerController.presetLabel(minutes)
                        snackbarHostState.showSnackbar("将在 $label 后停止播放")
                    }
                },
                onCancel = {
                    sleepTimer.cancel()
                    scope.launch {
                        snackbarHostState.showSnackbar("已关闭睡眠定时")
                    }
                },
                landscape = isLandscapeWindow,
            )
        }

        if (playbackTuningSheetOpen) {
            PlaybackTuningSheet(
                tuning = surfaceState.playbackTuning,
                onDismiss = { playbackTuningSheetOpen = false },
                onSpeedChange = actions.setPlaybackSpeed,
                onPitchSemitonesChange = actions.setPlaybackPitchSemitones,
                onReset = actions.resetPlaybackTuning,
                landscape = isLandscapeWindow,
            )
        }

        addToPlaylistSong?.let { playlistSong ->
            AddToPlaylistSheet(
                songs = listOf(playlistSong),
                playlistStore = playlistStore,
                onDismiss = { addToPlaylistSong = null },
                onCreated = { message ->
                    scope.launch { snackbarHostState.showSnackbar(message) }
                },
                landscape = isLandscapeWindow,
            )
        }

        pendingDeleteSong?.let { deleteSong ->
            MicaConfirmDialog(
                visible = true,
                title = "删除音乐",
                message = "确定从设备删除「${deleteSong.title}」？此操作不可撤销。",
                confirmLabel = "删除",
                destructive = true,
                onConfirm = {
                    performDeleteSong(deleteSong)
                    pendingDeleteSong = null
                },
                onDismiss = { pendingDeleteSong = null },
            )
        }
    }
}

internal fun lyricsCloudUsesVerticalSplit(mode: com.mica.music.data.PlayerCoverFlowMode): Boolean =
    mode == com.mica.music.data.PlayerCoverFlowMode.STANDARD || mode.usesCoverFlowStage

internal fun usesHorizontalClassicLyricsPage(
    mode: com.mica.music.data.PlayerCoverFlowMode,
    lyricsCloudAvailable: Boolean,
): Boolean = mode.usesHorizontalLyricsPage && !lyricsCloudAvailable

private fun playerStatusBarUsesDarkIcons(
    coverColor: Color,
    lowerBackground: PlayerLowerBackgroundMode,
    darkTheme: Boolean,
): Boolean {
    if (lowerBackground == PlayerLowerBackgroundMode.THEME) return !darkTheme
    return coverColor.relativeLuminance() > 0.35f
}
