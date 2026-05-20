package com.mica.music.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
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
import com.mica.music.ui.screens.NowPlayingScreen
import com.mica.music.ui.screens.SettingsScreen
import com.mica.music.ui.screens.SongDetailScreen

object Routes {
    const val Home = "home"
    const val Settings = "settings"
    const val Equalizer = "equalizer"
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

    NavHost(
        navController = navController,
        startDestination = Routes.Home,
        enterTransition = {
            slideIntoContainer(SlideDirection.Up, animationSpec = tween(300))
        },
        exitTransition = {
            slideOutOfContainer(SlideDirection.Down, animationSpec = tween(300))
        },
        popEnterTransition = {
            slideIntoContainer(SlideDirection.Down, animationSpec = tween(300))
        },
        popExitTransition = {
            slideOutOfContainer(SlideDirection.Up, animationSpec = tween(300))
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
                val statusTop = homeStatusBarTopPadding()
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
            val statusTop = homeStatusBarTopPadding()
            SettingsScreen(
                library = library,
                uiSettings = uiSettings,
                onBack = { navController.popBackStack() },
                onOpenEqualizer = { navController.navigate(Routes.Equalizer) },
                contentPadding = PaddingValues(
                    top = statusTop,
                    bottom = navBarPadding.calculateBottomPadding(),
                ),
            )
        }
        composable(Routes.Equalizer) {
            val statusTop = homeStatusBarTopPadding()
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
