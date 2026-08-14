package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLyricsSettingsTest {
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
