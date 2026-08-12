package com.mica.music.media.usb

internal enum class UsbControlDirection { IN, OUT }
internal enum class UsbControlRecipient { INTERFACE, ENDPOINT }

internal data class UsbControlRequest(
    val direction: UsbControlDirection,
    val recipient: UsbControlRecipient,
    val request: Int,
    val value: Int,
    val index: Int,
    val payload: ByteArray = ByteArray(0),
    val readLength: Int = 0,
)

internal sealed interface UsbControlIoResult {
    data class Success(
        val transferredBytes: Int,
        val data: ByteArray = ByteArray(0),
    ) : UsbControlIoResult

    data class Failure(val detail: String) : UsbControlIoResult
}

internal fun interface UsbAudioControlIo {
    fun execute(request: UsbControlRequest): UsbControlIoResult
}

internal sealed interface UsbRateControlResult {
    data class Applied(val sampleRateHz: Int) : UsbRateControlResult
    data class Rejected(val rejection: UsbAudioRejection) : UsbRateControlResult
}

internal sealed interface UsbRateQueryResult {
    data class Supported(val sampleRates: UsbSampleRateSupport) : UsbRateQueryResult
    data class Rejected(val rejection: UsbAudioRejection) : UsbRateQueryResult
}

internal class Uac1EndpointRateController(private val io: UsbAudioControlIo) {
    fun setAndVerify(endpointAddress: Int, sampleRateHz: Int): UsbRateControlResult {
        if (sampleRateHz <= 0 || sampleRateHz > 0x00ff_ffff) {
            return rejected(UsbAudioRejectionCode.UNSUPPORTED_SAMPLE_RATE, "UAC1 rate is outside 24-bit range")
        }
        val payload = sampleRateHz.toLe24()
        val write = io.execute(
            UsbControlRequest(
                direction = UsbControlDirection.OUT,
                recipient = UsbControlRecipient.ENDPOINT,
                request = UAC1_SET_CUR,
                value = SAMPLING_FREQ_CONTROL shl 8,
                index = endpointAddress and 0xff,
                payload = payload,
            ),
        )
        if (!write.isExactSuccess(payload.size)) {
            return rejected(UsbAudioRejectionCode.RATE_CONTROL_FAILED, "UAC1 SET_CUR failed/short")
        }
        val read = io.execute(
            UsbControlRequest(
                direction = UsbControlDirection.IN,
                recipient = UsbControlRecipient.ENDPOINT,
                request = UAC1_GET_CUR,
                value = SAMPLING_FREQ_CONTROL shl 8,
                index = endpointAddress and 0xff,
                readLength = 3,
            ),
        )
        val bytes = read.exactData(3)
            ?: return rejected(UsbAudioRejectionCode.RATE_CONTROL_FAILED, "UAC1 GET_CUR failed/short")
        val actual = bytes.u24le(0)
        if (actual != sampleRateHz) {
            return rejected(
                UsbAudioRejectionCode.RATE_READBACK_MISMATCH,
                "UAC1 requested=$sampleRateHz readback=$actual",
            )
        }
        return UsbRateControlResult.Applied(sampleRateHz)
    }

    private fun rejected(code: UsbAudioRejectionCode, detail: String) =
        UsbRateControlResult.Rejected(UsbAudioRejection(code, detail))
}

