package com.mica.music.audio.eq

import android.media.AudioFormat
import androidx.media3.common.util.UnstableApi
import com.mica.music.audio.eq.EqBandConstants
import com.mica.music.audio.fx.SoundFxSettings
import com.mica.music.media.fx.SoundFxEngine
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * PixelPlayer-compatible Android five-band graphic EQ model for Mica's shared PCM paths.
 * Ten UI sliders are paired into the five broad legacy Android EQ bands before DSP.
 *
 * Control changes are published atomically and consumed at audio-buffer boundaries. Biquad history,
 * gain smoothing and limiter envelope are owned by the audio thread; UI changes never lock it.
 */
@UnstableApi
class SoftwareEqualizer {

    private data class TargetSettings(
        val enabled: Boolean,
        val levelsMillibels: ShortArray,
        val globalGainMillibels: Short,
        val replayGainHostEnabled: Boolean,
        val replayGainLinearFactor: Float,
        val channelBalancePercent: Int,
        val soundFx: SoundFxSettings,
        val revision: Long,
    )

    private val targetSettings = AtomicReference(
        TargetSettings(
            enabled = false,
            levelsMillibels = EqBandConstants.defaultLevels(),
            globalGainMillibels = EqBandConstants.DEFAULT_GLOBAL_GAIN_MILLIBELS,
            replayGainHostEnabled = false,
            replayGainLinearFactor = 1f,
            channelBalancePercent = 0,
            soundFx = SoundFxSettings(),
            revision = 0L,
        ),
    )

    // Audio-thread-owned state below this line.
    private var sampleRateHz = 44_100
    private var channelCount = 2
    private var filters = createFilters(channelCount)
    private var frameScratch = DoubleArray(channelCount)
    private val limiter = LinkedPeakLimiter()
    private val soundFxEngine = SoundFxEngine()
    private var appliedSoundFxActive = false
    private var appliedRevision = Long.MIN_VALUE
    private var appliedEnabled = false
    private var appliedFlat = true
    private var targetPreampGain = 1.0
    private var currentPreampGain = 1.0
    private var targetGlobalGain = 1.0
    private var currentGlobalGain = 1.0
    private var targetReplayGain = 1.0
    private var currentReplayGain = 1.0
    private var targetLeftBalanceGain = 1.0
    private var currentLeftBalanceGain = 1.0
    private var targetRightBalanceGain = 1.0
    private var currentRightBalanceGain = 1.0
    private var gainSmoothingCoefficient = smoothingCoefficient(sampleRateHz)
    private var ditherState = 0x4D494341

    init {
        limiter.configure(sampleRateHz)
    }

    fun setEnabled(value: Boolean) {
        updateTarget { old -> old.copy(enabled = value, revision = old.revision + 1L) }
    }

    fun isEnabled(): Boolean = targetSettings.get().enabled

    fun configure(sampleRateHz: Int, channelCount: Int) {
        val safeSampleRate = sampleRateHz.coerceAtLeast(1)
        val safeChannelCount = channelCount.coerceAtLeast(1)
        if (this.sampleRateHz == safeSampleRate && this.channelCount == safeChannelCount) return
        this.sampleRateHz = safeSampleRate
        this.channelCount = safeChannelCount
        filters = createFilters(safeChannelCount)
        frameScratch = DoubleArray(safeChannelCount)
        gainSmoothingCoefficient = smoothingCoefficient(safeSampleRate)
        limiter.configure(safeSampleRate)
        soundFxEngine.configure(safeSampleRate, safeChannelCount)
        appliedRevision = Long.MIN_VALUE
        applyPendingSettings(snapGains = true)
        resetDspState()
    }

    fun setLevels(levels: ShortArray) {
        if (levels.size != EqBandConstants.BAND_COUNT) return
        val clamped = ShortArray(EqBandConstants.BAND_COUNT) { index ->
            levels[index].coerceIn(EqBandConstants.MIN_MILLIBELS, EqBandConstants.MAX_MILLIBELS)
        }
        updateTarget { old ->
            old.copy(levelsMillibels = clamped, revision = old.revision + 1L)
        }
    }

    fun setBandLevel(index: Int, millibels: Short) {
        if (index !in 0 until EqBandConstants.BAND_COUNT) return
        updateTarget { old ->
            val levels = old.levelsMillibels.copyOf()
            levels[index] = millibels.coerceIn(EqBandConstants.MIN_MILLIBELS, EqBandConstants.MAX_MILLIBELS)
            old.copy(levelsMillibels = levels, revision = old.revision + 1L)
        }
    }

