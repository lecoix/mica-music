package com.mica.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mica.music.data.AppUiSettings
import com.mica.music.data.DsdSupport
import com.mica.music.data.MusicLibrary
import com.mica.music.data.LyricsPageTheme
import com.mica.music.data.LyricsSync
import com.mica.music.data.PlaybackProgressState
import com.mica.music.data.PlaybackQueueState
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.ui.screens.player.view.CoverFlowCarouselNavigationBridge
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.PlaylistStore
import com.mica.music.data.SleepTimerController
import com.mica.music.data.Song
import com.mica.music.data.renderStateAt
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.ui.components.AddToPlaylistSheet
import com.mica.music.ui.components.MicaConfirmDialog
import com.mica.music.ui.components.PlaybackQueueSheet
import com.mica.music.ui.components.PlaybackTuningSheet
import com.mica.music.ui.components.SleepTimerSheet
import com.mica.music.ui.components.SongActionMenuSheet
import com.mica.music.ui.components.SongMenuAction
import com.mica.music.ui.components.cachedCoverAspectRatio
import com.mica.music.ui.components.formatPlaybackTuningMenuLabel
import com.mica.music.ui.components.rememberPlaybackSeekState
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.screens.player.ParticleCoverPlayerLayer
import com.mica.music.ui.screens.player.rememberPlayerPageUiModel
import com.mica.music.ui.screens.player.view.PhotoStackCarouselNavigationBridge
import com.mica.music.ui.system.StatusBarEffect
import com.mica.music.ui.theme.NowPlayingBackground
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
fun NowPlayingContent(
    library: MusicLibrary,
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

    val playlistStore = remember { PlaylistStore(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var actionMenuSong by remember { mutableStateOf<Song?>(null) }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    var pendingDeleteSong by remember { mutableStateOf<Song?>(null) }
    var queueSheetOpen by remember { mutableStateOf(false) }
    var sleepTimerSheetOpen by remember { mutableStateOf(false) }
    var playbackTuningSheetOpen by remember { mutableStateOf(false) }
    var lyricsExpanded by remember { mutableStateOf(false) }

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

    LaunchedEffect(sleepTimer) {
        sleepTimer.onExpired = {
            scope.launch {
                snackbarHostState.showSnackbar("睡眠定时已结束，播放已暂停")
            }
        }
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
    val hasWordSyncedLyrics = remember(song.lyrics) { song.lyrics.any { it.cues.isNotEmpty() } }
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

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val fullHeight = maxHeight
            val fullWidth = maxWidth
            val bottomInset = contentPadding.calculateBottomPadding()
            val screenHeight = fullHeight - bottomInset
            val screenWidth = fullWidth
            val density = LocalDensity.current

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
                LyricsSync.hasTimedLyrics(song.lyrics)
            val lyricsCloudRequested = lyricsExpanded && lyricsCloudAvailable
            val classicLyricsExpanded = lyricsExpanded && !lyricsCloudAvailable
            val useVerticalCloudSplit = lyricsCloudUsesVerticalSplit(uiSettings.playerCoverFlowMode)
            val cloudTransition by animateFloatAsState(
                targetValue = if (lyricsCloudRequested) 1f else 0f,
                animationSpec = tween(
                    durationMillis = if (rememberMicaMotionEnabled()) MicaMotion.DurationLongMs else 0,
                    easing = MicaMotion.Easing,
                ),
                label = "lyricsCloudPageTransition",
            )

            val previewModel = rememberPlayerPageUiModel(
                surfaceState = surfaceState,
                queueState = queueState,
                uiSettings = uiSettings,
                lyricsExpanded = classicLyricsExpanded,
                panelHeight = screenHeight * 0.45f,
                screenHeight = screenHeight,
                screenWidth = screenWidth,
                coverAspectRatio = coverAspectRatio,
                coverSwitching = coverMotionActive,
            ) ?: return@BoxWithConstraints

            val motionEnabled = rememberMicaMotionEnabled()
            LaunchedEffect(
                uiSettings.playerCoverFlowMode,
                lowerBackground,
                previewModel.frame.coverFlowStageActive,
                motionEnabled,
                queueState.queue.size,
            ) {
                TrackSwitchPerformance.updateVisualContext(
                    TrackSwitchPerformance.VisualContext(
                        coverFlowMode = uiSettings.playerCoverFlowMode.name,
                        lowerBackground = lowerBackground.name,
                        coverFlowStageActive = previewModel.frame.coverFlowStageActive,
                        motionEnabled = motionEnabled,
                        queueSize = queueState.queue.size,
                    ),
                )
            }

            val backgroundZoneStop = if (fullHeight.value > 0f) {
                previewModel.frame.cover.zoneStop * (screenHeight.value / fullHeight.value)
            } else {
                previewModel.frame.cover.zoneStop
            }

            val coverFlowStageActive = previewModel.frame.coverFlowStageActive
            val photoStackStageActive = uiSettings.playerCoverFlowMode.usesPhotoStack &&
                previewModel.frame.photoStack.normalLayerVisible
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

            NowPlayingBackground(
                coverColor = appearance.coverColor,
                albumArtUri = song.albumArtUri,
                mode = lowerBackground,
                coverZoneStop = backgroundZoneStop,
                modifier = Modifier.fillMaxSize(),
            )

            if (lyricsCloudRequested || cloudTransition > 0f) {
                val cloudRenderState = remember(song.lyrics, progressState.positionMs) {
                    song.lyrics.renderStateAt(progressState.positionMs)
                }
                val cloudColors = rememberLyricsContentColors(
                    appearance.contentColors,
                    uiSettings.lyricsPageTextColorMode,
                )
                LyricsCloudPanel(
                    renderState = cloudRenderState,
                    isPlaying = surfaceState.isPlaying,
                    isVisible = lyricsCloudRequested,
                    colors = cloudColors,
                    onLineClick = actions.seekToMs,
                    bilingualDisplayMode = uiSettings.lyricsBilingualDisplayMode,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = if (useVerticalCloudSplit) {
                            0f
                        } else {
                            -with(density) { fullWidth.toPx() } * cloudTransition
                        }
                    },
            ) {
            ParticleCoverPlayerLayer(
                song = previewModel.song,
                frame = previewModel.frame,
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

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                    NowPlayingCoverSection(
                        song = previewModel.song,
                        queue = previewModel.queue,
                        currentIndex = previewModel.currentIndex,
                        frame = previewModel.frame,
                        coverColor = appearance.coverColor,
                        contentColors = playerUiColors,
                        lowerBackground = lowerBackground,
                        artworkJunction = appearance.artworkJunction,
                        seekState = seekState,
                        isPlaying = previewModel.isPlaying,
                        coverFlowMode = uiSettings.playerCoverFlowMode,
                        particleCoverTuning = uiSettings.particleCoverTuning,
                        lyricsExpanded = classicLyricsExpanded,
                        coverContentAlpha = coverContentAlpha,
                        onCoverBoundsChanged = onCoverBoundsChanged,
                        onCoverAspectRatioChanged = { coverAspectRatio = it },
                        onCloseLyrics = { lyricsExpanded = false },
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
                        modifier = Modifier.graphicsLayer {
                            translationY = if (useVerticalCloudSplit) {
                                -with(density) { fullHeight.toPx() } * 1.1f * cloudTransition
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
                                    with(density) { fullHeight.toPx() } * 1.1f * cloudTransition
                                } else {
                                    0f
                                }
                            },
                    ) {
                        val panelHeight = maxHeight
                        val pageModel = rememberPlayerPageUiModel(
                            surfaceState = surfaceState,
                            queueState = queueState,
                            uiSettings = uiSettings,
                            lyricsExpanded = classicLyricsExpanded,
                            panelHeight = panelHeight,
                            screenHeight = screenHeight,
                            screenWidth = screenWidth,
                            coverAspectRatio = coverAspectRatio,
                            coverSwitching = coverMotionActive,
                        ) ?: return@BoxWithConstraints
                        PlayerLowerPanelSection(
                            surfaceState = surfaceState,
                            progressState = progressState,
                            activeSong = song,
                            lyrics = song.lyrics,
                            autoContentColors = appearance.contentColors,
                            colors = playerUiColors,
                            hifiBadgeColors = appearance.hifiBadgeColors,
                            playerPageTextColorMode = uiSettings.playerPageTextColorMode,
                            lowerBackground = lowerBackground,
                            lower = pageModel.frame.lower,
                            seekState = seekState,
                            immersiveLower = immersiveLower,
                            lyricsPageOpen = classicLyricsExpanded,
                            lyricsPageImmersive = uiSettings.lyricsPageImmersive,
                            lyricsTextColorMode = uiSettings.lyricsPageTextColorMode,
                            lyricsAlignment = uiSettings.lyricsPageAlignment,
                            lyricsFontSizeSp = uiSettings.lyricsPageFontSizeSp,
                            lyricsTranslationFontSizeSp = uiSettings.lyricsPageTranslationFontSizeSp,
                            lyricsBilingualDisplayMode = uiSettings.lyricsBilingualDisplayMode,
                            stripSongTitleParentheses = uiSettings.stripSongTitleParentheses,
                            playerInfoVisibility = uiSettings.playerInfoVisibility,
                            playbackTuning = surfaceState.playbackTuning,
                            spectrumEnabled = pageModel.frame.spectrumEnabled,
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
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

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
            )
        }

        if (playbackTuningSheetOpen) {
            PlaybackTuningSheet(
                tuning = surfaceState.playbackTuning,
                onDismiss = { playbackTuningSheetOpen = false },
                onSpeedChange = actions.setPlaybackSpeed,
                onPitchSemitonesChange = actions.setPlaybackPitchSemitones,
                onReset = actions.resetPlaybackTuning,
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

private fun playerStatusBarUsesDarkIcons(
    coverColor: Color,
    lowerBackground: PlayerLowerBackgroundMode,
    darkTheme: Boolean,
): Boolean {
    if (lowerBackground == PlayerLowerBackgroundMode.THEME) return !darkTheme
    return coverColor.relativeLuminance() > 0.35f
}
