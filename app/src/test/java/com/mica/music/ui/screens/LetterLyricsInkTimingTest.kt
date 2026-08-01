package com.mica.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterLyricsInkTimingTest {
    @Test
    fun `ink progress depends only on elapsed time from this glyph`() {
        val revealMs = 1_000

        assertEquals(0f, letterInkSettleProgress(1_000, revealMs, true), 0.0001f)
        assertEquals(0.5f, letterInkSettleProgress(1_230, revealMs, true), 0.0001f)
        assertEquals(1f, letterInkSettleProgress(1_460, revealMs, true), 0.0001f)
        assertEquals(1f, letterInkSettleProgress(2_000, revealMs, true), 0.0001f)
    }

    @Test
    fun `disabled motion completes the glyph immediately`() {
        assertEquals(1f, letterInkSettleProgress(1_000, 1_000, false), 0.0001f)
    }
}
