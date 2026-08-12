package com.mica.music.media.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioRateControlTest {
    @Test
    fun uac1SetCurUsesEndpointControlAndRequiresExactReadback() {
        val io = ScriptedControlIo(
            UsbControlIoResult.Success(3),
            UsbControlIoResult.Success(3, le24(48_000)),
        )

        val result = Uac1EndpointRateController(io).setAndVerify(0x03, 48_000)

        assertEquals(UsbRateControlResult.Applied(48_000), result)
        assertEquals(2, io.requests.size)
        assertEquals(UsbControlDirection.OUT, io.requests[0].direction)
        assertEquals(UsbControlRecipient.ENDPOINT, io.requests[0].recipient)
        assertEquals(0x01, io.requests[0].request)
        assertEquals(0x0100, io.requests[0].value)
        assertEquals(0x03, io.requests[0].index)
        assertEquals(UsbControlDirection.IN, io.requests[1].direction)
        assertEquals(0x81, io.requests[1].request)
    }

    @Test
    fun uac1ReadbackMismatchFailsClosed() {
        val io = ScriptedControlIo(
            UsbControlIoResult.Success(3),
            UsbControlIoResult.Success(3, le24(44_100)),
        )

        val result = Uac1EndpointRateController(io).setAndVerify(0x03, 48_000)

        assertTrue(result is UsbRateControlResult.Rejected)
        assertEquals(
            UsbAudioRejectionCode.RATE_READBACK_MISMATCH,
            (result as UsbRateControlResult.Rejected).rejection.code,
        )
    }

    @Test
    fun uac2RangeQueryDecodesDiscreteRates() {
        val rangeBody = concat(
            byteArrayOf(2, 0),
            le32(44_100), le32(44_100), le32(0),
            le32(48_000), le32(48_000), le32(0),
        )
        val io = ScriptedControlIo(
            UsbControlIoResult.Success(2, byteArrayOf(2, 0)),
            UsbControlIoResult.Success(rangeBody.size, rangeBody),
        )

        val result = Uac2ClockRateController(io, audioControlInterface = 1).querySupportedRates(4)

        assertTrue(result is UsbRateQueryResult.Supported)
        val rates = (result as UsbRateQueryResult.Supported).sampleRates
        assertTrue(rates.supports(44_100))
        assertTrue(rates.supports(48_000))
        assertTrue(!rates.supports(96_000))
        assertEquals(0x0401, io.requests.first().index)
        assertEquals(0x02, io.requests.first().request)
    }

    @Test
    fun uac2RangeShortReadFailsClosed() {
        val io = ScriptedControlIo(
            UsbControlIoResult.Success(1, byteArrayOf(1)),
        )

        val result = Uac2ClockRateController(io, 1).querySupportedRates(4)

        assertTrue(result is UsbRateQueryResult.Rejected)
        assertEquals(
            UsbAudioRejectionCode.RATE_CONTROL_FAILED,
            (result as UsbRateQueryResult.Rejected).rejection.code,
        )
    }

    @Test
    fun uac2SetCurUsesClockEntityAndRequiresExactReadback() {
        val io = ScriptedControlIo(
            UsbControlIoResult.Success(4),
            UsbControlIoResult.Success(4, le32(96_000)),
        )

        val result = Uac2ClockRateController(io, 1).setAndVerify(4, 96_000)

        assertEquals(UsbRateControlResult.Applied(96_000), result)
        assertEquals(0x0401, io.requests[0].index)
        assertEquals(0x0100, io.requests[0].value)
        assertEquals(0x01, io.requests[0].request)
        assertEquals(UsbControlRecipient.INTERFACE, io.requests[0].recipient)
    }

    private class ScriptedControlIo(vararg results: UsbControlIoResult) : UsbAudioControlIo {
        private val scripted = ArrayDeque(results.toList())
        val requests = mutableListOf<UsbControlRequest>()

        override fun execute(request: UsbControlRequest): UsbControlIoResult {
            requests += request
            return scripted.removeFirst()
        }
    }

    private fun le24(value: Int): ByteArray = byteArrayOf(
        value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(),
    )

    private fun le32(value: Int): ByteArray = byteArrayOf(
        value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte(),
    )

    private fun concat(vararg chunks: ByteArray): ByteArray {
        val result = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }
}
