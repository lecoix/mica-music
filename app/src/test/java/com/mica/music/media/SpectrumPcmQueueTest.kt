package com.mica.music.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SpectrumPcmQueueTest {
    @Test
    fun largeFlacBlockIsConsumedAcrossMultipleVisualTicks() {
        val queue = SpectrumPcmQueue(initialCapacity = 16)
        repeat(4_096) { queue.offer(it.toFloat(), maxSamples = 22_050) }

        val hop = 44_100 / 60
        val drainedPerTick = mutableListOf<Int>()
        while (queue.size > 0) {
            drainedPerTick += queue.drain(hop) { }
        }

        assertEquals(listOf(735, 735, 735, 735, 735, 421), drainedPerTick)
    }

    @Test
    fun queueDropsOldestAudioWhenLatencyLimitIsReached() {
        val queue = SpectrumPcmQueue(initialCapacity = 4)
        repeat(8) { queue.offer(it.toFloat(), maxSamples = 4) }
        val consumed = mutableListOf<Float>()

        queue.drain(4, consumed::add)

        assertEquals(listOf(4f, 5f, 6f, 7f), consumed)
    }

    @Test
    fun dynamicCapacityRetainsTwoConsecutiveApeWarmupFrames() {
        val queue = SpectrumPcmQueue(initialCapacity = 8_192)
        val policy = SpectrumQueueCapacityPolicy()
        val sampleRate = 44_100
        val frameSamples = 73_728

        repeat(2) {
            val capacity = policy.capacitySamples(sampleRate, frameSamples)
            repeat(frameSamples) { queue.offer(0f, maxSamples = capacity) }
        }

        assertEquals(frameSamples * 2, queue.size)
    }
}
