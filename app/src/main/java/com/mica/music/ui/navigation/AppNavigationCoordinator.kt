package com.mica.music.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import com.mica.music.ui.screens.home.HomeNavigationIntent

@Stable
class AppNavigationCoordinator internal constructor(
    private val playerExpandedState: MutableState<Boolean>,
    private val locateState: MutableIntState,
) {
    var playerExpanded: Boolean
        get() = playerExpandedState.value
        set(value) { playerExpandedState.value = value }

    var locateCurrentSongRequest: Int
        get() = locateState.intValue
        set(value) { locateState.intValue = value }

    var homeNavigationIntent by mutableStateOf<HomeNavigationIntent?>(null)

    /** 全屏播放层可见时 overlay 须 MATCH_PARENT；收起时 WRAP_CONTENT 以利触摸穿透。 */
    var overlayFullScreen by mutableStateOf(false)

    var playerBackProgress by mutableStateOf<Float?>(null)

    /** 设置页发起、播放页消费的一次性自定义布局编辑请求。 */
    var customLayoutEditRequested by mutableStateOf(false)

    private var navController: NavHostController? = null

    fun attachNavController(controller: NavHostController) {
        navController = controller
    }

    fun detachNavController(controller: NavHostController) {
        if (navController === controller) {
            navController = null
        }
    }

    fun navigate(route: String) {
        navController?.navigate(route)
    }

    fun navigateSongDetail(songId: String) {
        navController?.navigate(Routes.songDetail(songId))
    }

    fun popBackStack() {
        navController?.popBackStack()
    }

    fun popBackStackHome() {
        navController?.popBackStack(Routes.Home, inclusive = false)
    }
}

internal fun playerOverlayOwnsBack(
    playerExpanded: Boolean,
    overlayFullScreen: Boolean,
): Boolean = playerExpanded || overlayFullScreen

@Composable
fun rememberAppNavigationCoordinator(): AppNavigationCoordinator {
    val playerExpanded = rememberSaveable { mutableStateOf(false) }
    val locateRequest = rememberSaveable { mutableIntStateOf(0) }
    return remember(playerExpanded, locateRequest) {
        AppNavigationCoordinator(playerExpanded, locateRequest)
    }
}
