package com.mica.music.media.ape

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApeFramePayloadReaderTest {

    @Test
    fun lastFrameUsesActualBytesWhenAlignedPacketExtendsPastInput() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        val target = ByteArray(8)

        val actualBytes = readApeFramePayload(
            input = extractorInput(payload),
            target = target,
            offset = 0,
            length = 8,
            isLastFrame = true,
        )

        assertEquals(6, actualBytes)
        assertArrayEquals(payload, target.copyOf(actualBytes))
    }

    @Test
    fun nonFinalFrameStillRejectsTruncatedPayload() {
        assertThrows(EOFException::class.java) {
            readApeFramePayload(
                input = extractorInput(byteArrayOf(1, 2, 3, 4, 5, 6)),
                target = ByteArray(8),
                offset = 0,
                length = 8,
                isLastFrame = false,
            )
        }
    }

    @Test
    fun lastFrameStillRejectsMissingPayload() {
        assertThrows(EOFException::class.java) {
            readApeFramePayload(
                input = extractorInput(byteArrayOf()),
                target = ByteArray(8),
                offset = 0,
                length = 8,
                isLastFrame = true,
            )
        }
    }

    private fun extractorInput(bytes: ByteArray): ExtractorInput {
        var position = 0
        val reader = DataReader { target, offset, requestedLength ->
            if (position >= bytes.size) {
                C.RESULT_END_OF_INPUT
            } else {
                val count = minOf(requestedLength, bytes.size - position)
                bytes.copyInto(target, offset, position, position + count)
                position += count
                count
            }
        }
        return DefaultExtractorInput(reader, 0L, bytes.size.toLong())
    }
}
