package com.mica.music.data.scanner

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryParserGoldenTest {

    @Test
    fun id3TextFrameReportsFrameAndEncoder() {
        val bytes = id3v23TextFrame("TSSE", "Mica Encoder 1.0")

        val frames = Id3FrameLister.listAll(bytes)

        assertEquals(listOf("HEADER", "TSSE"), frames.map { it.frameId })
        assertEquals("Mica Encoder 1.0", frames.last().preview)
        assertEquals("Mica Encoder 1.0", EncoderSettingsReader.fromBytes(bytes))
    }

    @Test
    fun flacVorbisCommentsPreserveKeysValuesAndEncoderPriority() {
        val bytes = flacVorbisComments(
            vendor = "Mica Vendor",
            comments = listOf(
                "TITLE=Golden Song",
                "ENCODERSETTINGS=-8 --no-padding",
            ),
        )

        assertEquals(
            listOf("TITLE" to "Golden Song", "ENCODERSETTINGS" to "-8 --no-padding"),
            VorbisCommentLister.listAll(bytes),
        )
        assertEquals("-8 --no-padding", EncoderSettingsReader.fromBytes(bytes))
    }

    @Test
    fun mp4IlstLyricsReadsUtf8DataAtom() {
        val lyrics = "[00:01.00]Golden line"
        val dataPayload = ByteArray(8) + lyrics.toByteArray(Charsets.UTF_8)
        val item = box(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()), box("data", dataPayload))
        val bytes = box("ilst", item)

        assertEquals(lyrics, Mp4LyricsReader.read(bytes))
        assertTrue(Mp4LyricsReader.listIlstItems(bytes).any { it.key == "\\xa9lyr" })
    }

    @Test(timeout = 2_000)
    fun impossibleLengthsReturnWithoutLooping() {
        val hugeMp4 = byteArrayOf(
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            'i'.code.toByte(), 'l'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
        )
        val truncatedFlac = "fLaC".toByteArray() + byteArrayOf(0x04, 0x7F, 0xFF.toByte(), 0xFF.toByte())

        assertNull(Mp4LyricsReader.read(hugeMp4))
        assertTrue(VorbisCommentLister.listAll(truncatedFlac).isEmpty())
        assertNull(EncoderSettingsReader.fromBytes(ByteArray(0)))
    }

    private fun id3v23TextFrame(frameId: String, text: String): ByteArray {
        val payload = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
        val frame = frameId.toByteArray(Charsets.US_ASCII) +
            int32Be(payload.size) +
            byteArrayOf(0, 0) +
            payload
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) +
            synchsafe(frame.size) +
            frame
    }

    private fun flacVorbisComments(
        vendor: String,
        comments: List<String>,
    ): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
            write(int32Le(vendorBytes.size))
            write(vendorBytes)
            write(int32Le(comments.size))
            comments.forEach { comment ->
                val bytes = comment.toByteArray(Charsets.UTF_8)
                write(int32Le(bytes.size))
                write(bytes)
            }
        }.toByteArray()
        return "fLaC".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x84.toByte()) +
            byteArrayOf(
                ((payload.size ushr 16) and 0xFF).toByte(),
                ((payload.size ushr 8) and 0xFF).toByte(),
                (payload.size and 0xFF).toByte(),
            ) +
            payload
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        box(type.toByteArray(Charsets.US_ASCII), payload)

    private fun box(type: ByteArray, payload: ByteArray): ByteArray =
        int32Be(payload.size + 8) + type + payload

    private fun int32Be(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun int32Le(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )
}
