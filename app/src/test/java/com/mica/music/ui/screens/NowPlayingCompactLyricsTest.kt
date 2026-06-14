package com.mica.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingCompactLyricsTest {
    @Test
    fun safeLyricDisplayIndex_clampsPreviousSongIndexToNewLyrics() {
        assertEquals(3, safeLyricDisplayIndex(lyricsSize = 4, displayIndex = 5))
        assertEquals(45, safeLyricDisplayIndex(lyricsSize = 46, displayIndex = 47))
    }

    @Test
    fun safeLyricDisplayIndex_handlesBeforeFirstAndEmptyLyrics() {
        assertEquals(0, safeLyricDisplayIndex(lyricsSize = 4, displayIndex = -1))
        assertNull(safeLyricDisplayIndex(lyricsSize = 0, displayIndex = 5))
    }
}
