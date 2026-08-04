package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricDisplayRowsTest {

    @Test
    fun splitPartsAtIngestSplitsThinSpaceIntoOriginalAndTranslation() {
        val split = LyricDisplayRows.splitPartsAtIngest(
            listOf(
                LyricTextPart(LyricTextRole.ORIGINAL, "未熟 無ジョウ されど\u2009不成熟 无情（常） 但是"),
            ),
        )

        assertEquals(
            listOf(
                LyricTextRole.ORIGINAL to "未熟 無ジョウ されど",
                LyricTextRole.TRANSLATION to "不成熟 无情（常） 但是",
            ),
            split.map { it.role to it.text },
        )
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
