package com.mica.music.media

/** Primitive bounded FIFO used to pace decoder-sized PCM blocks at visual frame cadence. */
internal class SpectrumPcmQueue(initialCapacity: Int = 8_192) {
    private var samples = FloatArray(initialCapacity.coerceAtLeast(1))
    private var readIndex = 0
    var size: Int = 0
        private set

    fun clear() {
        readIndex = 0
        size = 0
    }

    fun offer(value: Float, maxSamples: Int) {
        val limit = maxSamples.coerceAtLeast(1)
        if (size == samples.size && samples.size < limit) {
            grow(minOf(limit, maxOf(samples.size * 2, samples.size + 1)))
        }
        if (size == samples.size || size >= limit) {
            readIndex = (readIndex + 1) % samples.size
            size--
        }
        samples[(readIndex + size) % samples.size] = value
        size++
    }

    fun drain(maxCount: Int, consume: (Float) -> Unit): Int {
        val count = minOf(maxCount.coerceAtLeast(0), size)
        repeat(count) {
            consume(samples[readIndex])
            readIndex = (readIndex + 1) % samples.size
        }
        size -= count
        if (size == 0) readIndex = 0
        return count
    }

    private fun grow(newCapacity: Int) {
        val expanded = FloatArray(newCapacity)
        for (i in 0 until size) {
            expanded[i] = samples[(readIndex + i) % samples.size]
        }
        samples = expanded
        readIndex = 0
    }
}
