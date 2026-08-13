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
 * - 否则取 [WindowInsets.statusBars] 与固定高度的较大值，避免状态栏显示动画刚开始时
 *   从固定高度跳到接近 0 的动画 inset，造成内容先上移再下移。
 */
@Composable
fun homeStatusBarTopPadding(hideStatusBar: Boolean = false): Dp {
    val fixedHeight = rememberFixedStatusBarHeight()
    if (hideStatusBar) {
        return fixedHeight
    }
    val insetTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return stableStatusBarTopPadding(insetTop, fixedHeight)
}

internal fun stableStatusBarTopPadding(insetTop: Dp, fixedHeight: Dp): Dp =
    maxOf(insetTop, fixedHeight)

@Composable
private fun rememberFixedStatusBarHeight(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density) {
        with(density) { StatusBarController.statusBarHeightDp(context).dp }
    }
}
