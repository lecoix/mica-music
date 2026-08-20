package com.afalphy.sylvakru

/**
 * The block-conversion loop extracted from the reference [DsdFileReader] DSF path.
 *
 * Media3's Mica DSF extractor emits one compact planar block at a time. The reference USB DSD
 * encoder expects MSB-first byte-interleaved DSD (L R L R...). Keeping this conversion next to the
 * vendored DSD primitives avoids teaching the Media3 renderer a second DSD representation.
 */
class DsfPlanarBlockConverter(
    private val channels: Int,
    private val lsbFirst: Boolean,
) {
    init {
        require(channels > 0) { "channels must be positive" }
    }

    fun convert(planar: ByteArray): ByteArray {
        require(planar.size % channels == 0) {
            "DSF planar block size ${planar.size} is not aligned to $channels channels"
        }
        val bytesPerChannel = planar.size / channels
        val output = ByteArray(planar.size)
        for (index in 0 until bytesPerChannel) {
            for (channel in 0 until channels) {
                val byte = planar[channel * bytesPerChannel + index]
                output[index * channels + channel] =
                    if (lsbFirst) BIT_REVERSE_TABLE[byte.toInt() and 0xff] else byte
            }
        }
        return output
    }

    private companion object {
        // Byte-for-byte algorithm from DsdFileReader: DSF bitsPerSample=1 is LSB-first.
        val BIT_REVERSE_TABLE = ByteArray(256) { index ->
            var value = index
            var reversed = 0
            repeat(8) {
                reversed = (reversed shl 1) or (value and 1)
                value = value shr 1
            }
            reversed.toByte()
        }
    }
}
