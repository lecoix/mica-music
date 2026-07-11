package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRenderStateTest {

    @Test
    fun renderStateKeepsExistingLeadBasedActiveLineAndExposesTimeline() {
        val state = listOf(
            LyricLine(1_000, "one", endTimeMs = 1_500),
            LyricLine(3_000, "two", endTimeMs = 4_000),
        ).renderStateAt(positionMs = 2_000)

        assertTrue(state.hasTimedLyrics)
        assertEquals(0, state.activeLineIndex)
        assertTrue(state.timeline.phase is LyricsTimelinePhase.Gap)
    }

    @Test
    fun renderStateMarksUntimedLyricsWithoutAnActiveLine() {
        val state = listOf(LyricLine(0, "plain text")).renderStateAt(positionMs = 500)

        assertEquals(-1, state.activeLineIndex)
        assertTrue(!state.hasTimedLyrics)
    }
}
