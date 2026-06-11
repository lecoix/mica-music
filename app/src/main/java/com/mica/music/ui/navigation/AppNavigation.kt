package com.mica.music.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
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
    library: MusicLibrary,
    playerController: PlayerController,
    sleepTimer: SleepTimerController,
    uiSettings: AppUiSettings,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val navController = rememberNavController()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val motionEnabled = rememberMicaMotionEnabled()
    val navFade = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs)
    val navSlide = MicaMotion.tweenIntOffset(motionEnabled, MicaMotion.DurationMediumMs)
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    var locateCurrentSongRequest by rememberSaveable { mutableStateOf(0) }
    var homeNavigationIntent by remember { mutableStateOf<HomeNavigationIntent?>(null) }

    Box(Modifier.fillMaxSize()) {
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
                        playerExpanded = true
                    },
                    onMiniPlayerExpand = { playerExpanded = true },
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                    onOpenEqualizer = { navController.navigate(Routes.Equalizer) },
                    onOpenAbout = { navController.navigate(Routes.About) },
                    onOpenSongDetail = { songId ->
                        navController.navigate(Routes.songDetail(songId))
                    },
                    showMiniPlayer = false,
                    locateCurrentSongRequest = locateCurrentSongRequest,
                    homeNavigationIntent = homeNavigationIntent,
                    onHomeNavigationIntentConsumed = { homeNavigationIntent = null },
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
                    onOpenMetadataDebug = { navController.navigate(Routes.MetadataDebug) },
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

        PlayerSheetHost(
            library = library,
            playerController = playerController,
            sleepTimer = sleepTimer,
            uiSettings = uiSettings,
            expanded = playerExpanded,
            onExpandedChange = { playerExpanded = it },
            onOpenEqualizer = { navController.navigate(Routes.Equalizer) },
            onOpenSongDetail = { songId ->
                playerExpanded = false
                navController.navigate(Routes.songDetail(songId))
            },
            onBrowseArtist = { artistName ->
                playerExpanded = false
                navController.popBackStack(Routes.Home, inclusive = false)
                homeNavigationIntent = HomeNavigationIntent(
                    section = HomeSection.Artists,
                    browseDestination = BrowseDestination.Artist(artistName),
                )
            },
            onBrowseAlbum = { albumTitle ->
                playerExpanded = false
                navController.popBackStack(Routes.Home, inclusive = false)
                homeNavigationIntent = HomeNavigationIntent(
                    section = HomeSection.Albums,
                    browseDestination = BrowseDestination.Album(albumTitle),
                )
            },
            onLocateCurrentSong = {
                navController.popBackStack(Routes.Home, inclusive = false)
                locateCurrentSongRequest++
            },
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f),
        )
    }
}
