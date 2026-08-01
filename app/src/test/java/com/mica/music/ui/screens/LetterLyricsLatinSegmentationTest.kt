package com.mica.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterLyricsLatinSegmentationTest {
    @Test
    fun `latin phrase wraps at word boundaries using measured width`() {
        val text = "Same summer air filled up my lung once more I"

        val segments = splitLatinPhraseIntoSegments(
            text = text,
            maxWidthPx = 20f,
            measureTextWidthPx = { it.length.toFloat() },
        )

        assertEquals(
            listOf("Same summer air", "filled up my lung", "once more I"),
            segments.map(String::trimEnd),
        )
        assertEquals(text, segments.joinToString(""))
        assertTrue(segments.all { it.length <= 20 })
    }

    @Test
    fun `single latin word only hard wraps when it cannot fit`() {
        val segments = splitLatinPhraseIntoSegments(
            text = "extraordinary",
            maxWidthPx = 5f,
            measureTextWidthPx = { it.length.toFloat() },
        )

        assertEquals(listOf("extra", "ordin", "ary"), segments)
    }
}
