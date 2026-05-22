package com.mica.music.ui.system

import android.content.Context
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mica.music.data.AppPreferences
import com.mica.music.data.AppThemeMode

/**
 * 统一控制系统状态栏显示/隐藏与图标深浅；供 Activity 生命周期与 Compose 共用。
 */
object StatusBarController {

    fun apply(
        window: Window,
        hideStatusBar: Boolean,
        lightStatusBarIcons: Boolean,
    ) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightStatusBarIcons
        if (hideStatusBar) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    fun applyFromPreferences(context: Context, window: Window) {
        val hide = AppPreferences.hideStatusBar(context)
        val lightIcons = !isDarkTheme(context)
        apply(window, hide, lightIcons)
    }

    fun statusBarHeightDp(context: Context): Float {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val px = if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
        return px / context.resources.displayMetrics.density
    }

    private fun isDarkTheme(context: Context): Boolean = when (AppPreferences.themeMode(context)) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> {
            val night = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            night == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }
}
