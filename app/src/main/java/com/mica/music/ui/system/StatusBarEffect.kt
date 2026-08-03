package com.mica.music.ui.system

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView

/**
 * 按偏好隐藏/显示状态栏，并设置状态栏图标深浅（浅色主题用深色图标）。
 * 与 [StatusBarController.applyFromPreferences] 在 Activity 恢复时配合，避免首帧 inset 闪动。
 */
@Composable
fun StatusBarEffect(
    hideStatusBar: Boolean,
    darkStatusBarIcons: Boolean,
    restoreFromPreferencesOnDispose: Boolean = false,
) {
    val view = LocalView.current

    DisposableEffect(view, restoreFromPreferencesOnDispose) {
        if (restoreFromPreferencesOnDispose) {
            onDispose {
                val activity = view.context as Activity
                StatusBarController.applyFromPreferences(activity, activity.window)
            }
        } else {
            onDispose {}
        }
    }

    SideEffect {
        val window = (view.context as Activity).window
        StatusBarController.apply(window, hideStatusBar, darkStatusBarIcons)
    }
}
