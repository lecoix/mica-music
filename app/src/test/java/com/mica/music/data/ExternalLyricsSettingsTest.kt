package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLyricsSettingsTest {
    @Test
    fun unfilledColorFollowsFirstFillColorUnlessCustomized() {
        val fill = 0xFF66CCFF.toInt()
        val custom = 0xFFFF80B5.toInt()
        val style = ExternalLyricsStyle(colorsArgb = listOf(fill))

        assertEquals(fill, style.resolvedUnfilledColorArgb)
        assertEquals(custom, style.copy(unfilledColorArgb = custom).resolvedUnfilledColorArgb)
        assertEquals(fill, style.copy(unfilledColorArgb = custom).copy(unfilledColorArgb = null).resolvedUnfilledColorArgb)
        assertEquals(DEFAULT_EXTERNAL_LYRICS_COLORS.first(), ExternalLyricsStyle(colorsArgb = emptyList()).resolvedUnfilledColorArgb)
    }

    @Test
    fun colorsAreBoundedToFourAndEmptyInputUsesDefaults() {
        assertEquals(MAX_EXTERNAL_LYRICS_COLORS, normalizeExternalLyricsColors((0..9).toList()).size)
        assertEquals(DEFAULT_EXTERNAL_LYRICS_COLORS, normalizeExternalLyricsColors(emptyList()))
    }

    @Test
    fun invalidStoredModesFallBackToSafeDefaults() {
        assertEquals(ExternalLyricsVisibilityMode.DEFAULT, ExternalLyricsVisibilityMode.fromStorage("bad"))
        assertEquals(ExternalLyricsColorMode.SINGLE, ExternalLyricsColorMode.fromStorage("bad"))
        assertTrue(ExternalLyricsStyle().normalizedColors.isNotEmpty())
    }

    @Test
    fun effectStrengthsPreserveCurrentDefaultsAndClampForRendering() {
        val defaults = ExternalLyricsStyle()
        assertEquals(1f, defaults.opacityFraction)
        assertEquals(1f, defaults.shadowStrengthFraction)
        assertEquals(0f, defaults.glowStrengthFraction)

        val invalid = ExternalLyricsStyle(
            opacityPercent = -20,
            shadowStrengthPercent = 140,
            glowStrengthPercent = 250,
        )
        assertEquals(0f, invalid.opacityFraction)
        assertEquals(1f, invalid.shadowStrengthFraction)
        assertEquals(1f, invalid.glowStrengthFraction)
    }
}
