package com.mica.music.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.mica.music.ui.system.homeStatusBarTopPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.mica.music.data.AppUiSettings
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerController
import com.mica.music.data.Song
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.screens.AboutScreen
import com.mica.music.ui.screens.EqualizerScreen
import com.mica.music.ui.screens.HomeScreen
import com.mica.music.ui.screens.MetadataDebugScreen
import com.mica.music.ui.screens.NowPlayingScreen
import com.mica.music.ui.screens.SettingsScreen
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.screens.SongDetailScreen
import com.mica.music.ui.theme.HifiSpacing
import kotlin.math.abs

private data class SharedCoverTransitionRequest(
    val key: Int,
    val song: Song,
    val from: Rect,
    val to: Rect,
)

private data class SongCoverBounds(
    val songId: String,
    val bounds: Rect,
)

object Routes {
    const val Home = "home"
    const val Settings = "settings"
    const val Equalizer = "equalizer"
    const val About = "about"
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
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val motionEnabled = rememberMicaMotionEnabled()
    val navFade = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs)
    val navSlide = MicaMotion.tweenIntOffset(motionEnabled, MicaMotion.DurationMediumMs)
    val sharedCoverProgress = remember { Animatable(1f) }
    var sharedCoverKey by remember { mutableIntStateOf(0) }
    var sharedCoverAnimatingKey by remember { mutableIntStateOf(0) }
    var sharedCoverAnimationStartKey by remember { mutableIntStateOf(0) }
    var sharedCoverExitPopKey by remember { mutableIntStateOf(0) }
    var sharedCoverExitReadyKey by remember { mutableIntStateOf(0) }
    var sharedCoverHideKey by remember { mutableIntStateOf(0) }
    var sharedCoverRequest by remember { mutableStateOf<SharedCoverTransitionRequest?>(null) }
    var pendingEnterSong by remember { mutableStateOf<Song?>(null) }
    var miniCoverBounds by remember { mutableStateOf<Rect?>(null) }
    var stableMiniCoverBounds by remember { mutableStateOf<Rect?>(null) }
    var stableMiniUpdatesEnabled by remember { mutableStateOf(true) }
    var nowPlayingCoverBounds by remember { mutableStateOf<SongCoverBounds?>(null) }
    val sharedCoverEnabled =
        uiSettings.playerCoverFlowMode == PlayerCoverFlowMode.STANDARD &&
            uiSettings.miniPlayerStyle == MiniPlayerStyle.FLOATING_ISLAND
    val hiddenCoverSongId = sharedCoverRequest
        ?.takeIf { it.key == sharedCoverHideKey }
        ?.song
        ?.id
        ?: pendingEnterSong?.id

    fun startSharedCoverTransition(
        song: Song,
        from: Rect,
        to: Rect,
        startImmediately: Boolean = true,
    ): Int {
        sharedCoverKey += 1
        sharedCoverRequest = SharedCoverTransitionRequest(
            key = sharedCoverKey,
            song = song,
            from = from,
            to = to,
        )
        if (startImmediately) {
            sharedCoverHideKey = sharedCoverKey
            sharedCoverAnimationStartKey = sharedCoverKey
        }
        return sharedCoverKey
    }

    LaunchedEffect(playerController.currentSong?.id) {
        nowPlayingCoverBounds = null
    }

    LaunchedEffect(currentRoute) {
        if (currentRoute == Routes.Home) {
            stableMiniUpdatesEnabled = true
        }
    }

    LaunchedEffect(pendingEnterSong?.id, nowPlayingCoverBounds, miniCoverBounds, sharedCoverEnabled) {
        val song = pendingEnterSong ?: return@LaunchedEffect
        val from = miniCoverBounds ?: return@LaunchedEffect
        val to = nowPlayingCoverBounds
            ?.takeIf { it.songId == song.id }
            ?.bounds
            ?: return@LaunchedEffect
        if (!sharedCoverEnabled) {
            pendingEnterSong = null
            return@LaunchedEffect
        }
        pendingEnterSong = null
        startSharedCoverTransition(song, from, to)
    }

    LaunchedEffect(sharedCoverRequest?.key, sharedCoverAnimationStartKey) {
        val request = sharedCoverRequest ?: return@LaunchedEffect
        if (request.key != sharedCoverAnimationStartKey) return@LaunchedEffect
        sharedCoverProgress.snapTo(0f)
        sharedCoverAnimatingKey = request.key
        sharedCoverProgress.animateTo(
            targetValue = 1f,
            animationSpec = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs),
        )
        sharedCoverRequest = null
        sharedCoverAnimationStartKey = 0
        sharedCoverAnimatingKey = 0
        sharedCoverHideKey = 0
        stableMiniUpdatesEnabled = true
    }

    LaunchedEffect(sharedCoverExitReadyKey) {
        val key = sharedCoverExitReadyKey
        if (key == 0) return@LaunchedEffect
        withFrameNanos { }
        if (sharedCoverRequest?.key == key) {
            sharedCoverAnimationStartKey = key
            navController.popBackStack()
        }
        sharedCoverExitReadyKey = 0
        sharedCoverExitPopKey = 0
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val rootHeightPx = with(density) { maxHeight.toPx() }
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val fallbackMiniCoverBounds = if (uiSettings.miniPlayerStyle == MiniPlayerStyle.FLOATING_ISLAND) {
            val safeBottom = maxOf(
                WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding(),
                HifiSpacing.xs,
            )
            with(density) {
                val coverSize = 48.dp.toPx()
                val left = (HifiSpacing.xl + HifiSpacing.md).toPx()
                val bottom = rootHeightPx - safeBottom.toPx() - HifiSpacing.sm.toPx() * 2f
                Rect(
                    left = left,
                    top = bottom - coverSize,
                    right = left + coverSize,
                    bottom = bottom,
                )
            }
        } else {
            null
        }
        LaunchedEffect(stableMiniCoverBounds, fallbackMiniCoverBounds, currentRoute, imeVisible) {
            if (currentRoute != Routes.Home || !stableMiniUpdatesEnabled || imeVisible) {
                return@LaunchedEffect
            }
            val stable = stableMiniCoverBounds ?: return@LaunchedEffect
            val fallback = fallbackMiniCoverBounds ?: return@LaunchedEffect
            if (!stable.isUsableMiniTarget(rootHeightPx, fallback)) {
                stableMiniCoverBounds = fallback
            }
        }
    NavHost(
        navController = navController,
        startDestination = Routes.Home,
        // 进入：子页自下而上；底下主页只淡出，不滑走
        enterTransition = {
            val sharedCoverEnter = targetState.destination.route == Routes.NowPlaying &&
                pendingEnterSong != null
            if (sharedCoverEnter) {
                fadeIn(navFade)
            } else {
                fadeIn(navFade) + slideIntoContainer(SlideDirection.Up, animationSpec = navSlide)
            }
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
                    stableMiniUpdatesEnabled = false
                    playerController.playSongById(songId)
                    navController.navigate(Routes.NowPlaying)
                },
                onMiniPlayerExpand = {
                    nowPlayingCoverBounds = null
                    val enterFrom = (stableMiniCoverBounds ?: miniCoverBounds)
                        ?.takeIf { it.isUsableMiniTarget(rootHeightPx, fallbackMiniCoverBounds) }
                        ?: fallbackMiniCoverBounds
                    if (sharedCoverEnabled && enterFrom != null) {
                        miniCoverBounds = enterFrom
                        stableMiniUpdatesEnabled = false
                        pendingEnterSong = playerController.currentSong
                    } else {
                        pendingEnterSong = null
                    }
                    navController.navigate(Routes.NowPlaying)
                },
                onOpenSettings = {
                    navController.navigate(Routes.Settings)
                },
                onOpenEqualizer = {
                    navController.navigate(Routes.Equalizer)
                },
                onOpenAbout = {
                    navController.navigate(Routes.About)
                },
                onOpenSongDetail = { songId ->
                    navController.navigate(Routes.songDetail(songId))
                },
                miniPlayerCoverAlpha = if (hiddenCoverSongId == playerController.currentSong?.id) 0f else 1f,
                onMiniPlayerCoverBoundsChanged = {
                    if (currentRoute == Routes.Home && stableMiniUpdatesEnabled && !imeVisible) {
                        miniCoverBounds = it
                        if (it != null && it.isUsableMiniTarget(rootHeightPx, fallbackMiniCoverBounds)) {
                            stableMiniCoverBounds = it
                        }
                    }
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
        composable(Routes.NowPlaying) {
            val playerPadding = navBarPadding
            NowPlayingScreen(
                playerController = playerController,
                uiSettings = uiSettings,
                onClose = {
                    val song = playerController.currentSong
                    val from = nowPlayingCoverBounds
                        ?.takeIf { it.songId == song?.id }
                        ?.bounds
                    val to = bestMiniTarget(
                        mini = miniCoverBounds,
                        stable = stableMiniCoverBounds,
                        fallback = fallbackMiniCoverBounds,
                        rootHeightPx = rootHeightPx,
                    )
                    if (sharedCoverEnabled && song != null && from != null && to != null) {
                        sharedCoverExitPopKey = startSharedCoverTransition(
                            song = song,
                            from = from,
                            to = to,
                            startImmediately = false,
                        )
                    } else {
                        navController.popBackStack()
                    }
                },
                onOpenEqualizer = { navController.navigate(Routes.Equalizer) },
                contentPadding = playerPadding,
                coverContentAlpha = if (hiddenCoverSongId == playerController.currentSong?.id) 0f else 1f,
                onCoverBoundsChanged = { bounds ->
                    val songId = playerController.currentSong?.id
                    nowPlayingCoverBounds = if (songId != null && bounds != null) {
                        SongCoverBounds(songId = songId, bounds = bounds)
                    } else {
                        null
                    }
                },
            )
        }
    }
        SharedCoverTransitionOverlay(
            request = sharedCoverRequest,
            progress = if (sharedCoverRequest?.key == sharedCoverAnimatingKey) {
                sharedCoverProgress.value
            } else {
                0f
            },
            onImageReady = { key ->
                if (key == sharedCoverExitPopKey && sharedCoverExitReadyKey != key) {
                    sharedCoverHideKey = key
                    sharedCoverExitReadyKey = key
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(100f),
        )
    }
}

@Composable
private fun SharedCoverTransitionOverlay(
    request: SharedCoverTransitionRequest?,
    progress: Float,
    onImageReady: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = request ?: return
    val density = LocalDensity.current
    val rect = lerpRect(active.from, active.to, progress.coerceIn(0f, 1f))
    Box(modifier) {
        SongCover(
            albumArtUri = active.song.albumArtUri,
            fallbackColor = active.song.coverColor,
            contentDescription = null,
            crossfadeMillis = 0,
            drawBackdropWhileLoading = false,
            onImageReady = { onImageReady(active.key) },
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = rect.left.toInt(),
                        y = rect.top.toInt(),
                    )
                }
                .size(
                    width = with(density) { rect.width.toDp() },
                    height = with(density) { rect.height.toDp() },
                ),
        )
    }
}

private fun lerpRect(start: Rect, end: Rect, fraction: Float): Rect {
    fun lerp(startValue: Float, endValue: Float): Float =
        startValue + (endValue - startValue) * fraction
    return Rect(
        left = lerp(start.left, end.left),
        top = lerp(start.top, end.top),
        right = lerp(start.right, end.right),
        bottom = lerp(start.bottom, end.bottom),
    )
}

private fun bestMiniTarget(
    mini: Rect?,
    stable: Rect?,
    fallback: Rect?,
    rootHeightPx: Float,
): Rect? =
    listOfNotNull(mini, stable, fallback)
        .filter { it.width > 0f && it.height > 0f && rootHeightPx > 0f }
        .maxByOrNull { it.bottom }

private fun Rect.isUsableMiniTarget(rootHeightPx: Float, expected: Rect?): Boolean {
    if (width <= 0f || height <= 0f || rootHeightPx <= 0f) return false
    if (expected == null) return bottom >= rootHeightPx * 0.9f

    val bottomTolerance = expected.height * 0.5f
    val leftTolerance = expected.width * 0.5f
    return abs(bottom - expected.bottom) <= bottomTolerance &&
        abs(left - expected.left) <= leftTolerance
}
