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
}
