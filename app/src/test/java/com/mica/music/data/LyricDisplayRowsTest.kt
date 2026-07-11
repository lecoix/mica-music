package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricDisplayRowsTest {

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
