package com.mica.music.ui.components

import com.mica.music.data.LyricCue
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsBilingualDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrowBarSoftKaraokeLyricTest {

    @Test
    fun panStaysZeroWhenContentFits() {
        assertEquals(
            0f,
            narrowBarLyricPanOffsetPx(
                lineStartMs = 0,
                lineEndMs = 1_000,
                positionMs = 500,
                contentWidthPx = 200f,
                viewportWidthPx = 300f,
            ),
            0.0001f,
        )
    }

    @Test
    fun panMovesFromStartToEndWithEaseOut() {
        val start = narrowBarLyricPanOffsetPx(0, 1_000, 0, 1_000f, 400f)
        val mid = narrowBarLyricPanOffsetPx(0, 1_000, 500, 1_000f, 400f)
        val end = narrowBarLyricPanOffsetPx(0, 1_000, 1_000, 1_000f, 400f)

        assertEquals(0f, start, 0.0001f)
        assertTrue(mid < 0f)
        assertTrue(mid > end)
        assertEquals(-600f, end, 0.0001f)
        // easeOut: at 50% time, more than 50% travel
        assertTrue(kotlin.math.abs(mid) > 300f)
    }

    @Test
    fun softFillUsesOriginalRowOnlyAndAdvancesWithCues() {
        val line = LyricLine(
            timeMs = 0,
            text = "hello\n你好",
            cues = listOf(
                LyricCue(timeMs = 0, text = "hel"),
                LyricCue(timeMs = 500, text = "lo"),
            ),
        )
        val row = LyricDisplayRows.rowsForBilingualDisplayMode(
            text = line.text,
            mode = LyricsBilingualDisplayMode.ORIGINAL,
        ).single()
        val ranges = narrowBarCueRanges(line)

        val early = narrowBarSoftFillFraction(line, row, ranges, positionMs = 100, nextLineTimeMs = 2_000)
        val late = narrowBarSoftFillFraction(line, row, ranges, positionMs = 800, nextLineTimeMs = 2_000)

        assertTrue(early in 0f..1f)
        assertTrue(late > early)
        assertTrue(late <= 1f)
    }
}