    fun currentLevels(): ShortArray = targetSettings.get().levelsMillibels.copyOf()

    fun setGlobalGainMillibels(millibels: Short) {
        val clamped = millibels.coerceIn(
            EqBandConstants.MIN_GLOBAL_GAIN_MILLIBELS,
            EqBandConstants.MAX_GLOBAL_GAIN_MILLIBELS,
        )
        updateTarget { old ->
            old.copy(globalGainMillibels = clamped, revision = old.revision + 1L)
        }
    }

    fun currentGlobalGainMillibels(): Short = targetSettings.get().globalGainMillibels

    fun setReplayGain(enabled: Boolean, factor: Float) {
        val safe = factor.takeIf { it.isFinite() && it >= 0f }?.coerceAtMost(16f) ?: 1f
        updateTarget { old ->
            if (old.replayGainHostEnabled == enabled && old.replayGainLinearFactor == safe) old
            else old.copy(
                replayGainHostEnabled = enabled,
                replayGainLinearFactor = safe,
                revision = old.revision + 1L,
            )
        }
    }

    fun isReplayGainHostEnabled(): Boolean = targetSettings.get().replayGainHostEnabled

    fun replayGainLinearFactor(): Float = targetSettings.get().replayGainLinearFactor

    fun setChannelBalancePercent(percent: Int) {
        val safe = percent.coerceIn(-100, 100)
        updateTarget { old ->
            if (old.channelBalancePercent == safe) old
            else old.copy(channelBalancePercent = safe, revision = old.revision + 1L)
        }
    }

    fun channelBalancePercent(): Int = targetSettings.get().channelBalancePercent

    fun setSoundFx(settings: SoundFxSettings) {
        val sanitized = settings.sanitized()
        updateTarget { old ->
            if (old.soundFx == sanitized) old
            else old.copy(soundFx = sanitized, revision = old.revision + 1L)
        }
    }

    fun soundFxSettings(): SoundFxSettings = targetSettings.get().soundFx

    fun isSoundFxDspActive(): Boolean = targetSettings.get().soundFx.isDspActive()

    fun isProcessingRequired(): Boolean {
        val settings = targetSettings.get()
        val eqActive = settings.enabled && (
            settings.levelsMillibels.any { it != 0.toShort() } ||
                settings.globalGainMillibels != 0.toShort()
            )
        val balanceActive = channelCount >= 2 && settings.channelBalancePercent != 0
        return eqActive || settings.replayGainHostEnabled || balanceActive || settings.soundFx.isDspActive()
    }

    /** Called by the renderer/audio thread when its stream is flushed or reconfigured. */
    fun resetFilters() {
        applyPendingSettings(snapGains = true)
        resetDspState()
    }

