package com.mica.music.ui.navigation

import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import com.mica.music.data.SleepTimerController
import com.mica.music.ui.components.PlayerSheetHost
import com.mica.music.ui.components.miniPlayerListClearance
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.screens.AboutScreen
import com.mica.music.ui.screens.EqualizerScreen
import com.mica.music.ui.screens.home.BrowseDestination
import com.mica.music.ui.screens.home.HomeNavigationIntent
import com.mica.music.ui.screens.home.HomePlaybackActions
import com.mica.music.ui.screens.home.HomePlaybackState
import com.mica.music.ui.screens.home.HomeScreen
import com.mica.music.ui.screens.home.HomeSection
import com.mica.music.ui.screens.MetadataDebugScreen
import com.mica.music.ui.screens.NowPlayingActions
import com.mica.music.ui.screens.ParticleCoverPreviewScreen
import com.mica.music.ui.screens.PhotoStackShadowPreviewScreen
import com.mica.music.ui.screens.settings.SettingsScreen
import com.mica.music.ui.screens.SongDetailScreen
import com.mica.music.ui.system.homeStatusBarTopPadding
import com.mica.music.util.DiagnosticLog
import com.mica.music.util.logBackFlow
import kotlinx.coroutines.CancellationException

private const val BackRootDebugTag = "DEBUG-BACK-ROOT-1A2B"

object Routes {
    const val Home = "home"
    const val Settings = "settings"
    const val Equalizer = "equalizer"
    const val About = "about"
    const val MetadataDebug = "metadata_debug"
    const val ParticleCoverPreview = "particle_cover_preview"
    const val PhotoStackShadowPreview = "photo_stack_shadow_preview"
    const val SongDetail = "song_detail/{songId}"

    fun songDetail(songId: String): String =
        "song_detail/${Uri.encode(songId)}"
}

@Composable
fun AppNavigation(
    coordinator: AppNavigationCoordinator,
    library: MusicLibrary,
    playerController: PlayerController,
    sleepTimer: SleepTimerController,
    uiSettings: AppUiSettings,
    contentPadding: PaddingValues = PaddingValues(),
) {
    AppNavigationMain(
        coordinator = coordinator,
        library = library,
        playerController = playerController,
        uiSettings = uiSettings,
    )
}

