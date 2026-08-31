package com.mica.music.data.scanner

import java.io.ByteArrayOutputStream
import java.io.File
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EmbeddedSyltLyricsTest {

    @Test
    fun validUtf16MillisecondSyltBeatsUnsyncedLyricsAndBuildsLines() {
        val sylt = syltPayload(
            1_000 to "你",
            1_200 to "好",
            2_000 to "\n世",
            2_200 to "界",
        )
        val uslt = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + "fallback".toByteArray()
        val id3 = id3v23(frame("USLT", uslt), frame("SYLT", sylt))

        val parsed = EmbeddedLyricsReader.readFromBinaryForTest(id3).orEmpty()
        val document = requireNotNull(EmbeddedLyricsReader.readDocumentFromBinaryForTest(id3))

        assertEquals(listOf("你好", "世界"), parsed.map { it.text })
        assertEquals(listOf(1_000, 2_000), parsed.map { it.timeMs })
        assertEquals(listOf(1_000, 1_200), parsed[0].cues.map { it.timeMs })
        assertEquals(listOf(2_000, 2_200), parsed[1].cues.map { it.timeMs })
        assertTrue(parsed.all { it.cues.isNotEmpty() })
        assertEquals(LyricsFormat.SYLT, document.format)
        assertEquals(LyricsOrigin.EMBEDDED, document.origin)
    }

    @Test
    fun randomAccessFastProbeFeedsSyltBinaryParser() {
        val sylt = syltPayload(
            1_000 to "Remote",
            1_300 to " SYLT",
            2_200 to "\nworks",
            2_500 to "!",
        )
        val id3 = id3v23(frame("SYLT", sylt))
        val source = object : AudioProbeRandomAccessSource {
            private val bytes = id3 + ByteArray(4096)
            override val sizeBytes: Long = bytes.size.toLong()

            override fun readAt(fileOffset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
                if (fileOffset >= bytes.size) return -1
                val count = minOf(length, 7, bytes.size - fileOffset.toInt())
                bytes.copyInto(buffer, bufferOffset, fileOffset.toInt(), fileOffset.toInt() + count)
                return count
            }
        }

        val window = requireNotNull(
            AudioProbeBytes.readFastForLyricsOrThrow(source, "audio/mpeg", "MicaRemoteSylt.mp3"),
        )
        val document = requireNotNull(
            EmbeddedLyricsReader.parseBinaryDocument(window, "audio/mpeg", "mp3"),
        )

        assertEquals(LyricsFormat.SYLT, document.format)
        assertEquals(LyricsOrigin.EMBEDDED, document.origin)
        assertEquals(listOf("Remote SYLT", "works!"), document.lines.map { line -> line.parts.joinToString("") { it.text } })
        assertEquals(4, document.lines.sumOf { it.tokens.size })
    }

    @Test
    fun realWavFixtureRetainsItsSyltTimelineWhenAvailable() {
        val fixture = listOf(File(".test-music"), File("../.test-music"))
            .flatMap { it.listFiles().orEmpty().asList() }
            .firstOrNull { it.isFile && it.extension.equals("wav", ignoreCase = true) }
        assumeTrue("Local diagnostic fixture is not checked in", fixture != null)

        val parsed = EmbeddedLyricsReader.readFromBinaryForTest(requireNotNull(fixture).readBytes()).orEmpty()
        val cues = parsed.flatMap { it.cues }

        assertTrue("Expected multiple synchronized lyric lines", parsed.size > 20)
        assertTrue("Expected the embedded word timeline", cues.size > 500)
        assertTrue(cues.zipWithNext().all { (left, right) -> left.timeMs <= right.timeMs })
        assertTrue("Expected the real timeline to extend beyond three minutes", cues.last().timeMs > 180_000)
    }

    private fun syltPayload(vararg entries: Pair<Int, String>): ByteArray = ByteArrayOutputStream().apply {
        write(1)
        write("XXX".toByteArray())
        write(2)
        write(1)
        write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0))
        entries.forEach { (timeMs, text) ->
            write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            write(text.toByteArray(Charsets.UTF_16LE))
            write(byteArrayOf(0, 0))
            writeBe32(timeMs)
        }
    }.toByteArray()

    private fun frame(id: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(id.toByteArray())
        writeBe32(payload.size)
        write(byteArrayOf(0, 0))
        write(payload)
    }.toByteArray()

    private fun id3v23(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, frame -> acc + frame }
        val size = body.size
        return byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((size shr 21) and 0x7F).toByte(),
            ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(),
            (size and 0x7F).toByte(),
        ) + body
    }

    private fun ByteArrayOutputStream.writeBe32(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }
}
