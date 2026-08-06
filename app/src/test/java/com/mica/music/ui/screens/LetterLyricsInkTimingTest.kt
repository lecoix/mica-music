package com.mica.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterLyricsInkTimingTest {
    @Test
    fun `ink progress depends only on elapsed time from this glyph`() {
        val revealMs = 1_000
        val syncTimeMs = revealMs

        assertEquals(0f, letterInkSettleProgress(syncTimeMs, revealMs, true), 0.0001f)
        assertEquals(0.5f, letterInkSettleProgress(syncTimeMs + 230, revealMs, true), 0.0001f)
        assertEquals(1f, letterInkSettleProgress(syncTimeMs + 460, revealMs, true), 0.0001f)
        assertEquals(1f, letterInkSettleProgress(syncTimeMs + 1_000, revealMs, true), 0.0001f)
    }

    @Test
    fun currentLineGlyphInkStartsAtFirstDrawSync() {
        LetterGlyphInkFloors.resetSession("test-session")
        val inkRevealMs = LetterGlyphInkFloors.inkRevealMs(
            sessionKey = "test-session",
            lineIndex = 1,
            glyphIndex = 0,
            scheduledMs = 5_935,
            syncTimeMs = 6_468,
            isCurrentLine = true,
        )

        assertEquals(6_468, inkRevealMs)
        assertEquals(0f, letterInkSettleProgress(6_468, inkRevealMs, true), 0.0001f)
        assertEquals(
            6_468,
            LetterGlyphInkFloors.inkRevealMs(
                sessionKey = "test-session",
                lineIndex = 1,
                glyphIndex = 0,
                scheduledMs = 5_935,
                syncTimeMs = 6_900,
                isCurrentLine = true,
            ),
        )
    }

    @Test
    fun pastLineGlyphKeepsScheduledInkTime() {
        LetterGlyphInkFloors.resetSession("past-line")
        assertEquals(
            5_935,
            LetterGlyphInkFloors.inkRevealMs(
                sessionKey = "past-line",
                lineIndex = 0,
                glyphIndex = 0,
                scheduledMs = 5_935,
                syncTimeMs = 6_468,
                isCurrentLine = false,
            ),
        )
    }

    @Test
    fun eachCurrentLineGlyphGetsItsOwnInkFloor() {
        LetterGlyphInkFloors.resetSession("multi-glyph")
        val first = LetterGlyphInkFloors.inkRevealMs(
            sessionKey = "multi-glyph",
            lineIndex = 18,
            glyphIndex = 0,
            scheduledMs = 60_028,
            syncTimeMs = 60_500,
            isCurrentLine = true,
        )
        val last = LetterGlyphInkFloors.inkRevealMs(
            sessionKey = "multi-glyph",
            lineIndex = 18,
            glyphIndex = 2,
            scheduledMs = 60_868,
            syncTimeMs = 60_900,
            isCurrentLine = true,
        )

        assertEquals(60_500, first)
        assertEquals(60_900, last)
        assertEquals(0f, letterInkSettleProgress(60_500, first, true), 0.0001f)
        assertEquals(0f, letterInkSettleProgress(60_900, last, true), 0.0001f)
    }

    @Test
    fun `disabled motion completes the glyph immediately`() {
        assertEquals(1f, letterInkSettleProgress(1_000, 1_000, false), 0.0001f)
    }
}