internal class Uac2ClockRateController(
    private val io: UsbAudioControlIo,
    private val audioControlInterface: Int,
) {
    fun querySupportedRates(clockSourceId: Int): UsbRateQueryResult {
        val initial = io.execute(rangeRequest(clockSourceId, 2))
        val header = initial.exactData(2)
            ?: return queryRejected("UAC2 RANGE count read failed/short")
        val count = header.u16le(0)
        if (count <= 0 || count > MAX_UAC2_RATE_RANGES) {
            return queryRejected("UAC2 RANGE count=$count is invalid")
        }
        val bodyLength = 2 + count * 12
        val body = io.execute(rangeRequest(clockSourceId, bodyLength)).exactData(bodyLength)
            ?: return queryRejected("UAC2 RANGE body read failed/short")
        if (body.u16le(0) != count) {
            return queryRejected("UAC2 RANGE count changed between reads")
        }
        val ranges = mutableListOf<UsbSampleRateRange>()
        repeat(count) { index ->
            val offset = 2 + index * 12
            val min = body.u32leInt(offset) ?: return queryRejected("UAC2 RANGE min exceeds Int")
            val max = body.u32leInt(offset + 4) ?: return queryRejected("UAC2 RANGE max exceeds Int")
            val resolution = body.u32leInt(offset + 8) ?: return queryRejected("UAC2 RANGE resolution exceeds Int")
            if (min <= 0 || max < min) return queryRejected("UAC2 RANGE[$index] invalid min/max")
            val normalizedResolution = if (min == max && resolution == 0) 1 else resolution
            if (normalizedResolution <= 0) return queryRejected("UAC2 RANGE[$index] invalid resolution")
            ranges += UsbSampleRateRange(min, max, normalizedResolution)
        }
        val support = when {
            ranges.size == 1 && ranges.single().minHz == ranges.single().maxHz ->
                UsbSampleRateSupport.Fixed(ranges.single().minHz)
            ranges.all { it.minHz == it.maxHz } ->
                UsbSampleRateSupport.Discrete(ranges.map { it.minHz }.toSet())
            else -> UsbSampleRateSupport.Ranges(ranges)
        }
        return UsbRateQueryResult.Supported(support)
    }

    fun readClockValidity(clockSourceId: Int): UsbRateControlResult {
        val read = io.execute(
            UsbControlRequest(
                direction = UsbControlDirection.IN,
                recipient = UsbControlRecipient.INTERFACE,
                request = UAC2_CUR,
                value = CLOCK_VALID_CONTROL shl 8,
                index = entityIndex(clockSourceId),
                readLength = 1,
            ),
        )
        val data = read.exactData(1)
            ?: return rejected(UsbAudioRejectionCode.RATE_CONTROL_FAILED, "UAC2 clock validity read failed/short")
        if (data[0].toInt() and 0xff == 0) {
            return rejected(UsbAudioRejectionCode.CLOCK_INVALID, "UAC2 clock source=$clockSourceId reports invalid")
        }
        return UsbRateControlResult.Applied(0)
    }

    fun setAndVerify(clockSourceId: Int, sampleRateHz: Int): UsbRateControlResult {
        if (sampleRateHz <= 0) {
            return rejected(UsbAudioRejectionCode.UNSUPPORTED_SAMPLE_RATE, "UAC2 sample rate must be positive")
        }
        val payload = sampleRateHz.toLe32()
        val write = io.execute(
            UsbControlRequest(
                direction = UsbControlDirection.OUT,
                recipient = UsbControlRecipient.INTERFACE,
                request = UAC2_CUR,
                value = SAMPLING_FREQ_CONTROL shl 8,
                index = entityIndex(clockSourceId),
                payload = payload,
            ),
        )
        if (!write.isExactSuccess(4)) {
            return rejected(UsbAudioRejectionCode.RATE_CONTROL_FAILED, "UAC2 SET_CUR failed/short")
        }
        val read = io.execute(
            UsbControlRequest(
                direction = UsbControlDirection.IN,
                recipient = UsbControlRecipient.INTERFACE,
                request = UAC2_CUR,
                value = SAMPLING_FREQ_CONTROL shl 8,
                index = entityIndex(clockSourceId),
                readLength = 4,
            ),
        )
        val bytes = read.exactData(4)
            ?: return rejected(UsbAudioRejectionCode.RATE_CONTROL_FAILED, "UAC2 GET_CUR failed/short")
        val actual = bytes.u32leInt(0)
            ?: return rejected(UsbAudioRejectionCode.RATE_READBACK_MISMATCH, "UAC2 readback exceeds Int")
        if (actual != sampleRateHz) {
            return rejected(
                UsbAudioRejectionCode.RATE_READBACK_MISMATCH,
                "UAC2 requested=$sampleRateHz readback=$actual",
            )
        }
        return UsbRateControlResult.Applied(sampleRateHz)
    }

    private fun rangeRequest(clockSourceId: Int, length: Int) = UsbControlRequest(
        direction = UsbControlDirection.IN,
        recipient = UsbControlRecipient.INTERFACE,
        request = UAC2_RANGE,
        value = SAMPLING_FREQ_CONTROL shl 8,
        index = entityIndex(clockSourceId),
        readLength = length,
    )

    private fun entityIndex(clockSourceId: Int): Int =
        ((clockSourceId and 0xff) shl 8) or (audioControlInterface and 0xff)

    private fun queryRejected(detail: String) = UsbRateQueryResult.Rejected(
        UsbAudioRejection(UsbAudioRejectionCode.RATE_CONTROL_FAILED, detail),
    )

    private fun rejected(code: UsbAudioRejectionCode, detail: String) =
        UsbRateControlResult.Rejected(UsbAudioRejection(code, detail))
}

private fun UsbControlIoResult.isExactSuccess(expectedBytes: Int): Boolean =
    this is UsbControlIoResult.Success && transferredBytes == expectedBytes

private fun UsbControlIoResult.exactData(expectedBytes: Int): ByteArray? = when (this) {
    is UsbControlIoResult.Success -> data.takeIf { transferredBytes == expectedBytes && it.size == expectedBytes }
    is UsbControlIoResult.Failure -> null
}

private fun Int.toLe24(): ByteArray = byteArrayOf(
    (this and 0xff).toByte(), ((this ushr 8) and 0xff).toByte(), ((this ushr 16) and 0xff).toByte(),
)
private fun Int.toLe32(): ByteArray = byteArrayOf(
    (this and 0xff).toByte(), ((this ushr 8) and 0xff).toByte(),
    ((this ushr 16) and 0xff).toByte(), ((this ushr 24) and 0xff).toByte(),
)
private fun ByteArray.u16le(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
private fun ByteArray.u24le(offset: Int): Int =
    u16le(offset) or ((this[offset + 2].toInt() and 0xff) shl 16)
private fun ByteArray.u32leInt(offset: Int): Int? {
    val value = (this[offset].toLong() and 0xffL) or
        ((this[offset + 1].toLong() and 0xffL) shl 8) or
        ((this[offset + 2].toLong() and 0xffL) shl 16) or
        ((this[offset + 3].toLong() and 0xffL) shl 24)
    return value.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

private const val SAMPLING_FREQ_CONTROL = 0x01
private const val CLOCK_VALID_CONTROL = 0x02
private const val UAC1_SET_CUR = 0x01
private const val UAC1_GET_CUR = 0x81
private const val UAC2_CUR = 0x01
private const val UAC2_RANGE = 0x02
private const val MAX_UAC2_RATE_RANGES = 256
