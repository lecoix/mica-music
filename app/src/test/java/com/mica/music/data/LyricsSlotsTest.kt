package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsSlotsTest {
    private fun document(format: LyricsFormat, text: String) = listOf(
        LyricLine(0, text),
    ).toLyricsDocumentCompat(format = format, origin = LyricsOrigin.EXTERNAL)

    @Test
    fun selectionOrderCanBeChangedWithoutReparsing() {
        val embedded = document(LyricsFormat.SYLT, "embedded").copy(origin = LyricsOrigin.EMBEDDED)
        val lrc = document(LyricsFormat.LRC, "lrc")
        val ttml = document(LyricsFormat.TTML, "ttml")
        val slots = LyricsSlots(embedded, lrc, ttml)

        assertEquals(ttml, slots.selected())
        assertEquals(embedded, slots.selected(listOf(LyricsSlot.EMBEDDED, LyricsSlot.EXTERNAL_LRC)))
        assertEquals(3, slots.entries().size)
    }
}
