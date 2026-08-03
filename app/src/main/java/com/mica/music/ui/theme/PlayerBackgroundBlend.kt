package com.mica.music.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.floor
import kotlin.math.pow

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
        return if (onLight) darkPlayerContentColors() else lightPlayerContentColors()
    }

    /**
     * Stable artwork-seed foreground with semantic color roles.
     *
     * The stored cover color is already the project's stable Palette seed (the scanner
     * favors the lower cover region and muted swatches). We keep one hue family and
     * derive primary/secondary/tertiary by tone and chroma instead of assigning raw
     * multi-cluster colors to UI roles. This follows the Halcyon + Material-role
     * direction without adding a second color-utilities dependency to the app.
     */
    fun dynamicTextColors(
        coverColor: Color,
        surface: Color,
        isDark: Boolean,
    ): PlayerContentColors {
        val seed = artworkHsvSeed(coverColor, isDark)
        val primary = readableRoleColor(
            seed = seed,
            tone = if (isDark) 0.82f else 0.30f,
            saturationScale = 1.00f,
            surface = surface,
            isDark = isDark,
            minimumContrast = 4.5f,
        )
        val secondary = readableRoleColor(
            seed = seed,
            tone = if (isDark) 0.73f else 0.38f,
            saturationScale = 0.82f,
            surface = surface,
            isDark = isDark,
            minimumContrast = 3.0f,
        )
        val tertiary = readableRoleColor(
            seed = seed,
            tone = if (isDark) 0.64f else 0.46f,
            saturationScale = 0.64f,
            surface = surface,
            isDark = isDark,
            minimumContrast = 2.0f,
        )
        return PlayerContentColors(
            primary = primary,
            secondary = secondary.copy(alpha = if (isDark) 0.82f else 0.86f),
            tertiary = tertiary.copy(alpha = if (isDark) 0.58f else 0.64f),
        )
    }

    private data class ArtworkHsvSeed(
        val hue: Float,
        val saturation: Float,
    )

    /** 深色主题压低色度，浅色主题提升色度；中性封面保持中性。 */
    private fun artworkHsvSeed(coverColor: Color, isDark: Boolean): ArtworkHsvSeed {
        val hsv = rgbToHsv(coverColor)
        if (hsv[1] < 0.08f) return ArtworkHsvSeed(hue = hsv[0], saturation = 0f)

        val saturation = if (isDark) {
            (hsv[1] * 0.72f).coerceIn(0.18f, 0.62f)
        } else {
            (hsv[1] * 1.12f).coerceIn(0.32f, 0.86f)
        }
        return ArtworkHsvSeed(hue = hsv[0], saturation = saturation)
    }

    private fun readableRoleColor(
        seed: ArtworkHsvSeed,
        tone: Float,
        saturationScale: Float,
        surface: Color,
        isDark: Boolean,
        minimumContrast: Float,
    ): Color {
        val hsv = floatArrayOf(
            seed.hue,
            (seed.saturation * saturationScale).coerceIn(0f, 0.90f),
            tone.coerceIn(0.08f, 0.92f),
        )
        val candidate = hsvToColor(hsv[0], hsv[1], hsv[2])
        if (wcagContrastRatio(candidate, surface) >= minimumContrast) return candidate

        val neutral = if (isDark) Color.White else Color(0xFF121217)
        val stops = if (isDark) {
            floatArrayOf(0.12f, 0.24f, 0.38f, 0.54f, 0.72f, 1f)
        } else {
            floatArrayOf(0.10f, 0.22f, 0.36f, 0.50f, 0.66f, 1f)
        }
        return stops
            .asSequence()
            .map { stop -> lerp(candidate, neutral, stop) }
            .firstOrNull { wcagContrastRatio(it, surface) >= minimumContrast }
            ?: neutral
    }

    private fun rgbToHsv(color: Color): FloatArray {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == color.red -> 60f * (((color.green - color.blue) / delta) % 6f)
            max == color.green -> 60f * ((color.blue - color.red) / delta + 2f)
            else -> 60f * ((color.red - color.green) / delta + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val saturation = if (max == 0f) 0f else delta / max
        return floatArrayOf(hue, saturation, max)
    }

    private fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
        val normalizedHue = ((hue % 360f) + 360f) % 360f
        val scaledHue = normalizedHue / 60f
        val sector = floor(scaledHue).toInt()
        val fraction = scaledHue - sector
        val p = value * (1f - saturation)
        val q = value * (1f - saturation * fraction)
        val t = value * (1f - saturation * (1f - fraction))
        val rgb = when (sector) {
            0 -> floatArrayOf(value, t, p)
            1 -> floatArrayOf(q, value, p)
            2 -> floatArrayOf(p, value, t)
            3 -> floatArrayOf(p, q, value)
            4 -> floatArrayOf(t, p, value)
            else -> floatArrayOf(value, p, q)
        }
        return Color(rgb[0], rgb[1], rgb[2], 1f)
    }

    internal fun wcagContrastRatio(foreground: Color, background: Color): Float {
        val foregroundLuminance = wcagRelativeLuminance(foreground)
        val backgroundLuminance = wcagRelativeLuminance(background)
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun wcagRelativeLuminance(color: Color): Float {
        fun linearize(channel: Float): Float =
            if (channel <= 0.04045f) {
                channel / 12.92f
            } else {
                ((channel + 0.055f) / 1.055f).pow(2.4f)
            }

        return 0.2126f * linearize(color.red) +
            0.7152f * linearize(color.green) +
            0.0722f * linearize(color.blue)
    }

}

internal fun Color.relativeLuminance(): Float =
    0.299f * red + 0.587f * green + 0.114f * blue
