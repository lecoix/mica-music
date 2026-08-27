package com.mica.music.media.eq

import com.mica.music.audio.eq.EqBandConstants

import android.media.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

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
    private var filters = createFilters(channelCount)
    private var preampGain = 1.0
    private var globalGainMillibels: Short = EqBandConstants.DEFAULT_GLOBAL_GAIN_MILLIBELS
    private var globalGain = 1.0
    private var ditherState = 0x4D494341

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

    fun setGlobalGainMillibels(millibels: Short) = lock.withLock {
        globalGainMillibels = millibels.coerceIn(
            EqBandConstants.MIN_GLOBAL_GAIN_MILLIBELS,
            EqBandConstants.MAX_GLOBAL_GAIN_MILLIBELS,
        )
        globalGain = 10.0.pow(globalGainMillibels / 2_000.0)
    }

    fun currentGlobalGainMillibels(): Short = lock.withLock { globalGainMillibels }

    fun resetFilters() = lock.withLock { resetFiltersLocked() }

    fun processInterleaved(buffer: ByteArray, offset: Int, length: Int, encoding: Int) {
        if (!enabled || length <= 0) return
        lock.withLock {
            when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> processPcm16Locked(buffer, offset, length)
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> processPcm24Locked(buffer, offset, length)
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
            repeat(channelCount) { channel ->
                var sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort().toInt()
                val x = processSampleLocked(sample / 32768.0, channel)
                sample = (x * 32767.0 + triangularDitherLocked()).toInt().coerceIn(-32768, 32767)
                buffer[index] = (sample and 0xFF).toByte()
                buffer[index + 1] = ((sample shr 8) and 0xFF).toByte()
                index += 2
            }
        }
    }

    private fun processPcm24Locked(buffer: ByteArray, offset: Int, length: Int) {
        val frameCount = length / (3 * channelCount)
        var index = offset
        repeat(frameCount) {
            repeat(channelCount) { channel ->
                var sample = (buffer[index].toInt() and 0xFF) or
                    ((buffer[index + 1].toInt() and 0xFF) shl 8) or
                    (buffer[index + 2].toInt() shl 16)
                val x = processSampleLocked(sample / 8_388_608.0, channel)
                sample = (x * 8_388_607.0 + triangularDitherLocked())
                    .toInt()
                    .coerceIn(-8_388_608, 8_388_607)
                buffer[index] = (sample and 0xFF).toByte()
                buffer[index + 1] = ((sample shr 8) and 0xFF).toByte()
                buffer[index + 2] = ((sample shr 16) and 0xFF).toByte()
                index += 3
            }
        }
    }

    private fun processPcm32Locked(buffer: ByteArray, offset: Int, length: Int) {
        val frameCount = length / (4 * channelCount)
        var index = offset
        repeat(frameCount) {
            repeat(channelCount) { channel ->
                val source = ByteBuffer.wrap(buffer, index, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val x = processSampleLocked(source / 2_147_483_648.0, channel)
                val scaled = x * Int.MAX_VALUE + triangularDitherLocked()
                val sample = scaled.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                ByteBuffer.wrap(buffer, index, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(sample)
                index += 4
            }
        }
    }

    private fun processPcmFloatLocked(buffer: ByteArray, offset: Int, length: Int) {
        val frameCount = length / (4 * channelCount)
        var index = offset
        repeat(frameCount) {
            repeat(channelCount) { channel ->
                var sample = ByteBuffer.wrap(buffer, index, 4).order(ByteOrder.LITTLE_ENDIAN).float
                sample = processSampleLocked(sample.toDouble(), channel).toFloat()
                ByteBuffer.wrap(buffer, index, 4).order(ByteOrder.LITTLE_ENDIAN).putFloat(sample)
                index += 4
            }
        }
    }

    private fun rebuildFiltersLocked() {
        if (filters.size != channelCount) {
            filters = createFilters(channelCount)
        }
        preampGain = 10.0.pow(
            -(levelsMillibels.maxOrNull()?.coerceAtLeast(0) ?: 0) / 2_000.0,
        )
        for (index in levelsMillibels.indices) {
            updateFilterLocked(index)
        }
    }

    private fun updateFilterLocked(index: Int) {
        val gainDb = levelsMillibels[index] / 100f
        filters.forEach { channelFilters ->
            channelFilters[index].setPeaking(
                sampleRate = sampleRateHz.toDouble(),
                centerHz = EqBandConstants.CENTER_HZ[index].toDouble(),
                gainDb = gainDb.toDouble(),
                q = 1.41,
            )
        }
    }

    private fun resetFiltersLocked() {
        filters.forEach { channelFilters ->
            channelFilters.forEach { it.resetState() }
        }
        ditherState = 0x4D494341
    }

    private fun processSampleLocked(source: Double, channel: Int): Double {
        var sample = source * preampGain
        filters[channel.coerceIn(filters.indices)].forEach { sample = it.process(sample) }
        sample *= globalGain
        return softLimit(sample)
    }

    private fun softLimit(value: Double): Double {
        val ceiling = 10.0.pow(-1.0 / 20.0)
        val magnitude = abs(value)
        val knee = ceiling * 0.8
        if (magnitude <= knee) return value
        val limited = knee + (ceiling - knee) *
            tanh((magnitude - knee) / (ceiling - knee))
        return kotlin.math.sign(value) * limited
    }

    private fun triangularDitherLocked(): Double =
        (nextDitherLocked() - nextDitherLocked()) / Int.MAX_VALUE.toDouble()

    private fun nextDitherLocked(): Double {
        ditherState = ditherState * 1_664_525 + 1_013_904_223
        return (ditherState ushr 1).toDouble()
    }

    private fun createFilters(channels: Int): Array<Array<BiquadFilter>> =
        Array(channels.coerceAtLeast(1)) {
            Array(EqBandConstants.BAND_COUNT) { BiquadFilter() }
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
