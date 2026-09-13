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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mica.music.data.AppUiSettings
import com.mica.music.usb.UsbHybridDiagnosticsPort
import com.mica.music.diagnostics.PlaybackCapabilityReportProvider
import com.mica.music.audio.loudness.LoudnessScanPort
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayStats
import com.mica.music.data.PlaylistStore
import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.toPlaybackSong
import com.mica.music.playback.PlayerController
import com.mica.music.playback.SleepTimerController
import com.mica.music.ui.components.PlayerSheetHost
import com.mica.music.ui.components.miniPlayerListClearance
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.screens.AboutScreen
import com.mica.music.ui.screens.EqualizerScreen
import com.mica.music.ui.screens.SoundFxScreen
import com.mica.music.ui.screens.home.BrowseDestination
import com.mica.music.ui.screens.home.HomeNavigationIntent
import com.mica.music.ui.screens.home.HomePlaybackActions
import com.mica.music.ui.screens.home.HomePlaybackState
import com.mica.music.ui.screens.home.HomeScreen
import com.mica.music.ui.screens.home.HomeSection
import com.mica.music.ui.screens.MetadataDebugScreen
import com.mica.music.ui.screens.NowPlayingActions
import com.mica.music.ui.screens.PhotoStackShadowPreviewScreen
import com.mica.music.ui.screens.settings.SettingsScreen
import com.mica.music.ui.screens.SongDetailScreen
import com.mica.music.ui.screens.SpatialAudioScreen
import com.mica.music.ui.screens.VersionUpdateScreen
import com.mica.music.ui.system.homeStatusBarTopPadding
import kotlinx.coroutines.CancellationException

object Routes {
    const val Home = "home"
    const val Settings = "settings"
    const val Equalizer = "equalizer"
    const val SoundFx = "sound_fx"
    const val About = "about"
    const val VersionUpdate = "version_update"
    const val MetadataDebug = "metadata_debug"
    const val SpatialAudio = "spatial_audio"
    const val PhotoStackShadowPreview = "photo_stack_shadow_preview"
    const val SongDetail = "song_detail/{songId}"

    fun songDetail(songId: String): String =
        "song_detail/${Uri.encode(songId)}"
}

