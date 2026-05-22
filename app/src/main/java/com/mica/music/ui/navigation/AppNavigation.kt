package com.mica.music.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import com.mica.music.ui.system.homeStatusBarTopPadding
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import com.mica.music.ui.screens.EqualizerScreen
import com.mica.music.ui.screens.HomeScreen
import com.mica.music.ui.screens.MetadataDebugScreen
import com.mica.music.ui.screens.NowPlayingScreen
import com.mica.music.ui.screens.SettingsScreen
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.screens.SongDetailScreen

object Routes {
    const val Home = "home"
    const val Settings = "settings"
    const val Equalizer = "equalizer"
    const val MetadataDebug = "metadata_debug"
    const val NowPlaying = "now_playing"
    const val SongDetail = "song_detail/{songId}"

    fun songDetail(songId: String): String =
        "song_detail/${Uri.encode(songId)}"
}

@Composable
fun AppNavigation(
    library: MusicLibrary,
    playerController: PlayerController,
    uiSettings: AppUiSettings,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val navController = rememberNavController()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val motionEnabled = rememberMicaMotionEnabled()
    val navFade = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs)
    val navSlide = MicaMotion.tweenIntOffset(motionEnabled, MicaMotion.DurationMediumMs)

    NavHost(
        navController = navController,
        startDestination = Routes.Home,
        // 进入：子页自下而上；底下主页只淡出，不滑走
        enterTransition = {
            fadeIn(navFade) + slideIntoContainer(SlideDirection.Up, animationSpec = navSlide)
        },
        exitTransition = {
            fadeOut(navFade)
        },
        // 返回：子页原路向下滑走；底下的主页只淡入，不再滑入
        popEnterTransition = {
            fadeIn(navFade)
        },
        popExitTransition = {
            fadeOut(navFade) + slideOutOfContainer(SlideDirection.Down, animationSpec = navSlide)
        },
        modifier = Modifier,
    ) {
        composable(Routes.Home) {
            HomeScreen(
                library = library,
                playerController = playerController,
                uiSettings = uiSettings,
                onSongClick = { songId ->
                    playerController.playSongById(songId)
                    navController.navigate(Routes.NowPlaying)
                },
                onMiniPlayerExpand = {
                    navController.navigate(Routes.NowPlaying)
                },
                onOpenSettings = {
                    navController.navigate(Routes.Settings)
                },
                onOpenSongDetail = { songId ->
                    navController.navigate(Routes.songDetail(songId))
                },
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
                onOpenEqualizer = { navController.navigate(Routes.Equalizer) },
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
        composable(Routes.NowPlaying) {
            val playerPadding = navBarPadding
            NowPlayingScreen(
                playerController = playerController,
                uiSettings = uiSettings,
                onClose = { navController.popBackStack() },
                onOpenEqualizer = { navController.navigate(Routes.Equalizer) },
                contentPadding = playerPadding,
            )
        }
    }
}
