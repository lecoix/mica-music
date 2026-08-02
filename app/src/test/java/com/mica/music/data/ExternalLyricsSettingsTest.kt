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
}
