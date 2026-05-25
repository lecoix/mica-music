package com.mica.music.media

import android.media.AudioFormat
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

object MicaSpectrumAnalyzer {
    private const val BandCount = 96
    private const val WindowSize = 2048
    private const val MinUpdateIntervalNanos = 8_000_000L
    private const val SilenceDecay = 0.88f
    private const val ProbeTag = "MicaSpectrumProbe"
    private const val ProbeEnabled = true

    private val _levels = MutableStateFlow(List(BandCount) { 0f })
    val levels: StateFlow<List<Float>> = _levels.asStateFlow()

    @Volatile
    private var enabled = false

    @Volatile
    private var lastUpdateNanos = 0L

    private val ring = FloatArray(WindowSize)
    private val windowed = FloatArray(WindowSize)
    private val window = FloatArray(WindowSize) { index ->
        0.5f - 0.5f * cos((2.0 * PI * index) / (WindowSize - 1)).toFloat()
    }
    private val real = FloatArray(WindowSize)
    private val imag = FloatArray(WindowSize)
    private val weightedLevels = FloatArray(BandCount)
    private val contrastLevels = FloatArray(BandCount)
    private val visualLevels = FloatArray(BandCount)
    private val previousLevels = FloatArray(BandCount)
    private val shapedLevels = FloatArray(BandCount)
    private var previousBassEnergy = 0f
    private val lock = Any()
    private var ringWriteIndex = 0
    private var ringSampleCount = 0
    private var probeWindowStartMs = 0L
    private var probeProcessCalls = 0
    private var probeOutputFrames = 0
    private var probePcmBytes = 0L
    private var probeAnalyzeNanos = 0L

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            synchronized(lock) {
                ring.fill(0f)
                windowed.fill(0f)
                real.fill(0f)
                imag.fill(0f)
                weightedLevels.fill(0f)
                contrastLevels.fill(0f)
                visualLevels.fill(0f)
                previousLevels.fill(0f)
                shapedLevels.fill(0f)
                previousBassEnergy = 0f
                ringWriteIndex = 0
                ringSampleCount = 0
                lastUpdateNanos = 0L
                resetProbe()
                _levels.value = List(BandCount) { 0f }
            }
        }
    }

    fun processPcmBuffer(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        encoding: Int,
        sampleRateHz: Int,
        channelCount: Int,
    ) {
        if (!enabled || length <= 0 || sampleRateHz <= 0) return
        val nowNanos = System.nanoTime()
        val nowMs = System.currentTimeMillis()
        synchronized(lock) {
            if (ProbeEnabled) {
                if (probeWindowStartMs == 0L) probeWindowStartMs = nowMs
                probeProcessCalls++
                probePcmBytes += length
            }
            appendMonoSamples(
                buffer = buffer,
                offset = offset,
                length = length,
                encoding = encoding,
                channelCount = channelCount.coerceAtLeast(1),
            )
            if (nowNanos - lastUpdateNanos < MinUpdateIntervalNanos) return
            lastUpdateNanos = nowNanos
            if (ringSampleCount < WindowSize / 2) {
                decayToSilence()
                return
            }
            val analyzeStart = if (ProbeEnabled) System.nanoTime() else 0L
            copyWindowedSamples()
            val next = shapeBands(analyzeBands(windowed, sampleRateHz))
            if (ProbeEnabled) {
                probeAnalyzeNanos += System.nanoTime() - analyzeStart
                probeOutputFrames++
                reportProbeIfNeeded(nowMs, sampleRateHz)
            }
            _levels.value = next.map { it.coerceIn(0f, 1f) }
        }
    }

    private fun resetProbe() {
        probeWindowStartMs = 0L
        probeProcessCalls = 0
        probeOutputFrames = 0
        probePcmBytes = 0L
        probeAnalyzeNanos = 0L
    }

    private fun reportProbeIfNeeded(nowMs: Long, sampleRateHz: Int) {
        val start = probeWindowStartMs
        val elapsedMs = nowMs - start
        if (elapsedMs < 1_000L) return
        val seconds = elapsedMs / 1_000f
        val outputFps = probeOutputFrames / seconds
        val callFps = probeProcessCalls / seconds
        val kbps = probePcmBytes / 1024f / seconds
        val avgAnalyzeMs = if (probeOutputFrames > 0) {
            probeAnalyzeNanos / probeOutputFrames / 1_000_000f
        } else {
            0f
        }
        Log.d(
            ProbeTag,
            "analysis fps=${outputFps.format1()} calls=${callFps.format1()} " +
                "pcmKBps=${kbps.format1()} avgAnalyzeMs=${avgAnalyzeMs.format2()} " +
                "sr=$sampleRateHz window=$WindowSize bands=$BandCount",
        )
        resetProbe()
        probeWindowStartMs = nowMs
    }

    private fun decayToSilence() {
        for (i in 0 until BandCount) {
            previousLevels[i] *= SilenceDecay
            shapedLevels[i] *= SilenceDecay
        }
        _levels.value = previousLevels.map { it.coerceIn(0f, 1f) }
    }

    private fun appendMonoSamples(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        encoding: Int,
        channelCount: Int,
    ) {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT -> 4
            else -> 2
        }
        val frameBytes = bytesPerSample * channelCount
        if (frameBytes <= 0) return
        val frameCount = length / frameBytes
        if (frameCount <= 0) return
        for (frame in 0 until frameCount) {
            var sum = 0f
            for (ch in 0 until channelCount) {
                val pos = offset + frame * frameBytes + ch * bytesPerSample
                sum += readSample(buffer, pos, encoding)
            }
            ring[ringWriteIndex] = sum / channelCount
            ringWriteIndex = (ringWriteIndex + 1) % WindowSize
            ringSampleCount = (ringSampleCount + 1).coerceAtMost(WindowSize)
        }
    }

    private fun copyWindowedSamples() {
        val available = ringSampleCount.coerceAtMost(WindowSize)
        val start = (ringWriteIndex - available + WindowSize) % WindowSize
        val silence = WindowSize - available
        for (i in 0 until silence) {
            windowed[i] = 0f
        }
        for (i in 0 until available) {
            val sourceIndex = (start + i) % WindowSize
            val targetIndex = silence + i
            windowed[targetIndex] = ring[sourceIndex] * window[targetIndex]
        }
    }

    private fun readSample(buffer: ByteArray, pos: Int, encoding: Int): Float {
        if (pos !in buffer.indices) return 0f
        return when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> {
                ((buffer[pos].toInt() and 0xff) - 128) / 128f
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                if (pos + 2 >= buffer.size) 0f else {
                    val raw = (buffer[pos].toInt() and 0xff) or
                        ((buffer[pos + 1].toInt() and 0xff) shl 8) or
                        (buffer[pos + 2].toInt() shl 16)
                    raw / 8_388_608f
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                if (pos + 3 >= buffer.size) 0f else {
                    val raw = (buffer[pos].toInt() and 0xff) or
                        ((buffer[pos + 1].toInt() and 0xff) shl 8) or
                        ((buffer[pos + 2].toInt() and 0xff) shl 16) or
                        (buffer[pos + 3].toInt() shl 24)
                    raw / 2_147_483_648f
                }
            }
            else -> {
                if (pos + 1 >= buffer.size) 0f else {
                    val raw = (buffer[pos].toInt() and 0xff) or
                        (buffer[pos + 1].toInt() shl 8)
                    raw / 32768f
                }
            }
        }
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
        // 低频抑制：音乐中 bass 能量天然占优，需要压低以留出峰谷空间
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

        // 高对比：波峰突出、波谷明显
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
            contrastLevels[i] = (base + prominence * 3.6f)
                .coerceIn(0f, 1f)
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

        // 快攻慢放
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
