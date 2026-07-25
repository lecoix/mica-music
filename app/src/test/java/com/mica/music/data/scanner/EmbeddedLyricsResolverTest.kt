package com.mica.music.data.scanner

import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedLyricsResolverTest {
    @Test
    fun tagLibWordTimingWinsWithoutCallingFallbacks() {
        var retrieverCalled = false
        var binaryCalled = false
        val result = EmbeddedLyricsResolver.resolve(
            tagLibCandidates = listOf(
                EmbeddedLyricsTextCandidate("LYRICS", "plain"),
                EmbeddedLyricsTextCandidate("SYNCEDLYRICS", "word"),
            ),
            parse = { value -> if (value == "word") document(LyricsFormat.LRC, tokens = true) else document(LyricsFormat.PLAIN) },
            retrieverFallback = { retrieverCalled = true; null },
            binaryFallback = { binaryCalled = true; ProbeResult.Ok(null) },
        )

        assertEquals(LyricsFormat.LRC, (result as ProbeResult.Ok).value?.format)
        assertFalse(retrieverCalled)
        assertFalse(binaryCalled)
    }

    @Test
    fun invalidTagLibCandidateFallsThroughInOrder() {
        val calls = mutableListOf<String>()
        val expected = document(LyricsFormat.PLAIN)
        val result = EmbeddedLyricsResolver.resolve(
            tagLibCandidates = listOf(EmbeddedLyricsTextCandidate("LYRICS", "invalid")),
            parse = { null },
            retrieverFallback = { calls += "retriever"; null },
            binaryFallback = { calls += "binary"; ProbeResult.Ok(expected) },
        )

        assertEquals(listOf("retriever", "binary"), calls)
        assertEquals(expected, (result as ProbeResult.Ok).value)
    }

    @Test
    fun tagLibGbkMojibakeIsRepairedBeforeParsing() {
        val expected = "[00:01.00]\u4e2d\u6587\u6b4c\u8bcd"
        val mojibake = String(expected.toByteArray(Charsets.UTF_8), charset("GBK"))
        var parsedText = ""
        EmbeddedLyricsResolver.selectTagLibCandidate(
            listOf(EmbeddedLyricsTextCandidate("LYRICS", mojibake)),
        ) { text ->
            parsedText = text
            document(LyricsFormat.PLAIN)
        }

        assertEquals(expected, parsedText)
    }

    @Test
    fun oversizedCandidateIsRejectedBeforeParsing() {
        var parsed = false
        EmbeddedLyricsResolver.selectTagLibCandidate(
            listOf(
                EmbeddedLyricsTextCandidate(
                    "LYRICS",
                    "x".repeat(EmbeddedLyricsResolver.MAX_CANDIDATE_CHARS + 1),
                ),
            ),
        ) {
            parsed = true
            document(LyricsFormat.PLAIN)
        }

        assertFalse(parsed)
    }

    @Test
    fun tenThousandTagLibHitsNeverInvokeBinaryFallback() {
        var binaryCalls = 0
        repeat(10_000) {
            val result = EmbeddedLyricsResolver.resolve(
                tagLibCandidates = listOf(EmbeddedLyricsTextCandidate("LYRICS", "line $it")),
                parse = { document(LyricsFormat.PLAIN) },
                retrieverFallback = { error("retriever must stay lazy") },
                binaryFallback = { binaryCalls++; ProbeResult.Ok(null) },
            )
            assertTrue(result is ProbeResult.Ok)
        }
        assertEquals(0, binaryCalls)
    }

    private fun document(format: LyricsFormat, tokens: Boolean = false): LyricsDocument = LyricsDocument(
        format = format,
        origin = LyricsOrigin.EMBEDDED,
        lines = listOf(
            LyricLineNode(
                id = "line",
                startMs = if (format == LyricsFormat.PLAIN) 0 else 1_000,
                parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, "line")),
                tokens = if (tokens) listOf(LyricToken("line", 1_000)) else emptyList(),
            ),
        ),
    )
}
