package com.mica.music.media.dsf

import com.mica.music.media.dsd.DsdSourceBitOrder

data class DsfExtractorPacketFacts(
    val sourceSampleRateHz: Int,
    val channelCount: Int,
    val sourceBitOrder: DsdSourceBitOrder,
) {
    init {
        require(sourceSampleRateHz > 0)
        require(channelCount > 0)
    }

    companion object {
        fun fromFormat(format: DsfFormat): DsfExtractorPacketFacts = DsfExtractorPacketFacts(
            sourceSampleRateHz = format.sampleRateHz,
            channelCount = format.channelCount,
            sourceBitOrder = if (format.bitsPerSample == 1) {
                DsdSourceBitOrder.LSB_FIRST
            } else {
                DsdSourceBitOrder.MSB_FIRST
            },
        )
    }
}

object DsfExtractorPacketCanonicalizer {
    fun canonicalize(
        packet: ByteArray,
        offset: Int = 0,
        byteCount: Int = packet.size - offset,
        facts: DsfExtractorPacketFacts,
    ): ByteArray {
        require(offset >= 0 && byteCount >= 0 && offset <= packet.size)
        require(byteCount <= packet.size - offset)
        require(byteCount % facts.channelCount == 0) {
            "DSF extractor packet is not channel-planar aligned"
        }
        if (byteCount == 0) return ByteArray(0)
        val frames = byteCount / facts.channelCount
        val output = ByteArray(byteCount)
        var out = 0
        repeat(frames) { frame ->
            repeat(facts.channelCount) { channel ->
                val raw = packet[offset + channel * frames + frame]
                output[out++] = if (facts.sourceBitOrder == DsdSourceBitOrder.LSB_FIRST) reverseBits(raw) else raw
            }
        }
        return output
    }

    private fun reverseBits(value: Byte): Byte =
        (Integer.reverse(value.toInt() and 0xFF) ushr 24).toByte()
}