    fun processInterleaved(buffer: ByteArray, offset: Int, length: Int, encoding: Int) {
        if (length <= 0) return
        applyPendingSettings()
        if (!processingActive()) return
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> processPcm16(buffer, offset, length)
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> processPcm24(buffer, offset, length)
            AudioFormat.ENCODING_PCM_32BIT -> processPcm32(buffer, offset, length)
            AudioFormat.ENCODING_PCM_FLOAT -> processPcmFloat(buffer, offset, length)
            else -> Unit
        }
    }

    fun processMedia3Buffer(input: ByteBuffer, encoding: Int, output: ByteBuffer) {
        applyPendingSettings()
        if (!processingActive()) {
            output.put(input)
            return
        }
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> processPcm16(input, output)
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> processPcm24(input, output)
            AudioFormat.ENCODING_PCM_32BIT -> processPcm32(input, output)
            AudioFormat.ENCODING_PCM_FLOAT -> processPcmFloat(input, output)
            else -> output.put(input)
        }
    }

    private fun processPcm16(buffer: ByteArray, offset: Int, length: Int) {
        val frameBytes = 2 * channelCount
        val frameCount = length / frameBytes
        var index = offset
        repeat(frameCount) {
            repeat(channelCount) { channel ->
                val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF))
                    .toShort()
                    .toInt()
                frameScratch[channel] = sample / 32768.0
                index += 2
            }
            processFrame()
            index -= frameBytes
            repeat(channelCount) { channel ->
                val sample = (frameScratch[channel] * 32767.0 + triangularDither())
                    .toInt()
                    .coerceIn(-32768, 32767)
                buffer[index] = (sample and 0xFF).toByte()
                buffer[index + 1] = ((sample shr 8) and 0xFF).toByte()
                index += 2
            }
        }
    }

    private fun processPcm24(buffer: ByteArray, offset: Int, length: Int) {
        val frameBytes = 3 * channelCount
        val frameCount = length / frameBytes
        var index = offset
        repeat(frameCount) {
            repeat(channelCount) { channel ->
                val sample = readPcm24(buffer, index)
                frameScratch[channel] = sample / 8_388_608.0
                index += 3
            }
            processFrame()
            index -= frameBytes
            repeat(channelCount) { channel ->
                val sample = (frameScratch[channel] * 8_388_607.0 + triangularDither())
                    .toInt()
                    .coerceIn(-8_388_608, 8_388_607)
                writePcm24(buffer, index, sample)
                index += 3
            }
        }
    }

    private fun processPcm32(buffer: ByteArray, offset: Int, length: Int) {
        val frameBytes = 4 * channelCount
        val frameCount = length / frameBytes
        var index = offset
        repeat(frameCount) {
            repeat(channelCount) { channel ->
                frameScratch[channel] = readIntLe(buffer, index) / 2_147_483_648.0
                index += 4
            }
            processFrame()
            index -= frameBytes
            repeat(channelCount) { channel ->
                val scaled = frameScratch[channel] * Int.MAX_VALUE + triangularDither()
                val sample = scaled.toLong()
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                    .toInt()
                writeIntLe(buffer, index, sample)
                index += 4
            }
        }
    }

    private fun processPcmFloat(buffer: ByteArray, offset: Int, length: Int) {
        val frameBytes = 4 * channelCount
        val frameCount = length / frameBytes
        var index = offset
        repeat(frameCount) {
            repeat(channelCount) { channel ->
                frameScratch[channel] = Float.fromBits(readIntLe(buffer, index)).toDouble()
                index += 4
            }
            processFrame()
            index -= frameBytes
            repeat(channelCount) { channel ->
                writeIntLe(buffer, index, frameScratch[channel].toFloat().toBits())
                index += 4
            }
        }
    }

    private fun processPcm16(input: ByteBuffer, output: ByteBuffer) {
        val frameBytes = 2 * channelCount
        val frameCount = input.remaining() / frameBytes
        repeat(frameCount) {
            repeat(channelCount) { channel ->
                val lo = input.get().toInt() and 0xFF
                val hi = input.get().toInt()
                frameScratch[channel] = ((hi shl 8) or lo).toShort().toInt() / 32768.0
            }
            processFrame()
            repeat(channelCount) { channel ->
                val sample = (frameScratch[channel] * 32767.0 + triangularDither())
                    .toInt()
                    .coerceIn(-32768, 32767)
                output.put((sample and 0xFF).toByte())
                output.put(((sample shr 8) and 0xFF).toByte())
            }
        }
        if (input.hasRemaining()) output.put(input)
    }

    private fun processPcm24(input: ByteBuffer, output: ByteBuffer) {
        val frameBytes = 3 * channelCount
        val frameCount = input.remaining() / frameBytes
        repeat(frameCount) {
            repeat(channelCount) { channel ->
                val b0 = input.get().toInt() and 0xFF
                val b1 = input.get().toInt() and 0xFF
                val b2 = input.get().toInt()
                frameScratch[channel] = (b0 or (b1 shl 8) or (b2 shl 16)) / 8_388_608.0
            }
            processFrame()
            repeat(channelCount) { channel ->
                val sample = (frameScratch[channel] * 8_388_607.0 + triangularDither())
                    .toInt()
                    .coerceIn(-8_388_608, 8_388_607)
                output.put((sample and 0xFF).toByte())
                output.put(((sample shr 8) and 0xFF).toByte())
                output.put(((sample shr 16) and 0xFF).toByte())
            }
        }
        if (input.hasRemaining()) output.put(input)
    }

    private fun processPcm32(input: ByteBuffer, output: ByteBuffer) {
        val frameBytes = 4 * channelCount
        val frameCount = input.remaining() / frameBytes
        repeat(frameCount) {
            repeat(channelCount) { channel ->
                frameScratch[channel] = readIntLe(input) / 2_147_483_648.0
            }
            processFrame()
            repeat(channelCount) { channel ->
                val scaled = frameScratch[channel] * Int.MAX_VALUE + triangularDither()
                writeIntLe(
                    output,
                    scaled.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
                )
            }
        }
        if (input.hasRemaining()) output.put(input)
    }

    private fun processPcmFloat(input: ByteBuffer, output: ByteBuffer) {
        val frameBytes = 4 * channelCount
        val frameCount = input.remaining() / frameBytes
        repeat(frameCount) {
            repeat(channelCount) { channel ->
                frameScratch[channel] = Float.fromBits(readIntLe(input)).toDouble()
            }
            processFrame()
            repeat(channelCount) { channel ->
                writeIntLe(output, frameScratch[channel].toFloat().toBits())
            }
        }
        if (input.hasRemaining()) output.put(input)
    }

    private fun processFrame() {
        currentPreampGain = smoothGain(currentPreampGain, targetPreampGain)
        currentGlobalGain = smoothGain(currentGlobalGain, targetGlobalGain)
        currentReplayGain = smoothGain(currentReplayGain, targetReplayGain)
        currentLeftBalanceGain = smoothGain(currentLeftBalanceGain, targetLeftBalanceGain)
        currentRightBalanceGain = smoothGain(currentRightBalanceGain, targetRightBalanceGain)
        val eqActive = appliedEnabled && !appliedFlat
        repeat(channelCount) { channel ->
            var sample = frameScratch[channel]
            if (eqActive) {
                sample *= currentPreampGain
                filters[channel].forEach { filter -> sample = filter.process(sample) }
                sample *= currentGlobalGain
            }
            frameScratch[channel] = sample * currentReplayGain
        }
        if (channelCount >= 2) {
            frameScratch[0] *= currentLeftBalanceGain
            frameScratch[1] *= currentRightBalanceGain
        }
        if (appliedSoundFxActive) {
            soundFxEngine.processFrame(frameScratch, channelCount)
        }
        if (eqActive || abs(currentReplayGain - 1.0) > 1e-9 || appliedSoundFxActive) {
            limiter.processFrame(frameScratch, channelCount)
        }
    }

    private fun applyPendingSettings(snapGains: Boolean = false) {
        val settings = targetSettings.get()
        if (settings.revision == appliedRevision) {
            if (snapGains) {
                currentPreampGain = targetPreampGain
                currentGlobalGain = targetGlobalGain
                currentReplayGain = targetReplayGain
                currentLeftBalanceGain = targetLeftBalanceGain
                currentRightBalanceGain = targetRightBalanceGain
            }
            return
        }

        val wasProcessingActive = processingActive()
        val enabledChanged = settings.enabled != appliedEnabled
        appliedEnabled = settings.enabled
        appliedFlat = settings.levelsMillibels.all { it == 0.toShort() } &&
            settings.globalGainMillibels == 0.toShort()

        val deviceLevels = AndroidFiveBandEqModel.collapseUiLevels(settings.levelsMillibels)
        val plan = EqHeadroomPlanner.plan(settings.levelsMillibels, sampleRateHz)
        targetPreampGain = 10.0.pow(plan.preampDb / 20.0)
        targetGlobalGain = 10.0.pow(settings.globalGainMillibels / 2_000.0)
        targetReplayGain = settings.replayGainLinearFactor.toDouble()
        val balance = settings.channelBalancePercent.coerceIn(-100, 100)
        targetLeftBalanceGain = if (balance > 0) 1.0 - balance / 100.0 else 1.0
        targetRightBalanceGain = if (balance < 0) 1.0 + balance / 100.0 else 1.0
        appliedSoundFxActive = settings.soundFx.isDspActive()
        soundFxEngine.setSettings(settings.soundFx)

        deviceLevels.indices.forEach { index ->
            updateFilter(index, deviceLevels[index])
        }

        if (snapGains || appliedRevision == Long.MIN_VALUE || enabledChanged || appliedFlat) {
            currentPreampGain = targetPreampGain
            currentGlobalGain = targetGlobalGain
        }
        val processingNow = processingActive()
        if (snapGains || appliedRevision == Long.MIN_VALUE || !processingNow) {
            currentReplayGain = targetReplayGain
            currentLeftBalanceGain = targetLeftBalanceGain
            currentRightBalanceGain = targetRightBalanceGain
        }
        if (!processingNow || wasProcessingActive != processingNow || enabledChanged) {
            resetDspState()
        }
        appliedRevision = settings.revision
    }

    private fun processingActive(): Boolean {
        val balanceActive = channelCount >= 2 &&
            (targetLeftBalanceGain != 1.0 || targetRightBalanceGain != 1.0)
        return (appliedEnabled && !appliedFlat) || targetReplayGain != 1.0 || balanceActive || appliedSoundFxActive
    }

    private fun updateFilter(index: Int, millibels: Short) {
        val gainDb = millibels / 100.0
        val maxCenter = sampleRateHz * 0.5 * 0.99
        val centerHz = AndroidFiveBandEqModel.CENTER_HZ[index].toDouble().coerceIn(1.0, maxCenter)
        filters.forEach { channelFilters ->
            channelFilters[index].setPeaking(
                sampleRate = sampleRateHz.toDouble(),
                centerHz = centerHz,
                gainDb = gainDb,
                q = AndroidFiveBandEqModel.Q,
            )
        }
    }

    private fun resetDspState() {
        filters.forEach { channelFilters -> channelFilters.forEach { it.resetState() } }
        limiter.reset()
        soundFxEngine.reset()
        ditherState = 0x4D494341
    }

    private fun smoothGain(current: Double, target: Double): Double {
        if (abs(current - target) < 1e-9) return target
        return target + (current - target) * gainSmoothingCoefficient
    }

    private fun triangularDither(): Double =
        (nextDither() - nextDither()) / Int.MAX_VALUE.toDouble()

    private fun nextDither(): Double {
        ditherState = ditherState * 1_664_525 + 1_013_904_223
        return (ditherState ushr 1).toDouble()
    }

    private fun updateTarget(transform: (TargetSettings) -> TargetSettings) {
        while (true) {
            val old = targetSettings.get()
            val updated = transform(old)
            if (targetSettings.compareAndSet(old, updated)) return
        }
    }

    private fun createFilters(channels: Int): Array<Array<BiquadFilter>> =
        Array(channels.coerceAtLeast(1)) {
            Array(AndroidFiveBandEqModel.BAND_COUNT) { BiquadFilter() }
        }

    private fun smoothingCoefficient(sampleRateHz: Int): Double {
        val smoothingSeconds = GAIN_SMOOTHING_MS / 1_000.0
        return exp(-1.0 / (sampleRateHz.coerceAtLeast(1) * smoothingSeconds))
    }

    private fun readPcm24(buffer: ByteArray, index: Int): Int =
        (buffer[index].toInt() and 0xFF) or
            ((buffer[index + 1].toInt() and 0xFF) shl 8) or
            (buffer[index + 2].toInt() shl 16)

    private fun writePcm24(buffer: ByteArray, index: Int, value: Int) {
        buffer[index] = (value and 0xFF).toByte()
        buffer[index + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[index + 2] = ((value shr 16) and 0xFF).toByte()
    }

    private fun readIntLe(buffer: ByteArray, index: Int): Int =
        (buffer[index].toInt() and 0xFF) or
            ((buffer[index + 1].toInt() and 0xFF) shl 8) or
            ((buffer[index + 2].toInt() and 0xFF) shl 16) or
            (buffer[index + 3].toInt() shl 24)

    private fun writeIntLe(buffer: ByteArray, index: Int, value: Int) {
        buffer[index] = (value and 0xFF).toByte()
        buffer[index + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[index + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[index + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readIntLe(buffer: ByteBuffer): Int =
        (buffer.get().toInt() and 0xFF) or
            ((buffer.get().toInt() and 0xFF) shl 8) or
            ((buffer.get().toInt() and 0xFF) shl 16) or
            (buffer.get().toInt() shl 24)

    private fun writeIntLe(buffer: ByteBuffer, value: Int) {
        buffer.put((value and 0xFF).toByte())
        buffer.put(((value shr 8) and 0xFF).toByte())
        buffer.put(((value shr 16) and 0xFF).toByte())
        buffer.put(((value shr 24) and 0xFF).toByte())
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
            val safeSampleRate = sampleRate.coerceAtLeast(1.0)
            val safeCenter = centerHz.coerceIn(1.0, safeSampleRate * 0.5 * 0.99)
            val safeQ = q.coerceIn(0.2, 20.0)
            val a = 10.0.pow(gainDb / 40.0)
            val omega = 2.0 * PI * safeCenter / safeSampleRate
            val sinW = sin(omega)
            val cosW = cos(omega)
            val alpha = sinW / (2.0 * safeQ)
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

    companion object {
        private const val GAIN_SMOOTHING_MS = 5.0
    }
}
