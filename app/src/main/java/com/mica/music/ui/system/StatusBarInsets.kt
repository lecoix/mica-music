package com.mica.music.ui.system

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 主页/设置顶栏顶部间距：在 edge-to-edge 下把顶栏放在状态栏下方。
 *
 * - [hideStatusBar] 为 true 时始终用固定 [status_bar_height]，避免切回 App 时 inset 从有到无导致布局跳动。
 * - 否则优先 [WindowInsets.statusBars]；inset 为 0 时用系统 status_bar_height 兜底。
 */
@Composable
fun homeStatusBarTopPadding(hideStatusBar: Boolean = false): Dp {
    if (hideStatusBar) {
        return rememberFixedStatusBarHeight()
    }
    val insetTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    if (insetTop > 0.dp) return insetTop
    return rememberFixedStatusBarHeight()
}

@Composable
private fun rememberFixedStatusBarHeight(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density) {
        with(density) { StatusBarController.statusBarHeightDp(context).dp }
    }
}
