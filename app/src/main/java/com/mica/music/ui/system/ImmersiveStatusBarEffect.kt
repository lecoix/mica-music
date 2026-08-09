package com.mica.music.ui.system

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** 进入组合时隐藏全部系统栏，离开时恢复；从边缘滑动可临时显示系统栏。 */
@Composable
fun ImmersiveSystemBarsEffect(
    enabled: Boolean,
    restoreStatusBarHidden: Boolean,
) {
    if (!enabled) return

    val view = LocalView.current
    DisposableEffect(view, restoreStatusBarHidden) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            controller.show(WindowInsetsCompat.Type.navigationBars())
            if (restoreStatusBarHidden) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
}
