package com.mica.music.media.eq

import android.media.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 10 段 peaking biquad 软件均衡器，供 PCM 流与 Media3 [SoftwareEqualizerAudioProcessor] 共用。
 */
@UnstableApi
class SoftwareEqualizer {

    private val lock = ReentrantLock()
    private var enabled = false
    private var sampleRateHz = 44_100
    private var channelCount = 2
    private val levelsMillibels = EqBandConstants.defaultLevels()
    private val filters = Array(EqBandConstants.BAND_COUNT) { BiquadFilter() }

    fun setEnabled(value: Boolean) = lock.withLock { enabled = value }

    fun isEnabled(): Boolean = lock.withLock { enabled }

    fun configure(sampleRateHz: Int, channelCount: Int) = lock.withLock {
        if (this.sampleRateHz == sampleRateHz && this.channelCount == channelCount) return@withLock
        this.sampleRateHz = sampleRateHz.coerceAtLeast(1)
        this.channelCount = channelCount.coerceAtLeast(1)
        rebuildFiltersLocked()
        resetFiltersLocked()
    }

    fun setLevels(levels: ShortArray) = lock.withLock {
        if (levels.size != EqBandConstants.BAND_COUNT) return@withLock
        levels.copyInto(levelsMillibels)
        rebuildFiltersLocked()
    }

    fun setBandLevel(index: Int, millibels: Short) = lock.withLock {
        if (index !in levelsMillibels.indices) return@withLock
        levelsMillibels[index] = millibels.coerceIn(EqBandConstants.MIN_MILLIBELS, EqBandConstants.MAX_MILLIBELS)
        updateFilterLocked(index)
    }

    fun currentLevels(): ShortArray = lock.withLock { levelsMillibels.copyOf() }

    fun resetFilters() = lock.withLock { resetFiltersLocked() }

    fun processInterleaved(buffer: ByteArray, offset: Int, length: Int, encoding: Int) {
        if (!enabled || length <= 0) return
        lock.withLock {
            when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> processPcm16Locked(buffer, offset, length)
                AudioFormat.ENCODING_PCM_32BIT -> processPcm32Locked(buffer, offset, length)
                AudioFormat.ENCODING_PCM_FLOAT -> processPcmFloatLocked(buffer, offset, length)
                else -> Unit
            }
        }
    }

    fun processMedia3Buffer(input: ByteBuffer, encoding: Int, output: ByteBuffer) {
        if (!enabled) {
            output.put(input)
            return
        }
        val remaining = input.remaining()
        if (remaining <= 0) return
        val array = ByteArray(remaining)
        input.get(array)
        processInterleaved(array, 0, remaining, encoding)
        output.put(array)
    }

    private fun processPcm16Locked(buffer: ByteArray, offset: Int, length: Int) {
        val frameCount = length / (2 * channelCount)
        var index = offset
        repeat(frameCount) {
            repeat(channelCount) { _ ->
                var sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort().toInt()
                var x = sample / 32768.0
                filters.forEach { x = it.process(x) }
                sample = (x * 32768.0).toInt().coerceIn(-32768, 32767)
                buffer[index] = (sample and 0xFF).toByte()
                buffer[index + 1] = ((sample shr 8) and 0xFF).toByte()
                index += 2
            }
        }
    }

    private fun processPcm32Locked(buffer: ByteArray, offset: Int, length: Int) {
        val frameCount = length / (4 * channelCount)
        var index = offset
        repeat(frameCount) {
            repeat(channelCount) { _ ->
                var sample = ByteBuffer.wrap(buffer, index, 4).order(ByteOrder.LITTLE_ENDIAN).int
                var x = sample / 2_147_483_648.0
                filters.forEach { x = it.process(x) }
                sample = (x * 2_147_483_648.0).toInt().coerceIn(Int.MIN_VALUE, Int.MAX_VALUE)
                ByteBuffer.wrap(buffer, index, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(sample)
                index += 4
            }
        }
    }

    private fun processPcmFloatLocked(buffer: ByteArray, offset: Int, length: Int) {
        val frameCount = length / (4 * channelCount)
        var index = offset
        repeat(frameCount) {
            repeat(channelCount) { _ ->
                var sample = ByteBuffer.wrap(buffer, index, 4).order(ByteOrder.LITTLE_ENDIAN).float
                var x = sample.toDouble()
                filters.forEach { x = it.process(x) }
                sample = x.toFloat().coerceIn(-1f, 1f)
                ByteBuffer.wrap(buffer, index, 4).order(ByteOrder.LITTLE_ENDIAN).putFloat(sample)
                index += 4
            }
        }
    }

    private fun rebuildFiltersLocked() {
        for (index in filters.indices) {
            updateFilterLocked(index)
        }
    }

    private fun updateFilterLocked(index: Int) {
        val gainDb = levelsMillibels[index] / 100f
        filters[index].setPeaking(
            sampleRate = sampleRateHz.toDouble(),
            centerHz = EqBandConstants.CENTER_HZ[index].toDouble(),
            gainDb = gainDb.toDouble(),
            q = 1.41,
        )
    }

    private fun resetFiltersLocked() {
        filters.forEach { it.resetState() }
    }

    private class BiquadFilter {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun setPeaking(sampleRate: Double, centerHz: Double, gainDb: Double, q: Double) {
            if (gainDb == 0.0) {
                b0 = 1.0
                b1 = 0.0
                b2 = 0.0
                a1 = 0.0
                a2 = 0.0
                return
            }
            val a = 10.0.pow(gainDb / 40.0)
            val omega = 2.0 * PI * centerHz / sampleRate
            val sinW = sin(omega)
            val cosW = cos(omega)
            val alpha = sinW / (2.0 * q)
            val b0n = 1.0 + alpha * a
            val b1n = -2.0 * cosW
            val b2n = 1.0 - alpha * a
            val a0n = 1.0 + alpha / a
            val a1n = -2.0 * cosW
            val a2n = 1.0 - alpha / a
            b0 = b0n / a0n
            b1 = b1n / a0n
            b2 = b2n / a0n
            a1 = a1n / a0n
            a2 = a2n / a0n
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        fun resetState() {
            x1 = 0.0
            x2 = 0.0
            y1 = 0.0
            y2 = 0.0
        }
    }
}
