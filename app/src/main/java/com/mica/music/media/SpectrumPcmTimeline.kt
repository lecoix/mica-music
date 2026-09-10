package com.mica.music.media

import java.util.ArrayDeque
import kotlin.math.roundToInt

/** Bounded, non-destructive PCM history. All calls are serialized by the analysis owner. */
internal class SpectrumPcmTimeline {
    private data class Block(val samples: FloatArray, val startUs: Long, var offset: Int = 0) {
        val remaining: Int get() = samples.size - offset
    }
    private val blocks = ArrayDeque<Block>()
    var sampleRate = 0
        private set
    var size = 0
        private set
    private val capacity = SpectrumQueueCapacityPolicy()

    fun clear() {
        blocks.clear()
        size = 0
        sampleRate = 0
        capacity.reset()
    }

    fun append(samples: FloatArray, rate: Int, startUs: Long) {
        if (samples.isEmpty()) return
        if (sampleRate != rate) clear()
        sampleRate = rate
        val limit = capacity.capacitySamples(rate, samples.size)
        val kept = minOf(samples.size, limit)
        val skipped = samples.size - kept
        blocks.addLast(Block(samples.copyOfRange(skipped, samples.size), startUs + skipped * 1_000_000L / rate))
        size += kept
        while (blocks.size > 1 && (size - blocks.first().remaining >= limit || blocks.size > 512)) {
            size -= blocks.removeFirst().remaining
        }
        // At most one boundary block straddles the retention limit.
        if (size > limit) {
            val drop = size - limit
            blocks.first().offset += drop
            size -= drop
        }
    }

    fun windowAt(positionUs: Long): FloatArray? {
        if (sampleRate == 0 || blocks.isEmpty()) return null
        val result = FloatArray(SpectrumFft.WindowSize)
        var containsPlayhead = false
        // Round to the nearest sample to absorb only microsecond timestamp quantization.
        for (block in blocks) {
            val playhead = ((positionUs - block.startUs) * sampleRate / 1_000_000.0).roundToInt()
            if (playhead in block.offset until block.samples.size) containsPlayhead = true
            val from = maxOf(block.offset, playhead - result.lastIndex)
            val to = minOf(block.samples.size, playhead + 1)
            if (to > from) {
                block.samples.copyInto(result, result.lastIndex - playhead + from, from, to)
            }
        }
        return result.takeIf { containsPlayhead }
    }
}
