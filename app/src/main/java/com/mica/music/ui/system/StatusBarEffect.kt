package com.mica.music.ui.system

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 按偏好隐藏/显示状态栏，并设置状态栏图标深浅（浅色主题用深色图标）。
 */
@Composable
fun StatusBarEffect(
    hideStatusBar: Boolean,
    darkTheme: Boolean,
) {
    val view = LocalView.current
    val lightStatusBarIcons = !darkTheme

    SideEffect {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = lightStatusBarIcons
    }

    DisposableEffect(view, hideStatusBar) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        if (hideStatusBar) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}
