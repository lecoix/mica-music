package com.mica.music.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** 播放页背景取色与主题色的混合工具。 */
internal object PlayerBackgroundBlend {

    fun accentuateCover(cover: Color, isDark: Boolean): Color {
        val softened = comfortColor(cover, isDark)
        val factor = if (isDark) 1.20f else 1.12f
        return Color(
            red = (softened.red * factor).coerceIn(0f, 1f),
            green = (softened.green * factor).coerceIn(0f, 1f),
            blue = (softened.blue * factor).coerceIn(0f, 1f),
            alpha = 1f,
        )
    }

    fun blend(cover: Color, theme: Color, themeFraction: Float): Color =
        lerp(cover, theme, themeFraction.coerceIn(0f, 1f))

    /**
     * 降低饱和度与亮度，避免取色过艳、刺眼（扫描与播放页共用）。
     */
    fun comfortColor(cover: Color, isDark: Boolean): Color {
        val lum = cover.relativeLuminance()
        val targetLum = if (isDark) 0.30f else 0.70f
        val lumScale = if (lum > 0.01f) (targetLum / lum).coerceIn(0.70f, 1.30f) else 1f
        var c = Color(
            red = (cover.red * lumScale).coerceIn(0f, 1f),
            green = (cover.green * lumScale).coerceIn(0f, 1f),
            blue = (cover.blue * lumScale).coerceIn(0f, 1f),
        )
        val gray = Color(lum, lum, lum, 1f)
        c = lerp(c, gray, if (isDark) 0.10f else 0.07f)
        return lerp(c, themeNeutral(isDark), if (isDark) 0.03f else 0.02f)
    }

    private fun themeNeutral(isDark: Boolean): Color =
        if (isDark) Color(0.12f, 0.10f, 0.16f) else Color(0.94f, 0.92f, 0.90f)

    fun artworkJunction(accent: Color, theme: Color, isDark: Boolean): Color =
        if (isDark) blend(accent, theme, 0.10f) else blend(accent, theme, 0.30f)

    fun artworkPeak(accent: Color, theme: Color, isDark: Boolean): Color =
        if (isDark) blend(accent, theme, 0.12f) else blend(accent, theme, 0.28f)

    fun artworkMid(accent: Color, theme: Color, isDark: Boolean): Color =
        blend(accent, theme, if (isDark) 0.48f else 0.58f)

    fun artworkHold(accent: Color, theme: Color, isDark: Boolean): Color = artworkPeak(accent, theme, isDark)

    fun readableTextColors(surface: Color): PlayerContentColors {
        val onLight = surface.relativeLuminance() > 0.42f
        return if (onLight) {
            PlayerContentColors(
                primary = Color(0.12f, 0.12f, 0.14f),
                secondary = Color(0.12f, 0.12f, 0.14f, 0.75f),
                tertiary = Color(0.12f, 0.12f, 0.14f, 0.5f),
            )
        } else {
            PlayerContentColors(
                primary = HifiPalette.NeutralWhite,
                secondary = HifiPalette.NeutralWhite.copy(alpha = 0.78f),
                tertiary = HifiPalette.NeutralWhite.copy(alpha = 0.48f),
            )
        }
    }

}

internal fun Color.relativeLuminance(): Float =
    0.299f * red + 0.587f * green + 0.114f * blue
