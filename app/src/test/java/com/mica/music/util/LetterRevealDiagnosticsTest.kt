package com.mica.music.util

import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import org.junit.Assert.assertEquals
import org.junit.Test

class LetterRevealDiagnosticsTest {
    @Test
    fun buildUniformRevealMsDistributesAcrossLineDuration() {
        val times = LetterRevealDiagnostics.buildUniformRevealMs(
            lineStartMs = 1_000,
            lineEndMs = 5_000,
            graphemeCount = 4,
        )

        assertEquals(1_000, times[0])
        assertEquals(2_000, times[1])
        assertEquals(3_000, times[2])
        assertEquals(4_000, times[3])
    }

    @Test
    fun onGlyphShownLogsOncePerGlyphPerSession() {
        LetterRevealDiagnostics.resetSession("test-session")
        repeat(3) {
            LetterRevealDiagnostics.onGlyphShown(
                lineIndex = 0,
                glyphIndex = 0,
                char = "猜",
                scheduledMs = 1_000,
                frameMs = 1_020,
                anchorMs = 1_000,
                inkProgress = 0.1f,
                isTranslation = false,
            )
        }
        LetterRevealDiagnostics.resetSession("other-session")
        LetterRevealDiagnostics.onGlyphShown(
            lineIndex = 0,
            glyphIndex = 0,
            char = "猜",
            scheduledMs = 1_000,
            frameMs = 1_020,
            anchorMs = 1_000,
            inkProgress = 0.1f,
            isTranslation = false,
        )
    }

    @Test
    fun logLineScheduleIncludesLrcTokensWhenWordTimed() {
        LetterRevealDiagnostics.resetSession("schedule-session")
        val line = LyricLineNode(
            id = "0-21973",
            startMs = 21_973,
            endMs = 23_000,
            parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "猜不透")),
            tokens = listOf(
                LyricToken("猜", 21_973),
                LyricToken("不", 22_229),
                LyricToken("透", 22_477),
            ),
        )

        LetterRevealDiagnostics.logLineScheduleIfNeeded(
            lineIndex = 0,
            line = line,
            displayText = "猜不透",
            fallbackEndMs = 23_000,
        )
        LetterRevealDiagnostics.logLineScheduleIfNeeded(
            lineIndex = 0,
            line = line,
            displayText = "猜不透",
            fallbackEndMs = 23_000,
        )
    }
}
