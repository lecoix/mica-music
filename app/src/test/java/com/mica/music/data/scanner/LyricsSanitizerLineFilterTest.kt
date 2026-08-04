package com.mica.music.data.scanner

import com.mica.music.data.LyricTextRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSanitizerLineFilterTest {

    @Test
    fun ignorableCoversEmptySlashNotesAndMetadata() {
        assertTrue(LyricsSanitizer.isIgnorableLyricText(""))
        assertTrue(LyricsSanitizer.isIgnorableLyricText("//"))
        assertTrue(LyricsSanitizer.isIgnorableLyricText("／／"))
        assertTrue(LyricsSanitizer.isIgnorableLyricText("[00:01.00]//"))
        assertTrue(LyricsSanitizer.isIgnorableLyricText("♪"))
        assertTrue(LyricsSanitizer.isIgnorableLyricText("[00:01.00]♪♫"))
        assertTrue(LyricsSanitizer.isIgnorableLyricText("[ti:Title]"))
        assertTrue(LyricsSanitizer.isIgnorableLyricText("[ar:Artist]"))
        assertTrue(LyricsSanitizer.isIgnorableLyricText("duration: 3.5"))
        assertTrue(LyricsSanitizer.isIgnorableLyricText("y"))
        assertTrue(LyricsSanitizer.isIgnorableLyricText("[00:00.00]n"))
    }

    @Test
    fun normalLyricBodiesAreKeptIncludingSingleCjk() {
        assertFalse(LyricsSanitizer.isIgnorableLyricText("爱"))
        assertFalse(LyricsSanitizer.isIgnorableLyricText("[00:22.031]爱"))
        assertFalse(LyricsSanitizer.isIgnorableLyricText("a i wa"))
        assertFalse(LyricsSanitizer.isIgnorableLyricText("Hello"))
    }

    @Test
    fun lrcParseDropsSlashAndNoteLinesButKeepsSingleCjkTranslation() {
        val document = LyricsSanitizer.parseFilteredDocument(
            """
            [ti:Song]
            [00:01.000]//
            [00:02.000]♪
            [00:22.031]愛は
            [00:22.031]a i wa
            [00:22.031]爱
            """.trimIndent(),
        )

        assertEquals(1, document.lines.size)
        assertEquals(
            listOf(
                LyricTextRole.READING to "a i wa",
                LyricTextRole.ORIGINAL to "愛は",
                LyricTextRole.TRANSLATION to "爱",
            ),
            document.lines.single().parts.map { it.role to it.text },
        )
    }

    @Test
    fun lrcPathDoesNotUseRenderableScoringToDropShortAsciiWords() {
        // Previously isBinaryGarbage → isRenderable rejected length-1 Latin; LRC must keep real words.
        val document = LyricsSanitizer.parseFilteredDocument("[00:01.000]I\n[00:02.000]Go")
        assertEquals(listOf("I", "Go"), document.lines.map { it.parts.single().text })
    }
}
