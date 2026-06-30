package com.mica.music.ui.theme

import android.content.Context
import android.app.WallpaperManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.AppAccentColor

fun resolveAppAccent(
    accent: AppAccentColor,
    customAccentColorArgb: Int,
    context: Context,
    isDark: Boolean,
): Color =
    when (accent) {
        AppAccentColor.DYNAMIC -> systemDynamicAccent(context, isDark)
        AppAccentColor.CUSTOM -> Color(customAccentColorArgb)
        else -> accent.resolve(isDark)
    }

private fun systemDynamicAccent(context: Context, isDark: Boolean): Color {
    return systemDynamicColorScheme(context, isDark)?.primary ?: HifiPalette.PurplePrimary
}

private fun systemDynamicColorScheme(context: Context, isDark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return if (isDark) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
}

@Composable
private fun rememberWallpaperColorVersion(enabled: Boolean): Int {
    val context = LocalContext.current.applicationContext
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(context, enabled) {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onDispose {}
        } else {
            val manager = WallpaperManager.getInstance(context)
            val listener = WallpaperManager.OnColorsChangedListener { _, _ ->
                version += 1
            }
            manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
            onDispose {
                manager.removeOnColorsChangedListener(listener)
            }
        }
    }
    return version
}

@Composable
fun rememberAppAccent(
    accent: AppAccentColor,
    customAccentColorArgb: Int,
    darkTheme: Boolean,
): Color {
    val context = LocalContext.current
    val wallpaperColorVersion = rememberWallpaperColorVersion(
        accent == AppAccentColor.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    )
    return remember(accent, customAccentColorArgb, darkTheme, context, wallpaperColorVersion) {
        resolveAppAccent(accent, customAccentColorArgb, context, darkTheme)
    }
}
