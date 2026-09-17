package com.mica.music.media

import java.util.ArrayDeque
import kotlin.math.roundToInt

/** Bounded, non-destructive PCM history. All calls are serialized by the analysis owner. */
internal class SpectrumPcmTimeline {
    private data class Block(val samples: FloatArray, val startUs: Long, var offset: Int = 0) {
        val remaining: Int get() = samples.size - offset
        fun endUs(rate: Int): Long = startUs + samples.size.toLong() * 1_000_000L / rate
    }
    private val blocks = ArrayDeque<Block>()
    var sampleRate = 0
        private set
    var size = 0
        private set
    private val capacity = SpectrumQueueCapacityPolicy()
    private var protectedPositionUs: Long? = null

    fun clear() {
        blocks.clear()
        size = 0
        sampleRate = 0
        protectedPositionUs = null
        capacity.reset()
    }

    /** The AudioSink media clock is the authority for which history must remain addressable. */
    fun protect(positionUs: Long) {
        if (positionUs >= 0) protectedPositionUs = positionUs
    }

    fun append(samples: FloatArray, rate: Int, startUs: Long) {
        if (samples.isEmpty()) return
        if (sampleRate != rate) {
            // A fresh AudioSink clock can arrive before the first PCM block (or before a format-change block).
            // Reset stored PCM/capacity state without discarding that clock authority.
            val protection = protectedPositionUs
            clear()
            protectedPositionUs = protection
        }
        sampleRate = rate
        val softLimit = capacity.capacitySamples(rate, samples.size)
        val hardLimit = capacity.maxCapacitySamples(rate)
        val kept = minOf(samples.size, hardLimit)
        val skipped = samples.size - kept
        blocks.addLast(Block(samples.copyOfRange(skipped, samples.size), startUs + skipped * 1_000_000L / rate))
        size += kept

        trimOldestTo(softLimit, protectedFloorUs(rate))
        if (size > hardLimit) {
            // Hard bound wins only if capture runs more than the supported retention envelope ahead
            // of the sink clock. Prefer current audible history over speculative future PCM.
            trimNewestTo(hardLimit)
        }
    }

    private fun protectedFloorUs(rate: Int): Long? = protectedPositionUs?.let { position ->
        val lookbackUs = (SpectrumFft.WindowSize - 1).toLong() * 1_000_000L / rate
        position - lookbackUs
    }

    private fun trimOldestTo(limit: Int, protectedFloorUs: Long?) {
        while (size > limit && blocks.isNotEmpty()) {
            val first = blocks.first()
            val removable = if (protectedFloorUs == null) {
                first.remaining
            } else {
                val floorSample = ((protectedFloorUs - first.startUs) * sampleRate / 1_000_000.0).toInt()
                (floorSample - first.offset).coerceIn(0, first.remaining)
            }
            if (removable <= 0) break
            if (removable == first.remaining) {
                blocks.removeFirst()
            } else {
                first.offset += removable
            }
            size -= removable
        }
        while (blocks.size > 512 && blocks.size > 1) {
            val first = blocks.first()
            if (protectedFloorUs != null && first.endUs(sampleRate) > protectedFloorUs) break
            size -= blocks.removeFirst().remaining
        }
    }

    private fun trimNewestTo(limit: Int) {
        while (size > limit && blocks.isNotEmpty()) {
            val last = blocks.removeLast()
            val overflow = size - limit
            if (last.remaining <= overflow) {
                size -= last.remaining
            } else {
                val keep = last.remaining - overflow
                val from = last.offset
                blocks.addLast(Block(last.samples.copyOfRange(from, from + keep), last.startUs + from * 1_000_000L / sampleRate))
                size -= overflow
            }
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
