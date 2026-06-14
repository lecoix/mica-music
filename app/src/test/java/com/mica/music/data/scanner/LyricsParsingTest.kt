package com.mica.music.data.scanner

import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsSync
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParsingTest {

    @Test
    fun lrcSupportsMultipleTimestampsFractionsAndSorting() {
        val parsed = LrcParser.parse(
            """
            [00:02.50][00:03.500]Later
            [00:01.2]First
            """.trimIndent(),
        )
        assertEquals(listOf(1_200, 2_500, 3_500), parsed.map { it.timeMs })
        assertEquals(listOf("First", "Later", "Later"), parsed.map { it.text })
    }

    @Test
    fun lrcAppliesPositiveAndNegativeOffsets() {
        val delayed = LrcParser.parse("[offset:+250]\n[00:01.00]Later")
        val clamped = LrcParser.parse("[offset:-2000]\n[00:01.00]Start")

        assertEquals(1_250, delayed.single().timeMs)
        assertEquals(0, clamped.single().timeMs)
    }

    @Test
    fun plainLyricsRemainUntimedAndMetadataTagsAreIgnored() {
        val parsed = LrcParser.parse("[ar:Artist]\nLine one\nLine two")
        assertEquals(listOf("Line one", "Line two"), parsed.map { it.text })
        assertFalse(LyricsSync.hasTimedLyrics(parsed))
    }

    @Test
    fun lyricSyncAppliesLeadWithoutSelectingTimedLineTooEarly() {
        val lyrics = listOf(LyricLine(1_000, "one"), LyricLine(2_000, "two"))
        assertEquals(0, LyricsSync.indexForPosition(lyrics, 800))
        assertEquals(1, LyricsSync.indexForPosition(lyrics, 1_900))
        assertEquals(-1, LyricsSync.indexForPosition(emptyList(), 1_000))
    }

    @Test
    fun encodingHandlesUtf8Utf16AndGbk() {
        val text = "[00:01.00]你好 Mica"
        assertTrue(LyricsEncoding.decodeBytes(text.toByteArray(StandardCharsets.UTF_8)).contains("Mica"))
        assertTrue(LyricsEncoding.decodeBytes(text.toByteArray(StandardCharsets.UTF_16)).contains("Mica"))
        assertTrue(LyricsEncoding.decodeBytes(text.toByteArray(charset("GBK"))).contains("Mica"))
    }
}
