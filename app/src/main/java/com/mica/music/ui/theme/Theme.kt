package com.mica.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.mica.music.data.AppAccentColor
import com.mica.music.data.CoverDisplayMode

val LocalHifiColors = staticCompositionLocalOf { LightHifiColors }
val LocalHifiTypography = staticCompositionLocalOf { HifiTypography() }
val LocalMicaBackgroundPreset = staticCompositionLocalOf { MicaPreset.Dawn }
val LocalCoverDisplayMode = staticCompositionLocalOf { CoverDisplayMode.CROP_FILL }

@Composable
fun MicaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentColor: AppAccentColor = AppAccentColor.PURPLE,
    micaBackgroundPreset: MicaPreset = MicaPreset.Dawn,
    coverDisplayMode: CoverDisplayMode = CoverDisplayMode.CROP_FILL,
    content: @Composable () -> Unit,
) {
    val accent = rememberAppAccent(accentColor, darkTheme)
    val baseColors = if (darkTheme) DarkHifiColors else LightHifiColors
    val targetColors = baseColors.copy(accent = accent)
    val hifiColors = rememberAnimatedHifiColors(targetColors)
    val typography = HifiTypography()
    val (micaStart, _) = micaBackgroundPreset.gradientColors(darkTheme)
    val motionEnabled = rememberMicaMotionEnabled()
    val animatedPrimary = animateColorAsState(
        hifiColors.accent,
        animationSpec = if (motionEnabled) {
            androidx.compose.animation.core.tween(
                com.mica.music.ui.motion.MicaMotion.DurationMediumMs,
                easing = com.mica.music.ui.motion.MicaMotion.Easing,
            )
        } else {
            androidx.compose.animation.core.tween(0)
        },
        label = "materialPrimary",
    ).value
    val animatedBackground = animateColorAsState(
        micaStart,
        animationSpec = if (motionEnabled) {
            androidx.compose.animation.core.tween(
                com.mica.music.ui.motion.MicaMotion.DurationMediumMs,
                easing = com.mica.music.ui.motion.MicaMotion.Easing,
            )
        } else {
            androidx.compose.animation.core.tween(0)
        },
        label = "materialBackground",
    ).value

    val materialColorScheme = if (darkTheme) {
        darkColorScheme(
            primary = animatedPrimary,
            onPrimary = HifiPalette.NeutralWhite,
            background = animatedBackground,
            onBackground = hifiColors.textPrimary,
            surface = hifiColors.surfaceCard,
            onSurface = hifiColors.textPrimary,
            onSurfaceVariant = hifiColors.textSecondary,
            outline = hifiColors.divider,
        )
    } else {
        lightColorScheme(
            primary = animatedPrimary,
            onPrimary = HifiPalette.NeutralWhite,
            background = animatedBackground,
            onBackground = hifiColors.textPrimary,
            surface = hifiColors.surfaceCard,
            onSurface = hifiColors.textPrimary,
            onSurfaceVariant = hifiColors.textSecondary,
            outline = hifiColors.divider,
        )
    }

    CompositionLocalProvider(
        LocalHifiColors provides hifiColors,
        LocalHifiTypography provides typography,
        LocalMicaBackgroundPreset provides micaBackgroundPreset,
        LocalCoverDisplayMode provides coverDisplayMode,
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