@Composable
fun AppNavigationMain(
    coordinator: AppNavigationCoordinator,
    library: MusicLibrary,
    playlistStore: PlaylistStore,
    remoteCatalogRepository: RemoteCatalogRepository,
    remotePlayStats: Map<String, PlayStats> = emptyMap(),
    playerController: PlayerController,
    uiSettings: AppUiSettings,
    loudnessScanPort: LoudnessScanPort,
    playbackCapabilityReportProvider: PlaybackCapabilityReportProvider,
    usbHybridDiagnosticsPort: UsbHybridDiagnosticsPort,
) {
    val navController = rememberNavController()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val motionEnabled = rememberMicaMotionEnabled()
    val navFade = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs)
    val navSlide = MicaMotion.tweenIntOffset(motionEnabled, MicaMotion.DurationMediumMs)
    val bottomOverlayClearance = if (playerController.playbackSurfaceState.currentSong != null) {
        miniPlayerListClearance(uiSettings.miniPlayerStyle)
    } else {
        0.dp
    }
    val playerOverlayOwnsBack = playerOverlayOwnsBack(
        playerExpanded = coordinator.playerExpanded,
        overlayFullScreen = coordinator.overlayFullScreen,
    )
    DisposableEffect(navController) {
        coordinator.attachNavController(navController)
        onDispose {
            coordinator.detachNavController(navController)
        }
    }

    val remoteTracks by remember(remoteCatalogRepository) {
        remoteCatalogRepository.observeTracksForEnabledSources()
    }.collectAsState(initial = emptyList())
    val remoteSongs = remember(remoteTracks, remotePlayStats) {
        remoteTracks.map { track ->
            val song = track.toPlaybackSong()
            remotePlayStats[song.id]?.let { stats ->
                song.copy(
                    playCount = stats.count,
                    totalListenSeconds = stats.totalListenSeconds,
                    lastPlayedAtMs = stats.lastPlayedAtMs,
                )
            } ?: song
        }
    }
    val remoteSongsById = remember(remoteSongs) {
        remoteSongs.associateBy { it.id }
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
                playlistStore = playlistStore,
                remoteSongs = remoteSongs,
                playbackState = homePlaybackState,
                playbackActions = homePlaybackActions,
                uiSettings = uiSettings,
                onSongClick = { songId ->
                    playerController.playSongById(songId)
                    coordinator.playerExpanded = true
                },
                onQueueSongClick = { queue, songId ->
                    playerController.playQueueSong(queue, songId)
                    coordinator.playerExpanded = true
                },
                onMiniPlayerExpand = {
                    coordinator.playerExpanded = true
                },
                onOpenSettings = {
                    coordinator.navigate(Routes.Settings)
                },
                onOpenEqualizer = {
                    coordinator.navigate(Routes.Equalizer)
                },
                onOpenAbout = {
                    coordinator.navigate(Routes.About)
                },
                onOpenSongDetail = { songId ->
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
            val song = songId?.let { id ->
                library.songById(id)
                    ?: remoteSongsById[id]
                    ?: playerController.playbackSurfaceState.currentSong?.takeIf { it.id == id }
            }
            if (song == null) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            } else {
                val statusTop = homeStatusBarTopPadding(
                    hideStatusBar = uiSettings.statusBarVisibilityMode.hidesOutsidePlayer,
                )
                SongDetailScreen(
                    song = song,
                    library = library,
                    loudnessScanPort = loudnessScanPort,
                    onBack = {
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
        composable(Routes.Settings) {
            val statusTop = homeStatusBarTopPadding(
                hideStatusBar = uiSettings.statusBarVisibilityMode.hidesOutsidePlayer,
            )
            SettingsScreen(
                library = library,
                uiSettings = uiSettings,
                loudnessScanPort = loudnessScanPort,
                usbHybridDiagnosticsPort = usbHybridDiagnosticsPort,
                canOpenCustomPlayerLayoutEditor = playerController.playbackSurfaceState.currentSong != null,
                onOpenCustomPlayerLayoutEditor = {
                    coordinator.customLayoutEditRequested = true
                    coordinator.playerExpanded = true
                },
                onBack = {
                    navController.popBackStack()
                },
                onOpenMetadataDebug = {
                    coordinator.navigate(Routes.MetadataDebug)
                },
                onOpenSpatialAudio = {
                    coordinator.navigate(Routes.SpatialAudio)
                },
                onOpenSoundFx = {
                    coordinator.navigate(Routes.SoundFx)
                },
                onOpenEqualizer = {
                    coordinator.navigate(Routes.Equalizer)
                },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
                bottomContentClearance = bottomOverlayClearance,
                playerOverlayOpen = playerOverlayOwnsBack,
            )
        }
        composable(Routes.MetadataDebug) {
            val statusTop = homeStatusBarTopPadding(
                hideStatusBar = uiSettings.statusBarVisibilityMode.hidesOutsidePlayer,
            )
            MetadataDebugScreen(
                library = library,
                playerController = playerController,
                onBack = {
                    navController.popBackStack()
                },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
            )
        }
        composable(Routes.SpatialAudio) {
            val statusTop = homeStatusBarTopPadding(
                hideStatusBar = uiSettings.statusBarVisibilityMode.hidesOutsidePlayer,
            )
            SpatialAudioScreen(
                onBack = {
                    navController.popBackStack()
                },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
                bottomContentClearance = bottomOverlayClearance,
            )
        }
        composable(Routes.PhotoStackShadowPreview) {
            val statusTop = homeStatusBarTopPadding(
                hideStatusBar = uiSettings.statusBarVisibilityMode.hidesOutsidePlayer,
            )
            PhotoStackShadowPreviewScreen(
                library = library,
                onBack = {
                    navController.popBackStack()
                },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
                bottomContentClearance = bottomOverlayClearance,
            )
        }
        composable(Routes.Equalizer) {
            val statusTop = homeStatusBarTopPadding(
                hideStatusBar = uiSettings.statusBarVisibilityMode.hidesOutsidePlayer,
            )
            EqualizerScreen(
                onBack = {
                    navController.popBackStack()
                },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
                bottomContentClearance = bottomOverlayClearance,
            )
        }
        composable(Routes.SoundFx) {
            val statusTop = homeStatusBarTopPadding(
                hideStatusBar = uiSettings.statusBarVisibilityMode.hidesOutsidePlayer,
            )
            SoundFxScreen(
                onBack = {
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
            val statusTop = homeStatusBarTopPadding(
                hideStatusBar = uiSettings.statusBarVisibilityMode.hidesOutsidePlayer,
            )
            AboutScreen(
                songs = library.songs,
                playbackCapabilityReportProvider = playbackCapabilityReportProvider,
                onBack = {
                    navController.popBackStack()
                },
                onOpenVersionUpdate = {
                    navController.navigate(Routes.VersionUpdate)
                },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
                bottomContentClearance = bottomOverlayClearance,
            )
        }
        composable(Routes.VersionUpdate) {
            val statusTop = homeStatusBarTopPadding(
                hideStatusBar = uiSettings.statusBarVisibilityMode.hidesOutsidePlayer,
            )
            VersionUpdateScreen(
                onBack = {
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
        try {
            backEvents.collect { backEvent ->
                coordinator.playerBackProgress = backEvent.progress.coerceIn(0f, 1f)
            }
            coordinator.playerExpanded = false
        } catch (_: CancellationException) {
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
    playlistStore: PlaylistStore,
    playerController: PlayerController,
    sleepTimer: SleepTimerController,
    uiSettings: AppUiSettings,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
) {
    val actions = rememberNowPlayingActions(playerController, uiSettings)
    PlayerSheetHost(
        library = library,
        playlistStore = playlistStore,
        surfaceState = playerController.playbackSurfaceState,
        progressState = playerController.playbackProgressState,
        queueState = playerController.playbackQueueState,
        sleepTimer = sleepTimer,
        actions = actions,
        uiSettings = uiSettings,
        customLayoutEditRequested = coordinator.customLayoutEditRequested,
        onCustomLayoutEditRequestConsumed = {
            coordinator.customLayoutEditRequested = false
        },
        expanded = coordinator.playerExpanded,
        predictiveBackProgress = coordinator.playerBackProgress,
        onExpandedChange = {
            coordinator.playerExpanded = it
        },
        onOpenEqualizer = {
            coordinator.navigate(Routes.Equalizer)
        },
        onOpenSongDetail = { songId ->
            coordinator.playerExpanded = false
            coordinator.navigateSongDetail(songId)
        },
        onBrowseArtist = { artistName ->
            coordinator.playerExpanded = false
            coordinator.popBackStackHome()
            coordinator.homeNavigationIntent = HomeNavigationIntent(
                section = HomeSection.Artists,
                browseDestination = BrowseDestination.Artist(artistName),
            )
        },
        onBrowseAlbum = { albumKey ->
            coordinator.playerExpanded = false
            coordinator.popBackStackHome()
            coordinator.homeNavigationIntent = HomeNavigationIntent(
                section = HomeSection.Albums,
                browseDestination = BrowseDestination.Album(albumKey),
            )
        },
        onLocateCurrentSong = {
            coordinator.popBackStackHome()
            coordinator.locateCurrentSongRequest++
        },
        onOverlayFullScreenChange = {
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
            removeSongById = playerController::removeSongById,
            setPlaybackSpeed = playerController::setPlaybackSpeed,
            setPlaybackPitchSemitones = playerController::setPlaybackPitchSemitones,
            resetPlaybackTuning = playerController::resetPlaybackTuning,
            peekTrackSkipDirection = playerController::peekTrackSkipDirection,
            consumeTrackSkipDirection = playerController::consumeTrackSkipDirection,
            attachMusicVideoOutput = playerController::attachMusicVideoOutput,
            detachMusicVideoOutput = playerController::detachMusicVideoOutput,
        )
    }

private fun homePlaybackState(playerController: PlayerController): HomePlaybackState =
    HomePlaybackState(
        currentSong = playerController.playbackSurfaceState.currentSong,
        isPlaying = playerController.playbackSurfaceState.isPlaying,
        positionMs = playerController.playbackProgressState.positionMs,
        positionRevision = playerController.playbackProgressState.positionRevision,
        queue = playerController.playbackQueueState.queue,
        isBuffering = playerController.playbackSurfaceState.isBuffering,
        playbackSpeed = playerController.playbackSurfaceState.playbackTuning.speed,
    )

@Composable
private fun rememberHomePlaybackActions(
    playerController: PlayerController,
): HomePlaybackActions =
    remember(playerController) {
        HomePlaybackActions(
            syncPlaybackState = playerController::syncPlaybackState,
            syncPosition = playerController::syncPosition,
            insertPlayNext = playerController::insertPlayNext,
            setQueue = playerController::setQueue,
            removeSongById = playerController::removeSongById,
            appendToQueue = playerController::appendSongs,
            togglePlay = playerController::togglePlay,
            previous = playerController::previous,
            next = playerController::next,
        )
    }
