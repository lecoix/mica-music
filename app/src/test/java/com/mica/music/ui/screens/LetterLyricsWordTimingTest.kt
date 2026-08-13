package com.mica.music.ui.screens

import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsSync
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterLyricsWordTimingTest {
    @Test
    fun wordTimedLineUsesTokenStartTimesForEachGrapheme() {
        val line = LyricLineNode(
            id = "1",
            startMs = 21_973,
            endMs = 23_000,
            parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "猜不透")),
            tokens = listOf(
                LyricToken("猜", 21_973),
                LyricToken("不", 22_229),
                LyricToken("透", 22_477),
            ),
        )

        val schedule = buildLetterGraphemeRevealMs(
            line = line,
            displayText = "猜不透",
            tokens = line.tokens,
            fallbackEndMs = 23_000,
        )

        assertArrayEquals(intArrayOf(21_973, 22_229, 22_477), schedule)
    }

    @Test
    fun multiGraphemeTokenSubdividesWithinTokenDuration() {
        val line = LyricLineNode(
            id = "0",
            startMs = 0,
            endMs = 1_000,
            parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "Girl")),
            tokens = listOf(
                LyricToken("Gi", 0, endMs = 200),
                LyricToken("rl", 200, endMs = 400),
            ),
        )

        val schedule = buildLetterGraphemeRevealMs(
            line = line,
            displayText = "Girl",
            tokens = line.tokens,
            fallbackEndMs = 1_000,
        )

        assertEquals(4, schedule.size)
        assertEquals(0, schedule[0])
        assertEquals(100, schedule[1])
        assertEquals(200, schedule[2])
        assertEquals(300, schedule[3])
    }

    @Test
    fun lowCoverageFallsBackToUniformDistribution() {
        val line = LyricLineNode(
            id = "1",
            startMs = 1_000,
            endMs = 5_000,
            parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "你好世界")),
            tokens = listOf(
                LyricToken("你", 1_000),
                LyricToken("好", 1_500),
            ),
        )

        val schedule = buildLetterGraphemeRevealMs(
            line = line,
            displayText = "你好世界",
            tokens = line.tokens,
            fallbackEndMs = 5_000,
        )

        assertEquals(4, schedule.size)
        assertEquals(1_000, schedule[0])
        assertEquals(2_000, schedule[1])
        assertEquals(3_000, schedule[2])
        assertEquals(4_000, schedule[3])
    }

    @Test
    fun visibleCountUsesExactPositionAndWordSchedule() {
        val revealMs = intArrayOf(1_000, 1_350, 1_600)

        assertEquals(
            0,
            letterColumnVisibleCount(
                columnGraphemeCount = 3,
                graphemeRevealMs = revealMs,
                lineIndex = 0,
                activeLineIndex = 0,
                framePositionMs = 800,
            ),
        )
        assertEquals(
            1,
            letterColumnVisibleCount(
                columnGraphemeCount = 3,
                graphemeRevealMs = revealMs,
                lineIndex = 0,
                activeLineIndex = 0,
                framePositionMs = 1_000,
            ),
        )
        assertEquals(
            2,
            letterColumnVisibleCount(
                columnGraphemeCount = 3,
                graphemeRevealMs = revealMs,
                lineIndex = 0,
                activeLineIndex = 0,
                framePositionMs = 1_350,
            ),
        )
        assertEquals(
            3,
            letterColumnVisibleCount(
                columnGraphemeCount = 3,
                graphemeRevealMs = revealMs,
                lineIndex = 0,
                activeLineIndex = 0,
                framePositionMs = 1_600,
            ),
        )
    }

    @Test
    fun lineRevealProgressUsesWordScheduleWhenPresent() {
        val schedule = intArrayOf(1_000, 1_350, 1_600)

        assertEquals(
            0f,
            letterLineRevealProgress(
                primarySchedule = schedule,
                framePositionMs = 800,
                lineStartMs = 1_000,
                lineEndMs = 2_000,
            ),
            0.0001f,
        )
        assertEquals(
            2f / 3f,
            letterLineRevealProgress(
                primarySchedule = schedule,
                framePositionMs = 1_350,
                lineStartMs = 1_000,
                lineEndMs = 2_000,
            ),
            0.0001f,
        )
        assertEquals(
            1f,
            letterLineRevealProgress(
                primarySchedule = schedule,
                framePositionMs = 1_600,
                lineStartMs = 1_000,
                lineEndMs = 2_000,
            ),
            0.0001f,
        )
    }

    @Test
    fun lyricsSyncDetectsWordTimedTokens() {
        val tokens = listOf(
            LyricToken("你", 1_000),
            LyricToken("好", 1_500),
        )
        assertTrue(LyricsSync.isWordTimedTokens(tokens))
    }
}
