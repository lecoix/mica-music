package com.mica.music.media.eq

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Linked peak safety limiter.
 *
 * Unlike a waveshaper, samples below the ceiling are untouched. When a frame would cross the
 * ceiling, all channels receive the same linear gain reduction so the stereo image is preserved.
 */
internal class LinkedPeakLimiter(
    ceilingDb: Double = DEFAULT_CEILING_DB,
    releaseMs: Double = DEFAULT_RELEASE_MS,
) {
    private val ceilingLinear = 10.0.pow(ceilingDb / 20.0)
    private val releaseMs = releaseMs.coerceAtLeast(1.0)
    private var releaseCoefficient = 0.0
    private var currentGain = 1.0

    fun configure(sampleRateHz: Int) {
        val sampleRate = sampleRateHz.coerceAtLeast(1).toDouble()
        val releaseSeconds = releaseMs / 1_000.0
        releaseCoefficient = exp(-1.0 / (releaseSeconds * sampleRate))
    }

    fun processFrame(samples: DoubleArray, channelCount: Int) {
        val count = channelCount.coerceIn(0, samples.size)
        if (count == 0) return

        var peak = 0.0
        repeat(count) { channel ->
            peak = maxOf(peak, abs(samples[channel]))
        }
        val requiredGain = if (peak > ceilingLinear && peak > 0.0) {
            ceilingLinear / peak
        } else {
            1.0
        }

        currentGain = if (requiredGain < currentGain) {
            requiredGain
        } else {
            1.0 + (currentGain - 1.0) * releaseCoefficient
        }

        repeat(count) { channel ->
            samples[channel] = (samples[channel] * currentGain)
                .coerceIn(-ceilingLinear, ceilingLinear)
        }
    }

    fun reset() {
        currentGain = 1.0
    }

    internal fun gainForTest(): Double = currentGain

    companion object {
        const val DEFAULT_CEILING_DB = -0.18
        const val DEFAULT_RELEASE_MS = 100.0
    }
}