@Composable
fun AppNavigationMain(
    coordinator: AppNavigationCoordinator,
    library: MusicLibrary,
    playerController: PlayerController,
    uiSettings: AppUiSettings,
) {
    val navController = rememberNavController()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val motionEnabled = rememberMicaMotionEnabled()
    val navFade = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs)
    val navSlide = MicaMotion.tweenIntOffset(motionEnabled, MicaMotion.DurationMediumMs)
    val bottomOverlayClearance = if (playerController.currentSong != null) {
        miniPlayerListClearance(uiSettings.miniPlayerStyle)
    } else {
        0.dp
    }
    val playerOverlayOwnsBack = playerOverlayOwnsBack(
        playerExpanded = coordinator.playerExpanded,
        overlayFullScreen = coordinator.overlayFullScreen,
    )

    LaunchedEffect(
        playerOverlayOwnsBack,
        coordinator.playerExpanded,
        coordinator.overlayFullScreen,
    ) {
        logBackFlow(
            "state root owns=$playerOverlayOwnsBack " +
                "playerExpanded=${coordinator.playerExpanded} " +
                "overlayFullScreen=${coordinator.overlayFullScreen}",
        )
        DiagnosticLog.event(
            "BackRoot",
            "$BackRootDebugTag owner-state owns=$playerOverlayOwnsBack " +
                "playerExpanded=${coordinator.playerExpanded} " +
                "overlayFullScreen=${coordinator.overlayFullScreen}",
        )
    }

    DisposableEffect(navController) {
        coordinator.attachNavController(navController)
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { controller, destination, _ ->
            logBackFlow(
                "page route=${destination.route ?: destination.id} " +
                    "backStack=${controller.previousBackStackEntry?.destination?.route ?: "none"} " +
                    "playerExpanded=${coordinator.playerExpanded} " +
                    "overlayFullScreen=${coordinator.overlayFullScreen}",
            )
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose {
            navController.removeOnDestinationChangedListener(listener)
            coordinator.detachNavController(navController)
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.Home,
        enterTransition = {
            fadeIn(navFade) + slideIntoContainer(SlideDirection.Up, animationSpec = navSlide)
        },
        exitTransition = { fadeOut(navFade) },
        popEnterTransition = { fadeIn(navFade) },
        popExitTransition = {
            fadeOut(navFade) + slideOutOfContainer(SlideDirection.Down, animationSpec = navSlide)
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(Routes.Home) {
            val homePlaybackState = homePlaybackState(playerController)
            val homePlaybackActions = rememberHomePlaybackActions(playerController)
            HomeScreen(
                library = library,
                playbackState = homePlaybackState,
                playbackActions = homePlaybackActions,
                uiSettings = uiSettings,
                onSongClick = { songId ->
                    logBackFlow("player-overlay open source=song-click song=$songId")
                    playerController.playSongById(songId)
                    coordinator.playerExpanded = true
                },
                onMiniPlayerExpand = {
                    logBackFlow("player-overlay open source=mini-player")
                    coordinator.playerExpanded = true
                },
                onOpenSettings = {
                    logBackFlow("nav-action open-settings from=home")
                    coordinator.navigate(Routes.Settings)
                },
                onOpenEqualizer = {
                    logBackFlow("nav-action open-equalizer from=home")
                    coordinator.navigate(Routes.Equalizer)
                },
                onOpenAbout = {
                    logBackFlow("nav-action open-about from=home")
                    coordinator.navigate(Routes.About)
                },
                onOpenSongDetail = { songId ->
                    logBackFlow("nav-action open-song-detail from=home song=$songId")
                    coordinator.navigateSongDetail(songId)
                },
                showMiniPlayer = false,
                locateCurrentSongRequest = coordinator.locateCurrentSongRequest,
                homeNavigationIntent = coordinator.homeNavigationIntent,
                onHomeNavigationIntentConsumed = { coordinator.homeNavigationIntent = null },
                contentPadding = navBarPadding,
                playerOverlayOpen = playerOverlayOwnsBack,
            )
        }
        composable(
            route = Routes.SongDetail,
            arguments = listOf(navArgument("songId") { type = NavType.StringType }),
        ) { entry ->
            val songId = entry.arguments?.getString("songId")
            val song = songId?.let { library.songById(it) }
            if (song == null) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    logBackFlow("nav-action pop-song-detail missing-song song=$songId")
                    navController.popBackStack()
                }
            } else {
                val statusTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)
                SongDetailScreen(
                    song = song,
                    library = library,
                    onBack = {
                        logBackFlow("back-consume source=song-detail-topbar song=${song.id}")
                        navController.popBackStack()
                    },
                    contentPadding = PaddingValues(
                        top = statusTop,
                        bottom = navBarPadding.calculateBottomPadding(),
                    ),
                )
            }
        }
        composable(Routes.Settings) {
            val statusTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)
            SettingsScreen(
                library = library,
                uiSettings = uiSettings,
                onBack = {
                    logBackFlow("back-consume source=settings-topbar")
                    navController.popBackStack()
                },
                onOpenMetadataDebug = {
                    logBackFlow("nav-action open-metadata-debug from=settings")
                    coordinator.navigate(Routes.MetadataDebug)
                },
                onOpenParticleCoverPreview = {
                    logBackFlow("nav-action open-particle-preview from=settings")
                    coordinator.navigate(Routes.ParticleCoverPreview)
                },
                onOpenPhotoStackShadowPreview = {
                    logBackFlow("nav-action open-photo-stack-preview from=settings")
                    coordinator.navigate(Routes.PhotoStackShadowPreview)
                },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
                bottomContentClearance = bottomOverlayClearance,
                playerOverlayOpen = playerOverlayOwnsBack,
            )
        }
        composable(Routes.ParticleCoverPreview) {
            val statusTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)
            ParticleCoverPreviewScreen(
                library = library,
                savedTuning = uiSettings.particleCoverTuning,
                onSaveTuning = uiSettings::updateParticleCoverTuning,
                onBack = {
                    logBackFlow("back-consume source=particle-preview-topbar")
                    navController.popBackStack()
                },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
            )
        }
        composable(Routes.MetadataDebug) {
            val statusTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)
            MetadataDebugScreen(
                library = library,
                playerController = playerController,
                onBack = {
                    logBackFlow("back-consume source=metadata-debug-topbar")
                    navController.popBackStack()
                },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
            )
        }
        composable(Routes.PhotoStackShadowPreview) {
            val statusTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)
            PhotoStackShadowPreviewScreen(
                library = library,
                onBack = {
                    logBackFlow("back-consume source=photo-stack-preview-topbar")
                    navController.popBackStack()
                },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
            )
        }
        composable(Routes.Equalizer) {
            val statusTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)
            EqualizerScreen(
                onBack = {
                    logBackFlow("back-consume source=equalizer-topbar")
                    navController.popBackStack()
                },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
                bottomContentClearance = bottomOverlayClearance,
            )
        }
        composable(Routes.About) {
            val statusTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)
            AboutScreen(
                songs = library.songs,
                onBack = {
                    logBackFlow("back-consume source=about-topbar")
                    navController.popBackStack()
                },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
                bottomContentClearance = bottomOverlayClearance,
            )
        }
    }

    PredictiveBackHandler(enabled = playerOverlayOwnsBack) { backEvents ->
        logBackFlow(
            "back-start source=root-player owns=true " +
                "playerExpanded=${coordinator.playerExpanded} " +
                "overlayFullScreen=${coordinator.overlayFullScreen}",
        )
        try {
            backEvents.collect { backEvent ->
                coordinator.playerBackProgress = backEvent.progress.coerceIn(0f, 1f)
            }
            logBackFlow(
                "back-consume source=root-player owns=true " +
                    "playerExpanded=${coordinator.playerExpanded} " +
                    "overlayFullScreen=${coordinator.overlayFullScreen}",
            )
            DiagnosticLog.event(
                "BackRoot",
                "$BackRootDebugTag root-consume playerExpanded=${coordinator.playerExpanded} " +
                    "overlayFullScreen=${coordinator.overlayFullScreen}",
            )
            coordinator.playerExpanded = false
        } catch (_: CancellationException) {
            logBackFlow(
                "back-cancel source=root-player " +
                    "playerExpanded=${coordinator.playerExpanded} " +
                    "overlayFullScreen=${coordinator.overlayFullScreen}",
            )
            coordinator.playerExpanded = true
        } finally {
            coordinator.playerBackProgress = null
        }
    }
}

