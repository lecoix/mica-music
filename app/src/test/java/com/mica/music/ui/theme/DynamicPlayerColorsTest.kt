package com.mica.music.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicPlayerColorsTest {

    @Test
    fun differentArtworkHuesProduceDifferentReadableForegrounds() {
        val darkSurface = Color(0xFF17151F)

        val violet = PlayerBackgroundBlend.dynamicTextColors(
            coverColor = Color(0xFF6E4CA8),
            surface = darkSurface,
            isDark = true,
        )
        val amber = PlayerBackgroundBlend.dynamicTextColors(
            coverColor = Color(0xFFB8752C),
            surface = darkSurface,
            isDark = true,
        )

        assertNotEquals(violet.primary, amber.primary)
    }

    @Test
    fun primaryForegroundMaintainsReadableContrastOnDarkAndLightSurfaces() {
        val darkSurface = Color(0xFF17151F)
        val lightSurface = Color(0xFFF3F0F6)
        val darkColors = PlayerBackgroundBlend.dynamicTextColors(
            coverColor = Color(0xFF8D273A),
            surface = darkSurface,
            isDark = true,
        )
        val lightColors = PlayerBackgroundBlend.dynamicTextColors(
            coverColor = Color(0xFF3C8D9A),
            surface = lightSurface,
            isDark = false,
        )

        assertTrue(
            PlayerBackgroundBlend.wcagContrastRatio(darkColors.primary, darkSurface) >= 4.5f,
        )
        assertTrue(
            PlayerBackgroundBlend.wcagContrastRatio(lightColors.primary, lightSurface) >= 4.5f,
        )
    }

    @Test
    fun lightThemeKeepsMoreArtworkColorfulnessThanDarkTheme() {
        val cover = Color(0xFFB13B66)
        val dark = PlayerBackgroundBlend.dynamicTextColors(
            coverColor = cover,
            surface = Color(0xFF17151F),
            isDark = true,
        )
        val light = PlayerBackgroundBlend.dynamicTextColors(
            coverColor = cover,
            surface = Color(0xFFF3F0F6),
            isDark = false,
        )

        assertTrue(saturation(light.primary) > saturation(dark.primary))
    }

    @Test
    fun semanticRolesStayInOneArtworkHueFamily() {
        val colors = PlayerBackgroundBlend.dynamicTextColors(
            coverColor = Color(0xFFB13B66),
            surface = Color(0xFFF3F0F6),
            isDark = false,
        )
        val hues = listOf(colors.primary, colors.secondary, colors.tertiary).map(::hue)

        assertEquals(hues[0], hues[1], 1.5f)
        assertEquals(hues[0], hues[2], 1.5f)
        assertNotEquals(colors.primary, colors.secondary)
        assertNotEquals(colors.secondary, colors.tertiary)
    }

    @Test
    fun neutralArtworkKeepsSemanticRolesNeutral() {
        val colors = PlayerBackgroundBlend.dynamicTextColors(
            coverColor = Color(0xFF777777),
            surface = Color(0xFFF3F0F6),
            isDark = false,
        )

        assertTrue(colorfulness(colors.primary) < 0.02f)
        assertTrue(colorfulness(colors.secondary) < 0.02f)
        assertTrue(colorfulness(colors.tertiary) < 0.02f)
    }

    private fun colorfulness(color: Color): Float =
        maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)

    private fun saturation(color: Color): Float {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        return if (max == 0f) 0f else (max - min) / max
    }

    private fun hue(color: Color): Float {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val delta = max - min
        if (delta == 0f) return 0f
        val raw = when {
            max == color.red -> 60f * (((color.green - color.blue) / delta) % 6f)
            max == color.green -> 60f * ((color.blue - color.red) / delta + 2f)
            else -> 60f * ((color.red - color.green) / delta + 4f)
        }
        return if (raw < 0f) raw + 360f else raw
    }
}
