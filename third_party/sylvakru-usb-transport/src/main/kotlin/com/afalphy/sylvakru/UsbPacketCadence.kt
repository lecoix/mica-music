package com.afalphy.sylvakru

/** Overflow-safe Bresenham cadence shared by PCM and DSD carrier packetization. */
class UsbPacketCadence(
    private val sampleRate: Int,
    private val packetsPerSecond: Int,
) {
    private var remainder = 0L

    init {
        require(sampleRate > 0)
        require(packetsPerSecond > 0)
    }

    fun nextNominalFrames(): Int {
        remainder += sampleRate.toLong()
        val frames = remainder / packetsPerSecond
        remainder %= packetsPerSecond
        return frames.toInt()
    }

    fun reset() {
        remainder = 0L
    }

    companion object {
        fun projectNominalFrames(sampleRate: Int, packetsPerSecond: Int, packetCount: Long): Long {
            require(sampleRate > 0 && packetsPerSecond > 0 && packetCount >= 0)
            return Math.multiplyExact(packetCount, sampleRate.toLong()) / packetsPerSecond
        }
    }
}
