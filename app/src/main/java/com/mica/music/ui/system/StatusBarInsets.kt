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
 * 优先 [WindowInsets.statusBars]；隐藏状态栏导致 inset 为 0 时用系统 status_bar_height 兜底。
 */
@Composable
fun homeStatusBarTopPadding(): Dp {
    val insetTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    if (insetTop > 0.dp) return insetTop

    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density) {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val px = if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
        with(density) { px.toDp().coerceAtLeast(0.dp) }
    }
}
