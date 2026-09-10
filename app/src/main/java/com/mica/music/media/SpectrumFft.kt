package com.mica.music.media

import com.mica.music.audio.spectrum.SPECTRUM_BAND_COUNT
import kotlin.math.*

/** Worker-confined FFT scratch and visual history. Never touched by PCM/lifecycle threads. */
internal class SpectrumFft {
    private val real = FloatArray(WindowSize)
    private val imag = FloatArray(WindowSize)
    private val weightedLevels = FloatArray(BandCount)
    private val contrastLevels = FloatArray(BandCount)
    private val visualLevels = FloatArray(BandCount)
    private val previousLevels = FloatArray(BandCount)
    private val shapedLevels = FloatArray(BandCount)
    private var previousBassEnergy = 0f
    private val window = FloatArray(WindowSize) { i ->
        0.5f - 0.5f * cos((2.0 * PI * i) / (WindowSize - 1)).toFloat()
    }
    fun analyze(samples: FloatArray, sampleRate: Int): Pair<List<Float>, Float> {
        for (i in samples.indices) samples[i] *= window[i]
        val envelope = analyzeEnvelope(samples)
        return shapeBands(analyzeBands(samples, sampleRate)).toList() to envelope
    }
    companion object {
        const val WindowSize = 2048
        private const val BandCount = SPECTRUM_BAND_COUNT
    }
    private fun analyzeEnvelope(samples: FloatArray): Float {
        val start = (samples.size * 0.25f).toInt().coerceIn(0, samples.lastIndex)
        var sumSquares = 0f
        var peak = 0f
        var count = 0
        for (index in start until samples.size) {
            val sample = samples[index]
            sumSquares += sample * sample
            peak = maxOf(peak, kotlin.math.abs(sample))
            count++
        }
        if (count == 0) return 0f
        val rms = sqrt(sumSquares / count).coerceIn(0f, 1f)
        val compressedRms = (ln(1f + rms * 18f) / ln(19f)).coerceIn(0f, 1f)
        val compressedPeak = (ln(1f + peak * 9f) / ln(10f)).coerceIn(0f, 1f)
        return (compressedRms * 0.72f + compressedPeak * 0.28f).coerceIn(0f, 1f)
    }

    private fun analyzeBands(samples: FloatArray, sampleRateHz: Int): FloatArray {
        val out = FloatArray(BandCount)
        for (i in 0 until WindowSize) {
            real[i] = samples[i]
            imag[i] = 0f
        }
        fft(real, imag)

        val nyquist = sampleRateHz / 2f
        val minHz = 50f
        val maxHz = minOf(16_000f, nyquist * 0.92f).coerceAtLeast(minHz + 1f)
        val minLog = ln(minHz)
        val maxLog = ln(maxHz)
        val binHz = sampleRateHz.toFloat() / WindowSize
        for (i in 0 until BandCount) {
            val leftT = i / BandCount.toFloat()
            val rightT = (i + 1) / BandCount.toFloat()
            val leftHz = kotlin.math.exp(minLog + (maxLog - minLog) * leftT)
            val rightHz = kotlin.math.exp(minLog + (maxLog - minLog) * rightT)
            val startBin = maxOf(1, (leftHz / binHz).toInt())
            val endBin = minOf(WindowSize / 2 - 1, kotlin.math.ceil(rightHz / binHz).toInt())
            var energy = 0f
            var bins = 0
            for (bin in startBin..endBin) {
                val re = real[bin]
                val im = imag[bin]
                energy += re * re + im * im
                bins++
            }
            val magnitude = if (bins > 0) {
                sqrt(energy / bins) / WindowSize * 2f
            } else {
                1e-7f
            }.coerceAtLeast(1e-7f)
            val db = 20f * log10(magnitude)
            out[i] = ((db + 52f) / 44f).coerceIn(0f, 1f)
        }
        return out
    }

