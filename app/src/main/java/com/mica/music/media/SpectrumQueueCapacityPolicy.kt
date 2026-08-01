package com.mica.music.media

/** Keeps the analysis queue large enough for decoder-sized bursts without a global over-allocation. */
internal class SpectrumQueueCapacityPolicy {
    private var sampleRateHz = 0
    private var largestInputBlockSamples = 0

    fun capacitySamples(sampleRateHz: Int, inputBlockSamples: Int): Int {
        if (this.sampleRateHz != sampleRateHz) {
            this.sampleRateHz = sampleRateHz
            largestInputBlockSamples = 0
        }
        largestInputBlockSamples = maxOf(largestInputBlockSamples, inputBlockSamples.coerceAtLeast(0))
        val baseCapacity = (sampleRateHz * BaseAudioSeconds).toInt().coerceAtLeast(1)
        val burstCapacity = largestInputBlockSamples.toLong() * BurstFrameCount
        val hardCapacity = (sampleRateHz * MaxAudioSeconds).toLong().coerceAtLeast(1L)
        return maxOf(baseCapacity.toLong(), minOf(burstCapacity, hardCapacity)).toInt()
    }

    fun reset() {
        sampleRateHz = 0
        largestInputBlockSamples = 0
    }

    private companion object {
        const val BaseAudioSeconds = 2f
        const val MaxAudioSeconds = 4f
        const val BurstFrameCount = 2L
    }
}
