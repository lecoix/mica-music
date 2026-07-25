package com.mica.music.data.scanner

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsEncodingContractTest {
    @Test
    fun id3DeclaredEncodingsAreDeterministic() {
        val latin1 = "cafe\u00e9 deja\u00e0"
        assertEquals(latin1, LyricsEncoding.decodeId3Bytes(latin1.toByteArray(Charsets.ISO_8859_1), 0))

        val utf16 = "\u6b20\u3051\u305f\u6708"
        assertEquals(utf16, LyricsEncoding.decodeId3Bytes(utf16.toByteArray(StandardCharsets.UTF_16), 1))
        assertEquals(utf16, LyricsEncoding.decodeId3Bytes(utf16.toByteArray(StandardCharsets.UTF_16BE), 2))
        assertEquals(utf16, LyricsEncoding.decodeId3Bytes(utf16.toByteArray(StandardCharsets.UTF_8), 3))
    }

    @Test
    fun id3Latin1LegacyFallbackRecoversGbkButPreservesRealLatin1() {
        val chinese = "[00:01.00]\u4f60\u597d\u4e16\u754c"
        assertEquals(chinese, LyricsEncoding.decodeId3Bytes(chinese.toByteArray(charset("GBK")), 0))

        val french = "[00:01.00]Cafe\u00e9 deja\u00e0 chante"
        assertEquals(french, LyricsEncoding.decodeId3Bytes(french.toByteArray(Charsets.ISO_8859_1), 0))
    }

    @Test
    fun validUtf8WithSupplementaryCharactersNeverEntersLegacyGuessing() {
        val text = "[00:01.00]\uD83C\uDFB5 \u6b20\u3051\u305f\u6708"
        assertEquals(text, LyricsEncoding.decodeBytes(text.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun strictContainerDecodersRejectUnknownOrMalformedBytes() {
        assertEquals("", LyricsEncoding.decodeMp4DataBytes("lyrics".toByteArray(), typeCode = 0))
        assertEquals("", LyricsEncoding.decodeUtf8Bytes(byteArrayOf(0xC3.toByte(), 0x28)))
        assertTrue(LyricsEncoding.decodeMp4DataBytes("\u4f60\u597d".toByteArray(), typeCode = 1).isNotEmpty())
    }
}