@Composable
fun PlayerSheetOverlay(
    coordinator: AppNavigationCoordinator,
    library: MusicLibrary,
    playerController: PlayerController,
    sleepTimer: SleepTimerController,
    uiSettings: AppUiSettings,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
) {
    val actions = rememberNowPlayingActions(playerController, uiSettings)
    PlayerSheetHost(
        library = library,
        surfaceState = playerController.playbackSurfaceState,
        progressState = playerController.playbackProgressState,
        queueState = playerController.playbackQueueState,
        sleepTimer = sleepTimer,
        actions = actions,
        uiSettings = uiSettings,
        expanded = coordinator.playerExpanded,
        predictiveBackProgress = coordinator.playerBackProgress,
        onExpandedChange = {
            logBackFlow("player-overlay expanded-change value=$it source=sheet")
            coordinator.playerExpanded = it
        },
        onOpenEqualizer = {
            logBackFlow("nav-action open-equalizer from=player")
            coordinator.navigate(Routes.Equalizer)
        },
        onOpenSongDetail = { songId ->
            logBackFlow("nav-action open-song-detail from=player song=$songId")
            coordinator.playerExpanded = false
            coordinator.navigateSongDetail(songId)
        },
        onBrowseArtist = { artistName ->
            logBackFlow("nav-action browse-artist from=player artist=$artistName")
            coordinator.playerExpanded = false
            coordinator.popBackStackHome()
            coordinator.homeNavigationIntent = HomeNavigationIntent(
                section = HomeSection.Artists,
                browseDestination = BrowseDestination.Artist(artistName),
            )
        },
        onBrowseAlbum = { albumTitle ->
            logBackFlow("nav-action browse-album from=player album=$albumTitle")
            coordinator.playerExpanded = false
            coordinator.popBackStackHome()
            coordinator.homeNavigationIntent = HomeNavigationIntent(
                section = HomeSection.Albums,
                browseDestination = BrowseDestination.Album(albumTitle),
            )
        },
        onLocateCurrentSong = {
            logBackFlow("nav-action locate-current-song from=player")
            coordinator.popBackStackHome()
            coordinator.locateCurrentSongRequest++
        },
        onOverlayFullScreenChange = {
            logBackFlow("player-overlay fullscreen-change value=$it")
            coordinator.overlayFullScreen = it
        },
        contentPadding = contentPadding,
        modifier = modifier,
    )
}

@Composable
private fun rememberNowPlayingActions(
    playerController: PlayerController,
    uiSettings: AppUiSettings,
): NowPlayingActions =
    remember(playerController, uiSettings) {
        NowPlayingActions(
            syncPosition = playerController::syncPosition,
            setSeekUiActive = playerController::setSeekUiActive,
            seekToMs = playerController::seekToMs,
            playQueueIndex = playerController::playSong,
            moveQueueItem = playerController::moveInQueue,
            removeQueueItem = playerController::removeFromQueue,
            togglePlay = playerController::togglePlay,
            previous = playerController::previous,
            next = playerController::next,
            coverFlowPreviousTarget = playerController::manualPreviousTarget,
            coverFlowNextTarget = playerController::manualNextTarget,
            cyclePlaybackQueueMode = playerController::cyclePlaybackQueueMode,
            toggleImmersiveLower = uiSettings::togglePlayerImmersiveLower,
            toggleLyricsPageImmersive = uiSettings::toggleLyricsPageImmersive,
            insertPlayNext = playerController::insertPlayNext,
            setQueue = playerController::setQueue,
        )
    }

private fun homePlaybackState(playerController: PlayerController): HomePlaybackState =
    HomePlaybackState(
        currentSong = playerController.currentSong,
        isPlaying = playerController.playbackSurfaceState.isPlaying,
        positionMs = playerController.playbackProgressState.positionMs,
        queue = playerController.songQueue,
    )

@Composable
private fun rememberHomePlaybackActions(
    playerController: PlayerController,
): HomePlaybackActions =
    remember(playerController) {
        HomePlaybackActions(
            syncPlaybackState = playerController::syncPlaybackState,
            insertPlayNext = playerController::insertPlayNext,
            setQueue = playerController::setQueue,
            appendToQueue = { songs ->
                if (songs.isNotEmpty()) {
                    playerController.setQueue(playerController.songQueue + songs)
                }
            },
            togglePlay = playerController::togglePlay,
            previous = playerController::previous,
            next = playerController::next,
        )
    }
