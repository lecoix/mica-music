package com.mica.music.data.scanner

import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricTextRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Love2000LyricsSampleTest {

    @Test
    fun singleCjkCharacterIsRenderableLyricText() {
        assertTrue(LyricsEncoding.isRenderable("爱"))
        assertTrue(LyricsEncoding.isRenderable("愛"))
        assertFalse(LyricsEncoding.isRenderable("y"))
        assertFalse(LyricsEncoding.isRenderable("a"))
    }

    @Test
    fun sameTimestampKeepsSingleCharacterChineseTranslation() {
        val document = LyricsSanitizer.parseFilteredDocument(
            """
            [00:22.031]愛は
            [00:22.031]a i wa
            [00:22.031]爱
            """.trimIndent(),
        )

        val line = document.lines.single { it.startMs == 22_031 }
        assertEquals(
            listOf(
                LyricTextRole.READING to "a i wa",
                LyricTextRole.ORIGINAL to "愛は",
                LyricTextRole.TRANSLATION to "爱",
            ),
            line.parts.map { it.role to it.text },
        )

        val rowsOff = LyricDisplayRows.rowsFromParts(
            parts = line.parts,
            readingEnabled = false,
        )
        assertEquals(
            listOf(LyricTextRole.ORIGINAL to "愛は", LyricTextRole.TRANSLATION to "爱"),
            rowsOff!!.map { it.role to it.text },
        )
    }
}
