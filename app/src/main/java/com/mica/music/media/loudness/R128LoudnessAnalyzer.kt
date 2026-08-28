package com.mica.music.media.loudness

import com.mica.music.data.LoudnessAnalysis
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/**
 * Streaming BS.1770 / EBU R128 integrated loudness analyzer.
 *
 * The analyzer consumes interleaved float PCM and keeps only 400 ms block energies, so a complete
 * library scan never materializes decoded PCM on disk or in memory.
 *
 * K-weighting coefficient construction and default channel mapping are adapted from libebur128
 * (Copyright (c) 2011 Jan Kokemüller, MIT License):
 * https://github.com/jiixyj/libebur128
 */
internal class R128LoudnessAnalyzer(
    private val sampleRateHz: Int,
    private val channelCount: Int,
) {
    init {
        require(sampleRateHz > 0)
        require(channelCount > 0)
    }

    private val filters = Array(channelCount) {
        ChannelFilter(
            shelf = Biquad.kWeightingShelf(sampleRateHz),
            highPass = Biquad.kWeightingHighPass(sampleRateHz),
        )
    }
    private val channelWeights = DoubleArray(channelCount) { index ->
        when {
            channelCount <= 3 -> 1.0
            channelCount == 4 -> if (index >= 2) SURROUND_WEIGHT else 1.0
            channelCount == 5 -> if (index >= 3) SURROUND_WEIGHT else 1.0
            index == 3 -> 0.0 // 5.1/7.1 conventional LFE position; R128 excludes LFE
            index == 4 || index == 5 -> SURROUND_WEIGHT
            index > 5 -> 0.0 // libebur128's default map leaves additional channels unused
            else -> 1.0
        }
    }
    // Match libebur128: round the 100 ms quantum first, then build a 400 ms block from it.
    private val stepFrames = max(1, (sampleRateHz + 5) / 10)
    private val windowFrames = stepFrames * 4
    private val energyRing = DoubleArray(windowFrames)
    private val blockEnergies = ArrayList<Double>()
    private var ringWrite = 0
    private var ringCount = 0
    private var rollingEnergy = 0.0
    private var framesSeen = 0L
    private var nextBlockAtFrame = windowFrames.toLong()
    private var pendingSamples = FloatArray(channelCount)
    private var pendingCount = 0
    private var samplePeak = 0f

    fun addInterleaved(samples: FloatArray, length: Int = samples.size) {
        require(length in 0..samples.size)
        var offset = 0
        while (offset < length) {
            val needed = channelCount - pendingCount
            val copied = minOf(needed, length - offset)
            samples.copyInto(pendingSamples, pendingCount, offset, offset + copied)
            pendingCount += copied
            offset += copied
            if (pendingCount == channelCount) {
                processFrame(pendingSamples)
                pendingCount = 0
            }
        }
    }

    fun finish(sourceSizeBytes: Long, sourceModifiedMs: Long): LoudnessAnalysis {
        val integrated = integratedLufs() ?: return LoudnessAnalysis()
        return LoudnessAnalysis(
            integratedLufs = integrated.toFloat(),
            samplePeak = samplePeak,
            trackGainDb = LoudnessAnalysis.TARGET_LUFS - integrated.toFloat(),
            sourceSizeBytes = sourceSizeBytes,
            sourceModifiedMs = sourceModifiedMs,
            analyzerRevision = LoudnessAnalysis.CURRENT_ANALYZER_REVISION,
        )
    }

    internal fun integratedLufs(): Double? {
        if (blockEnergies.isEmpty()) return null
        val absoluteGated = blockEnergies.filter { energy -> loudnessForEnergy(energy) >= ABSOLUTE_GATE_LUFS }
        if (absoluteGated.isEmpty()) return null
        val preliminaryEnergy = absoluteGated.average()
        if (preliminaryEnergy <= 0.0) return null
        val relativeGate = loudnessForEnergy(preliminaryEnergy) - RELATIVE_GATE_LU
        val gate = max(ABSOLUTE_GATE_LUFS, relativeGate)
        val finalBlocks = absoluteGated.filter { energy -> loudnessForEnergy(energy) >= gate }
        if (finalBlocks.isEmpty()) return null
        val energy = finalBlocks.average()
        if (energy <= 0.0 || !energy.isFinite()) return null
        return loudnessForEnergy(energy)
    }

    internal fun samplePeak(): Float = samplePeak

    private fun processFrame(frame: FloatArray) {
        var weightedEnergy = 0.0
        for (channel in 0 until channelCount) {
            val raw = frame[channel].coerceIn(-1f, 1f)
            samplePeak = max(samplePeak, abs(raw))
            val filtered = filters[channel].process(raw.toDouble())
            weightedEnergy += channelWeights[channel] * filtered * filtered
        }

        if (ringCount == windowFrames) {
            rollingEnergy -= energyRing[ringWrite]
        } else {
            ringCount++
        }
        energyRing[ringWrite] = weightedEnergy
        rollingEnergy += weightedEnergy
        ringWrite = (ringWrite + 1) % windowFrames
        framesSeen++

        if (ringCount == windowFrames && framesSeen >= nextBlockAtFrame) {
            blockEnergies += (rollingEnergy / windowFrames.toDouble()).coerceAtLeast(0.0)
            nextBlockAtFrame += stepFrames
        }
    }

    private data class ChannelFilter(
        val shelf: Biquad,
        val highPass: Biquad,
    ) {
        fun process(input: Double): Double = highPass.process(shelf.process(input))
    }

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(input: Double): Double {
            val output = b0 * input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b2 * input - a2 * output
            return output
        }

        companion object {
            /** Same pre-filter coefficient construction used by libebur128. */
            fun kWeightingShelf(sampleRateHz: Int): Biquad {
                val frequencyHz = 1_681.974450955533
                val gainDb = 3.999843853973347
                val q = 0.7071752369554196
                val k = tan(PI * frequencyHz / sampleRateHz)
                val vh = 10.0.pow(gainDb / 20.0)
                val vb = vh.pow(0.4996667741545416)
                val k2 = k * k
                val a0 = 1.0 + k / q + k2
                return Biquad(
                    b0 = (vh + vb * k / q + k2) / a0,
                    b1 = 2.0 * (k2 - vh) / a0,
                    b2 = (vh - vb * k / q + k2) / a0,
                    a1 = 2.0 * (k2 - 1.0) / a0,
                    a2 = (1.0 - k / q + k2) / a0,
                )
            }

            /** Same RLB high-pass coefficient construction used by libebur128. */
            fun kWeightingHighPass(sampleRateHz: Int): Biquad {
                val frequencyHz = 38.13547087602444
                val q = 0.5003270373238773
                val k = tan(PI * frequencyHz / sampleRateHz)
                val k2 = k * k
                val a0 = 1.0 + k / q + k2
                return Biquad(
                    b0 = 1.0,
                    b1 = -2.0,
                    b2 = 1.0,
                    a1 = 2.0 * (k2 - 1.0) / a0,
                    a2 = (1.0 - k / q + k2) / a0,
                )
            }
        }
    }

    companion object {
        private const val ABSOLUTE_GATE_LUFS = -70.0
        private const val RELATIVE_GATE_LU = 10.0
        private const val LOUDNESS_OFFSET = -0.691
        private const val SURROUND_WEIGHT = 1.41

        private fun loudnessForEnergy(energy: Double): Double =
            if (energy <= 0.0) Double.NEGATIVE_INFINITY else LOUDNESS_OFFSET + 10.0 * log10(energy)
    }
}
