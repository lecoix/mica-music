package com.mica.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalHifiColors = staticCompositionLocalOf { LightHifiColors }
val LocalHifiTypography = staticCompositionLocalOf { HifiTypography() }

@Composable
fun MicaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val hifiColors = if (darkTheme) DarkHifiColors else LightHifiColors
    val typography = HifiTypography()

    val materialColorScheme = if (darkTheme) {
        darkColorScheme(
            primary = hifiColors.accent,
            onPrimary = HifiPalette.NeutralWhite,
            background = HifiPalette.MicaAuroraStart,
            onBackground = hifiColors.textPrimary,
            surface = HifiPalette.MicaFogDarkStart,
            onSurface = hifiColors.textPrimary,
            onSurfaceVariant = hifiColors.textSecondary,
            outline = hifiColors.divider,
        )
    } else {
        lightColorScheme(
            primary = hifiColors.accent,
            onPrimary = HifiPalette.NeutralWhite,
            background = HifiPalette.MicaDawnStart,
            onBackground = hifiColors.textPrimary,
            surface = HifiPalette.NeutralWhite,
            onSurface = hifiColors.textPrimary,
            onSurfaceVariant = hifiColors.textSecondary,
            outline = hifiColors.divider,
        )
    }

    CompositionLocalProvider(
        LocalHifiColors provides hifiColors,
        LocalHifiTypography provides typography,
        LocalContentColor provides hifiColors.textPrimary,
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            shapes = HifiShapes,
            content = content,
        )
    }
}

object MicaTheme {
    val colors: HifiColors
        @Composable get() = LocalHifiColors.current
    val typography: HifiTypography
        @Composable get() = LocalHifiTypography.current
}

/** 播放页等深色背景上的文字/图标（专辑取色背景） */
object PlayerOnDark {
    val primary: Color @Composable get() = HifiPalette.NeutralWhite
    val secondary: Color @Composable get() = HifiPalette.NeutralWhite.copy(alpha = 0.7f)
    val tertiary: Color @Composable get() = HifiPalette.NeutralWhite.copy(alpha = 0.4f)
}

data class PlayerContentColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
)

/** 封面模糊模式：下半屏控件与文字统一为白色。 */
fun blurredCoverPlayerContentColors(): PlayerContentColors = PlayerContentColors(
    primary = HifiPalette.NeutralWhite,
    secondary = HifiPalette.NeutralWhite.copy(alpha = 0.78f),
    tertiary = HifiPalette.NeutralWhite.copy(alpha = 0.48f),
)

/** 随应用浅色/深色主题切换播放页前景色。 */
@Composable
fun rememberPlayerContentColors(): PlayerContentColors {
    val isDark = MicaTheme.colors.isDark
    val hifi = MicaTheme.colors
    return remember(isDark) {
        if (isDark) {
            PlayerContentColors(
                primary = HifiPalette.NeutralWhite,
                secondary = HifiPalette.NeutralWhite.copy(alpha = 0.7f),
                tertiary = HifiPalette.NeutralWhite.copy(alpha = 0.4f),
            )
        } else {
            PlayerContentColors(
                primary = hifi.textPrimary,
                secondary = hifi.textSecondary,
                tertiary = hifi.textTertiary,
            )
        }
    }
}
