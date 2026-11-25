package com.mica.music.ui.components

import com.mica.music.data.LyricCue
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsSession
import com.mica.music.data.toLegacyLyricLines
import com.mica.music.data.toLyricsDocumentCompat
import com.mica.music.lyrics.LyricsDisplayOptions
import com.mica.music.media.buildExternalLyricsLine
import com.mica.music.ui.overlay.externalLyricsFillFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrowBarSoftKaraokeLyricTest {

    @Test
    fun roleTokenEndsRemainConsistentAcrossClassicNarrowAndExternalSurfacesAfterSeek() {
        val roles = listOf(LyricTextRole.ORIGINAL, LyricTextRole.READING, LyricTextRole.TRANSLATION)
        val tokens = roles.flatMap { role ->
            listOf(LyricToken("hel", 1000, 1200, role), LyricToken("lo", 2000, 2200, role))
        }
        val document = LyricsDocument(
            lines = listOf(
                LyricLineNode("roles", 1000, 5000, roles.map { LyricTextPart(it, "hello") }, tokens),
            ),
        )
        val session = LyricsSession(document)
        val legacy = session.lyrics.single()
        val external = buildExternalLyricsLine(
            document, session.lyrics, 0,
            LyricsDisplayOptions(splitEnabled = true, bilingualMode = LyricsBilingualDisplayMode.ALL),
        )!!
        for (role in roles) {
            val selected = LyricDisplayRows.tokensForRole(tokens, role)
            val line = roleTokenCueLine(legacy, "hello", selected)
            assertEquals(listOf(1200, 2200), line.cues.map { it.endTimeMs })
            assertEquals(listOf(1200, 2200), listOf(line).toLyricsDocumentCompat().lines.single().tokens.map { it.endMs })
            val row = LyricDisplayRows.DisplayRow("hello", 0, 5, 0, role)
            val externalText = external.rows.first { it.role == role }.text
            // Repeated timestamps model pause; decreasing timestamps model seeking backwards.
            for (playbackPosition in listOf(400, 600, 700, 1000, 1600, 1700, 1800, 1800, 600)) {
                val position = session.snapshotAt(playbackPosition, effectiveOffsetMs = 500).positionMs
                val expected = when {
                    position < 1000 -> 0f
                    position < 1200 -> (position - 1000) / 200f * 0.6f
                    position < 2000 -> 0.6f
                    position < 2200 -> 0.6f + (position - 2000) / 200f * 0.4f
                    else -> 1f
                }
                for (discrete in listOf(false, true)) {
                    val classic = wordSyncedFill(line, row, lyricCueRanges(line), position, 20000, discrete)
                    val discreteExpected = when {
                        position < 1000 -> 0f
                        position < 2000 -> 0.6f
                        else -> 1f
                    }
                    assertEquals(if (discrete) discreteExpected else expected, classic.fillFraction, 0.0001f)
                    if (position in 1200..1999 || position >= 2200) assertEquals(1f, classic.activeProgress, 0f)
                }
                assertEquals(expected, narrowBarSoftFillFraction(line, row, narrowBarCueRanges(line), position, 20000), 0.0001f)
                assertEquals(expected, externalLyricsFillFraction(externalText, external, position), 0.0001f)
            }
        }
    }

    @Test
    fun missingInvalidAndOverflowingWordEndsUseValidBoundaries() {
        fun progress(cues: List<LyricCue>, lineEnd: Int?, next: Int?, position: Int): Float {
            val line = LyricLine(cues.first().timeMs, cues.joinToString("") { it.text }, cues, lineEnd)
            val row = LyricDisplayRows.DisplayRow(line.text, 0, line.text.length, 0)
            return wordSyncedFill(line, row, lyricCueRanges(line), position, next, false).fillFraction
        }
        // Invalid own end: the earlier valid line end wins over the next word start.
        for (invalid in listOf(null, 900, 1000)) {
            assertEquals(0.25f, progress(listOf(LyricCue(1000, "A", invalid), LyricCue(2000, "B")), 1400, 20000, 1200), 0.0001f)
        }
        // Valid own end remains authoritative even when it overlaps the next word start.
        assertEquals(0.1f, progress(listOf(LyricCue(1000, "A", 2000), LyricCue(1500, "B")), 1300, 20000, 1200), 0.0001f)
        assertEquals(0.5f, progress(listOf(LyricCue(1000, "A")), null, 2000, 1500), 0.0001f)
        assertEquals(0.5f, progress(listOf(LyricCue(1000, "A", 900)), 900, 900, 2250), 0.0001f)
        assertEquals(0.04f, progress(listOf(LyricCue(Int.MAX_VALUE - 100, "A")), null, null, Int.MAX_VALUE), 0.0001f)
        assertEquals(0f, progress(listOf(LyricCue(1000, "A")), null, null, Int.MIN_VALUE), 0f)
    }

    @Test
    fun sourceWordEndsStopFillDuringGapsAndBeforeLongInterlude() {
        val document = LyricsDocument(
            lines = listOf(
                LyricLineNode(
                    "gap", 1000, 5000,
                    listOf(LyricTextPart(LyricTextRole.ORIGINAL, "hello")),
                    listOf(LyricToken("hel", 1000, 1200), LyricToken("lo", 2000, 2200)),
                ),
            ),
        )
        val line = document.toLegacyLyricLines().single()
        val row = LyricDisplayRows.rowsForBilingualDisplayMode(line.text, mode = LyricsBilingualDisplayMode.ORIGINAL).single()
        val ranges = narrowBarCueRanges(line)
        assertEquals(0.6f, narrowBarSoftFillFraction(line, row, ranges, 1500, 20000), 0.0001f)
        assertEquals(1f, narrowBarSoftFillFraction(line, row, ranges, 2300, 20000), 0.0001f)
        assertEquals(1f, narrowBarSoftFillFraction(line, row, ranges, 2300, null), 0.0001f)
    }

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
        val rows = LyricDisplayRows.rowsForBilingualDisplayMode(
            text = line.text,
            mode = LyricsBilingualDisplayMode.ORIGINAL,
        )
        assertEquals(listOf("hello", "你好"), rows.map { it.text })
        val row = rows.first()
        val ranges = narrowBarCueRanges(line)

        val early = narrowBarSoftFillFraction(line, row, ranges, positionMs = 100, nextLineTimeMs = 2_000)
        val late = narrowBarSoftFillFraction(line, row, ranges, positionMs = 800, nextLineTimeMs = 2_000)
        val secondRowFill = narrowBarSoftFillFraction(
            line, rows[1], ranges, positionMs = 800, nextLineTimeMs = 2_000,
        )

        assertTrue(early in 0f..1f)
        assertTrue(late > early)
        assertTrue(late <= 1f)
        assertEquals(0f, secondRowFill, 0.0001f)
    }
}
