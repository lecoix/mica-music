package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTimelineEngineTest {

    @Test
    fun compatibilityDocumentSeparatesBilingualPartsAndKeepsWordTokens() {
        val document = listOf(
            LyricLine(
                timeMs = 1_000,
                text = "original\ntranslation",
                cues = listOf(LyricCue(1_000, "original")),
                endTimeMs = 2_000,
            ),
        ).toLyricsDocumentCompat(format = LyricsFormat.TTML)

        val line = document.lines.single()
        assertEquals(LyricsFormat.TTML, document.format)
        assertEquals(listOf(LyricTextRole.ORIGINAL, LyricTextRole.TRANSLATION), line.parts.map { it.role })
        assertEquals(listOf("original", "translation"), line.parts.map { it.text })
        assertEquals(2_000, line.tokens.single().endMs)
    }

    @Test
    fun timelineReportsBeforeLineActiveLineGapAndAfterLastLine() {
        val engine = LyricsTimelineEngine(
            LyricsDocument(
                lines = listOf(
                    LyricLineNode("one", startMs = 1_000, endMs = 2_000, parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "one"))),
                    LyricLineNode("two", startMs = 5_000, endMs = 6_000, parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "two"))),
                ),
            ),
        )

        assertEquals(LyricsTimelinePhase.BeforeFirstLine, engine.snapshotAt(500).phase)
        val active = engine.snapshotAt(1_500).phase as LyricsTimelinePhase.Line
        assertEquals(0, active.index)
        assertEquals(0.65f, active.progress, 0.0001f)
        val gap = engine.snapshotAt(3_500).phase as LyricsTimelinePhase.Gap
        assertEquals(0, gap.previousIndex)
        assertEquals(1, gap.nextIndex)
        assertEquals(3_000, gap.durationMs)
        assertEquals(0.55f, gap.progress, 0.0001f)
        assertEquals(LyricsTimelinePhase.AfterLastLine, engine.snapshotAt(6_000).phase)
    }

    @Test
    fun timelineLineActivationUsesLeadMs() {
        val engine = LyricsTimelineEngine(
            LyricsDocument(
                lines = listOf(
                    LyricLineNode("one", startMs = 1_000, endMs = 2_000, parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "one"))),
                ),
            ),
        )

        assertEquals(LyricsTimelinePhase.BeforeFirstLine, engine.snapshotAt(849).phase)
        val active = engine.snapshotAt(850).phase as LyricsTimelinePhase.Line
        assertEquals(0, active.index)
    }

    @Test
    fun timelineUsesNextStartAsFallbackForLinesWithoutExplicitEnd() {
        val engine = LyricsTimelineEngine(
            LyricsDocument(
                lines = listOf(
                    LyricLineNode("one", startMs = 1_000, parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "one"))),
                    LyricLineNode("two", startMs = 3_000, parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "two"))),
                ),
            ),
        )

        val phase = engine.snapshotAt(2_000).phase
        assertTrue(phase is LyricsTimelinePhase.Line)
        assertEquals(0, (phase as LyricsTimelinePhase.Line).index)
    }
}
