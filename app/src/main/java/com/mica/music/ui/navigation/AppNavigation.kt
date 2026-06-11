package com.mica.music.ui.navigation

import android.net.Uri
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
import androidx.compose.ui.Modifier
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
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.screens.AboutScreen
import com.mica.music.ui.screens.EqualizerScreen
import com.mica.music.ui.screens.BrowseDestination
import com.mica.music.ui.screens.HomeNavigationIntent
import com.mica.music.ui.screens.HomeScreen
import com.mica.music.ui.screens.HomeSection
import com.mica.music.ui.screens.MetadataDebugScreen
import com.mica.music.ui.screens.SettingsScreen
import com.mica.music.ui.screens.SongDetailScreen
import com.mica.music.ui.system.homeStatusBarTopPadding

object Routes {
    const val Home = "home"
    const val Settings = "settings"
    const val Equalizer = "equalizer"
    const val About = "about"
    const val MetadataDebug = "metadata_debug"
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

    DisposableEffect(navController) {
        coordinator.attachNavController(navController)
        onDispose { coordinator.detachNavController(navController) }
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
            HomeScreen(
                library = library,
                playerController = playerController,
                uiSettings = uiSettings,
                onSongClick = { songId ->
                    playerController.playSongById(songId)
                    coordinator.playerExpanded = true
                },
                onMiniPlayerExpand = { coordinator.playerExpanded = true },
                onOpenSettings = { coordinator.navigate(Routes.Settings) },
                onOpenEqualizer = { coordinator.navigate(Routes.Equalizer) },
                onOpenAbout = { coordinator.navigate(Routes.About) },
                onOpenSongDetail = { songId ->
                    coordinator.navigateSongDetail(songId)
                },
                showMiniPlayer = false,
                locateCurrentSongRequest = coordinator.locateCurrentSongRequest,
                homeNavigationIntent = coordinator.homeNavigationIntent,
                onHomeNavigationIntentConsumed = { coordinator.homeNavigationIntent = null },
                contentPadding = navBarPadding,
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
                    navController.popBackStack()
                }
            } else {
                val statusTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)
                SongDetailScreen(
                    song = song,
                    library = library,
                    onBack = { navController.popBackStack() },
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
                onBack = { navController.popBackStack() },
                onOpenMetadataDebug = { coordinator.navigate(Routes.MetadataDebug) },
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
                onBack = { navController.popBackStack() },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
            )
        }
        composable(Routes.Equalizer) {
            val statusTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)
            EqualizerScreen(
                onBack = { navController.popBackStack() },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
            )
        }
        composable(Routes.About) {
            val statusTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)
            AboutScreen(
                onBack = { navController.popBackStack() },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
            )
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
    PlayerSheetHost(
        library = library,
        playerController = playerController,
        sleepTimer = sleepTimer,
        uiSettings = uiSettings,
        expanded = coordinator.playerExpanded,
        onExpandedChange = { coordinator.playerExpanded = it },
        onOpenEqualizer = { coordinator.navigate(Routes.Equalizer) },
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
        onBrowseAlbum = { albumTitle ->
            coordinator.playerExpanded = false
            coordinator.popBackStackHome()
            coordinator.homeNavigationIntent = HomeNavigationIntent(
                section = HomeSection.Albums,
                browseDestination = BrowseDestination.Album(albumTitle),
            )
        },
        onLocateCurrentSong = {
            coordinator.popBackStackHome()
            coordinator.locateCurrentSongRequest++
        },
        onOverlayFullScreenChange = { coordinator.overlayFullScreen = it },
        contentPadding = contentPadding,
        modifier = modifier,
    )
}