    private fun shapeBands(raw: FloatArray): FloatArray {
        // 主分支视觉权重：压低天然占优的低频，中频最突出，高频逐步回落。
        for (i in 0 until BandCount) {
            val t = i / (BandCount - 1f)
            val presence = when {
                t < 0.08f -> 0.46f
                t < 0.20f -> 0.60f
                t < 0.55f -> 0.88f
                t < 0.80f -> 0.78f
                else -> 0.65f - (t - 0.80f) * 0.55f
            }.coerceIn(0.40f, 0.92f)
            val weighted = raw[i].coerceIn(0f, 1f).pow(1.5f) * presence
            weightedLevels[i] = weighted / (1f + weighted * 0.3f)
        }

        // 主分支高对比参数：突出局部波峰，压低波谷。
        for (i in 0 until BandCount) {
            val from = maxOf(0, i - 2)
            val to = minOf(BandCount - 1, i + 2)
            var localSum = 0f
            var count = 0
            for (j in from..to) {
                localSum += weightedLevels[j]
                count++
            }
            val localAvg = if (count > 0) localSum / count else weightedLevels[i]
            val value = weightedLevels[i]
            val prominence = (value - localAvg * 0.7f).coerceAtLeast(0f)
            val base = if (value < localAvg) value * 0.06f else value * 0.22f
            contrastLevels[i] = (base + prominence * 3.6f).coerceIn(0f, 1f)
        }

        val bassEnergy = weightedLevels.take(7).average().toFloat().coerceIn(0f, 1f)
        val beatLift = (bassEnergy - previousBassEnergy * 0.82f).coerceAtLeast(0f)
            .coerceIn(0f, 0.42f)
        previousBassEnergy = previousBassEnergy * 0.72f + bassEnergy * 0.28f

        for (i in 0 until BandCount) {
            val t = i / (BandCount - 1f)
            val rhythmReach = (1f - t * 0.95f).coerceIn(0f, 1f)
            val pulse = beatLift * rhythmReach * 0.68f
            visualLevels[i] = (contrastLevels[i] + pulse).coerceIn(0f, 1f)
        }

        // 主分支快攻慢放参数。
        for (i in 0 until BandCount) {
            val lifted = visualLevels[i]
            val attack = if (lifted > previousLevels[i]) 0.85f else 0.28f
            shapedLevels[i] = previousLevels[i] + (lifted - previousLevels[i]) * attack
        }

        // 窄核平滑：保留峰谷起伏，仅消除单条噪点
        for (i in 0 until BandCount) {
            val left = shapedLevels[maxOf(0, i - 1)]
            val center = shapedLevels[i]
            val right = shapedLevels[minOf(BandCount - 1, i + 1)]
            previousLevels[i] = (left * 0.12f + center * 0.76f + right * 0.12f)
                .coerceIn(0f, 1f)
        }
        return previousLevels
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        var j = 0
        for (i in 1 until WindowSize) {
            var bit = WindowSize shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal
                val tempImag = imag[i]
                imag[i] = imag[j]
                imag[j] = tempImag
            }
        }

        var length = 2
        while (length <= WindowSize) {
            val angle = (-2.0 * PI / length).toFloat()
            val wLengthReal = cos(angle)
            val wLengthImag = kotlin.math.sin(angle)
            var i = 0
            while (i < WindowSize) {
                var wReal = 1f
                var wImag = 0f
                val half = length / 2
                for (k in 0 until half) {
                    val even = i + k
                    val odd = even + half
                    val oddReal = real[odd] * wReal - imag[odd] * wImag
                    val oddImag = real[odd] * wImag + imag[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imag[odd] = imag[even] - oddImag
                    real[even] += oddReal
                    imag[even] += oddImag
                    val nextReal = wReal * wLengthReal - wImag * wLengthImag
                    wImag = wReal * wLengthImag + wImag * wLengthReal
                    wReal = nextReal
                }
                i += length
            }
            length = length shl 1
        }
    }

    private fun Float.format1(): String = String.format("%.1f", this)

    private fun Float.format2(): String = String.format("%.2f", this)
}
