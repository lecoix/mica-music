package com.mica.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

enum class MicaPreset { Dawn, Dusk, Midnight, Aurora, Fog }

/** 按浅色/深色主题返回云母渐变起止色 */
fun MicaPreset.gradientColors(isDark: Boolean): Pair<Color, Color> = when (this) {
    MicaPreset.Dawn -> if (isDark) {
        HifiPalette.MicaDawnDarkStart to HifiPalette.MicaDawnDarkEnd
    } else {
        HifiPalette.MicaDawnStart to HifiPalette.MicaDawnEnd
    }
    MicaPreset.Dusk -> if (isDark) {
        HifiPalette.MicaAuroraStart to HifiPalette.MicaAuroraEnd
    } else {
        HifiPalette.MicaDuskStart to HifiPalette.MicaDuskEnd
    }
    MicaPreset.Midnight -> HifiPalette.MicaMidnightStart to HifiPalette.MicaMidnightEnd
    MicaPreset.Aurora -> HifiPalette.MicaAuroraStart to HifiPalette.MicaAuroraEnd
    MicaPreset.Fog -> if (isDark) {
        HifiPalette.MicaFogDarkStart to HifiPalette.MicaFogDarkEnd
    } else {
        HifiPalette.MicaFogStart to HifiPalette.MicaFogEnd
    }
}

@Deprecated("Use gradientColors(isDark) or micaBackground()", ReplaceWith("gradientColors(isDark)"))
fun MicaPreset.colors(): Pair<Color, Color> = gradientColors(isDark = false)

/** 垂直渐变在屏幕底边的主题色（终点色，与 [Modifier.micaBackground] 底边一致）。 */
fun MicaPreset.bottomThemeColor(isDark: Boolean): Color =
    gradientColors(isDark).second

/** 浮岛卡片底边描边（略深于底面，半透明）。 */
fun micaFloatingCardBottomEdge(bottomSurface: Color, isDark: Boolean): Color {
    val base = if (isDark) {
        lerp(bottomSurface, Color.Black, 0.32f)
    } else {
        lerp(bottomSurface, Color.Black, 0.12f)
    }
    return base.copy(alpha = base.alpha * 0.55f)
}

@Composable
fun Modifier.micaBackground(preset: MicaPreset): Modifier {
    val (start, end) = preset.gradientColors(MicaTheme.colors.isDark)
    return this.background(Brush.verticalGradient(listOf(start, end)))
}

fun Modifier.micaFromArtwork(dominantColor: Color, vibrantColor: Color): Modifier {
    return this.background(
        Brush.verticalGradient(
            listOf(dominantColor.copy(alpha = 0.95f), vibrantColor.copy(alpha = 0.85f)),
        ),
    )
}
