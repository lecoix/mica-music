package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricDisplayRowsTest {

    @Test
    fun splitPartsAtIngestDoesNotPersistTextHeuristicSplits() {
        val samples = listOf(
            "AC/DC",
            "and/or",
            "01/02/2026",
            "original / translation",
            "left|right",
            "未熟 無ジョウ されど\u2009不成熟 无情（常） 但是",
        )

        samples.forEach { text ->
            val parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, text))
            assertEquals(parts, LyricDisplayRows.splitPartsAtIngest(parts))
        }
    }

    @Test
    fun splitPartsAtIngestLeavesExistingTranslationTrackUntouched() {
        val parts = listOf(
            LyricTextPart(LyricTextRole.READING, "a i wa"),
            LyricTextPart(LyricTextRole.ORIGINAL, "愛は"),
            LyricTextPart(LyricTextRole.TRANSLATION, "爱"),
        )

        assertEquals(parts, LyricDisplayRows.splitPartsAtIngest(parts))
    }

    @Test
    fun legacyLyricsConversionKeepsHeuristicSeparatorsInOriginalText() {
        val document = listOf(LyricLine(timeMs = 1_000, text = "AC/DC / live"))
            .toLyricsDocumentCompat(format = LyricsFormat.LRC)

        assertEquals(
            listOf(LyricTextPart(LyricTextRole.ORIGINAL, "AC/DC / live")),
            document.lines.single().parts,
        )
        assertEquals(null, LyricDisplayRows.rowsFromParts(document.lines.single().parts))
        assertEquals(
            listOf("AC/DC", "live"),
            LyricDisplayRows.splitForDisplay("AC/DC / live"),
        )
    }

    @Test
    fun rowsFromPartsMergesWhenSplitDisabled() {
        val rows = LyricDisplayRows.rowsFromParts(
            parts = listOf(
                LyricTextPart(LyricTextRole.ORIGINAL, "原文"),
                LyricTextPart(LyricTextRole.TRANSLATION, "译文"),
            ),
            splitEnabled = false,
        )!!

        assertEquals(listOf("原文\u2009译文"), rows.map { it.text })
    }

    @Test
    fun splitRowsRetainRangesInOriginalText() {
        val rows = LyricDisplayRows.splitForDisplayRows("original / translation")

        assertEquals(listOf("original", "translation"), rows.map { it.text })
        assertEquals(listOf(0, 11), rows.map { it.start })
        assertEquals(listOf(8, 22), rows.map { it.endExclusive })
    }

    @Test
    fun bilingualDisplayModeSelectsOriginalOrTranslationRow() {
        val text = "original / translation"

        val original = LyricDisplayRows.rowsForBilingualDisplayMode(
            text = text,
            mode = LyricsBilingualDisplayMode.ORIGINAL,
        )
        val translation = LyricDisplayRows.rowsForBilingualDisplayMode(
            text = text,
            mode = LyricsBilingualDisplayMode.TRANSLATION,
        )

        assertEquals(listOf("original"), original.map { it.text })
        assertEquals(listOf(0), original.map { it.start })
        assertEquals(listOf("translation"), translation.map { it.text })
        assertEquals(listOf(11), translation.map { it.start })
    }

    @Test
    fun newlineSeparatedTtmlBilingualTextUsesTheSameDisplayModes() {
        val text = "original\ntranslation"

        assertEquals(
            listOf("original"),
            LyricDisplayRows.rowsForBilingualDisplayMode(
                text,
                mode = LyricsBilingualDisplayMode.ORIGINAL,
            ).map { it.text },
        )
        assertEquals(
            listOf("translation"),
            LyricDisplayRows.rowsForBilingualDisplayMode(
                text,
                mode = LyricsBilingualDisplayMode.TRANSLATION,
            ).map { it.text },
        )
    }

    @Test
    fun rtlTextCanBeSplitWithoutChangingItsText() {
        val text = "\u05e9\u05dc\u05d5\u05dd\ntranslation"

        assertEquals(listOf("\u05e9\u05dc\u05d5\u05dd", "translation"), LyricDisplayRows.splitForDisplay(text))
    }
}
