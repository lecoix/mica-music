package com.mica.music.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.AppAccentColor

fun resolveAppAccent(accent: AppAccentColor, context: Context, isDark: Boolean): Color =
    when (accent) {
        AppAccentColor.DYNAMIC -> systemDynamicAccent(context, isDark)
        else -> accent.resolve(isDark)
    }

private fun systemDynamicAccent(context: Context, isDark: Boolean): Color {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val scheme = if (isDark) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
        return scheme.primary
    }
    return HifiPalette.PurplePrimary
}

@Composable
fun rememberAppAccent(accent: AppAccentColor, darkTheme: Boolean): Color {
    val context = LocalContext.current
    return remember(accent, darkTheme, context) {
        resolveAppAccent(accent, context, darkTheme)
    }
}
