package com.mica.music.data.remote.navidrome

import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavidromeLyricsParserTest {
    @Test
    fun `structured lyrics keep millisecond timing and first valid block`() {
        val body = """
            {"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[
              {"line":[{"start":0,"value":"   "}]},
              {"line":[
                {"start":1234,"value":" First line "},
                {"start":5678,"value":"Second line"}
              ]}
            ]}}}
        """.trimIndent()

        val document = requireNotNull(NavidromeLyricsParser.structuredLyrics(body))

        assertEquals(LyricsFormat.LRC, document.format)
        assertEquals(LyricsOrigin.EXTERNAL, document.origin)
        assertEquals(listOf(1234, 5678), document.lines.map { it.startMs })
        assertEquals(listOf("First line", "Second line"), document.lines.map { it.parts.single().text })
    }

    @Test
    fun `empty structured response returns null for legacy fallback`() {
        val body = """{"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[]}}}"""

        assertNull(NavidromeLyricsParser.structuredLyrics(body))
    }

    @Test
    fun `legacy lyrics extracts raw server value`() {
        val body = """{"subsonic-response":{"status":"ok","lyrics":{"value":"[00:01.00]Hello\nWorld"}}}"""

        assertEquals("[00:01.00]Hello\nWorld", NavidromeLyricsParser.legacyLyricsValue(body))
    }
}
