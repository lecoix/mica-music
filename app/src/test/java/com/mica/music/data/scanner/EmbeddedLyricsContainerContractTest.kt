package com.mica.music.data.scanner

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedLyricsContainerContractTest {
    private val lyrics = "[00:01.00]container lyric"

    @Test
    fun supportedFormatsUseOnlyTheirTagContainerParsers() {
        val id3 = setOf(EmbeddedLyricsReader.BinaryLyricsParser.ID3)
        val mp4 = setOf(EmbeddedLyricsReader.BinaryLyricsParser.MP4)

        listOf("mp3", "aac", "wav", "wave", "aiff", "aif").forEach { ext ->
            assertEquals(ext, id3, EmbeddedLyricsReader.binaryParsersFor(ext, ""))
        }
        listOf("m4a", "m4b", "m4p", "mp4", "alac").forEach { ext ->
            assertEquals(ext, mp4, EmbeddedLyricsReader.binaryParsersFor(ext, ""))
        }
        assertEquals(
            setOf(EmbeddedLyricsReader.BinaryLyricsParser.ID3, EmbeddedLyricsReader.BinaryLyricsParser.FLAC),
            EmbeddedLyricsReader.binaryParsersFor("flac", ""),
        )
        assertEquals(
            setOf(EmbeddedLyricsReader.BinaryLyricsParser.ID3, EmbeddedLyricsReader.BinaryLyricsParser.APE),
            EmbeddedLyricsReader.binaryParsersFor("ape", ""),
        )
        listOf("ogg", "opus", "wma", "dsf", "dff", "dsdiff").forEach { ext ->
            assertEquals(ext, emptySet<EmbeddedLyricsReader.BinaryLyricsParser>(), EmbeddedLyricsReader.binaryParsersFor(ext, ""))
        }
    }

    @Test
    fun taglibOnlyFormatsDoNotSelectAByteFallback() {
        listOf("ogg", "opus", "wma", "dsf", "dff", "dsdiff").forEach { ext ->
            assertEquals(ext, emptySet<EmbeddedLyricsReader.BinaryLyricsParser>(), EmbeddedLyricsReader.binaryParsersFor(ext, ""))
        }
    }

    @Test
    fun id3ContainersReadUsltAndDoNotLeakIntoTaglibOnlyFormats() {
        val bytes = id3Uslt(lyrics)

        listOf("mp3", "aac", "wav", "aiff").forEach { ext ->
            assertEquals(ext, "container lyric", lyricText(bytes, ext))
        }
        assertNull(EmbeddedLyricsReader.readDocumentFromBinaryForTest(bytes, ext = "ogg", mime = "audio/ogg"))
        assertNull(EmbeddedLyricsReader.readDocumentFromBinaryForTest(bytes, ext = "wma", mime = "audio/x-ms-wma"))
    }

    @Test
    fun flacVorbisApeV2AndMp4ContainersReadTheirNativeLyrics() {
        assertEquals("container lyric", lyricText(flacVorbis(lyrics), "flac", "audio/flac"))
        assertEquals("container lyric", lyricText(apeV2(lyrics), "ape", "audio/ape"))
        assertEquals("container lyric", lyricText(mp4Lyrics(lyrics), "m4a", "audio/mp4"))
        assertEquals("container lyric", lyricText(mp4Lyrics(lyrics), "alac", "audio/alac"))
    }

    @Test
    fun malformedVorbisAndApeItemsAreRejected() {
        val invalidUtf8 = flacVorbisBytes("LYRICS=".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28))
        assertNull(EmbeddedLyricsReader.readDocumentFromBinaryForTest(invalidUtf8, "audio/flac", "flac"))

        val binaryItem = apeV2(lyrics, flags = 2)
        assertNull(EmbeddedLyricsReader.readDocumentFromBinaryForTest(binaryItem, "audio/ape", "ape"))

        val impossibleLength = apeV2(lyrics, declaredValueLength = Int.MAX_VALUE)
        assertNull(EmbeddedLyricsReader.readDocumentFromBinaryForTest(impossibleLength, "audio/ape", "ape"))
    }

    private fun lyricText(bytes: ByteArray, ext: String, mime: String = "audio/$ext"): String? =
        EmbeddedLyricsReader.readDocumentFromBinaryForTest(bytes, mime, ext)
            ?.lines
            ?.firstOrNull()
            ?.parts
            ?.firstOrNull()
            ?.text

    private fun id3Uslt(text: String): ByteArray {
        val payload = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + text.toByteArray()
        val frame = "USLT".toByteArray() + int32Be(payload.size) + byteArrayOf(0, 0) + payload
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + synchsafe(frame.size) + frame
    }

    private fun flacVorbis(text: String): ByteArray = flacVorbisBytes("LYRICS=$text".toByteArray())

    private fun flacVorbisBytes(comment: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write(int32Le(4))
            write("Mica".toByteArray())
            write(int32Le(1))
            write(int32Le(comment.size))
            write(comment)
        }.toByteArray()
        return "fLaC".toByteArray() + byteArrayOf(0x84.toByte()) + uint24Be(payload.size) + payload
    }

    private fun apeV2(text: String, flags: Int = 0, declaredValueLength: Int? = null): ByteArray {
        val value = text.toByteArray()
        val item = int32Le(declaredValueLength ?: value.size) + int32Le(flags) +
            "LYRICS".toByteArray() + byteArrayOf(0) + value
        val footer = "APETAGEX".toByteArray() + int32Le(2_000) + int32Le(item.size + 32) +
            int32Le(1) + int32Le(0) + ByteArray(8)
        return item + footer
    }

    private fun mp4Lyrics(text: String): ByteArray {
        val data = box("data", int32Be(1) + ByteArray(4) + text.toByteArray())
        val lyricsKey = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
        return box("ilst", box(lyricsKey, data))
    }

    private fun box(type: String, payload: ByteArray): ByteArray = box(type.toByteArray(), payload)

    private fun box(type: ByteArray, payload: ByteArray): ByteArray = int32Be(payload.size + 8) + type + payload

    private fun int32Be(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun int32Le(value: Int): ByteArray = byteArrayOf(
        value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte(),
    )

    private fun uint24Be(value: Int): ByteArray = byteArrayOf(
        (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(), ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(), (value and 0x7F).toByte(),
    )
}
