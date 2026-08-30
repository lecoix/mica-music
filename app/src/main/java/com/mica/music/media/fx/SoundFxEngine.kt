package com.mica.music.media.fx

import com.mica.music.audio.fx.SoundFxSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Opt-in Shared-PCM effects: bass/treble shelves, stereo width, Freeverb-style reverb,
 * and a Woodworth spherical-head 360° surround. Neutral settings are a no-op.
 * Audio-thread only.
 */
internal class SoundFxEngine {
    private var sampleRateHz = 44_100
    private var channelCount = 2
    private var settings = SoundFxSettings()
    private var bassFilters = Array(2) { ShelfBiquad() }
    private var trebleFilters = Array(2) { ShelfBiquad() }
    private var reverb: Freeverb? = null
    private var surround: SphericalHeadSurround? = null

    fun configure(sampleRateHz: Int, channelCount: Int) {
        val rate = sampleRateHz.coerceAtLeast(8_000)
        val channels = channelCount.coerceAtLeast(1)
        if (this.sampleRateHz == rate && this.channelCount == channels) return
        this.sampleRateHz = rate
        this.channelCount = channels
        rebuildFilters()
        rebuildReverb()
        rebuildSurround()
        applyToneGains()
    }

    fun setSettings(next: SoundFxSettings) {
        val sanitized = next.sanitized()
        val reverbChanged = settings.reverbRoomPercent != sanitized.reverbRoomPercent ||
            settings.reverbDampingPercent != sanitized.reverbDampingPercent ||
            settings.reverbWetPercent != sanitized.reverbWetPercent
        val surroundChanged =
            settings.surroundIntensityPercent != sanitized.surroundIntensityPercent ||
                settings.surroundRotationDegPerSec != sanitized.surroundRotationDegPerSec
        val wasActive = settings.isDspActive()
        settings = sanitized
        applyToneGains()
        if (reverbChanged || (!wasActive && sanitized.isDspActive())) {
            rebuildReverb()
        }
        if (surroundChanged || (!wasActive && sanitized.isDspActive())) {
            rebuildSurround()
        }
        if (!sanitized.isDspActive()) {
            reset()
        }
    }

    fun isActive(): Boolean = settings.isDspActive()

    fun reset() {
        bassFilters.forEach { it.reset() }
        trebleFilters.forEach { it.reset() }
        reverb?.reset()
        surround?.reset()
    }

    fun processFrame(frame: DoubleArray, channelCount: Int) {
        if (!settings.isDspActive() || channelCount <= 0) return
        val count = channelCount.coerceAtMost(minOf(frame.size, bassFilters.size))
        val doBass = settings.bassDb != 0
        val doTreble = settings.trebleDb != 0
        val surroundActive = settings.surroundIntensityPercent != 0
        if (doBass || doTreble) {
            repeat(count) { channel ->
                var sample = frame[channel]
                if (doBass) sample = bassFilters[channel].process(sample)
                if (doTreble) sample = trebleFilters[channel].process(sample)
                frame[channel] = sample
            }
        }
        if (count >= 2 &&
            !surroundActive &&
            settings.stereoWidthPercent != SoundFxSettings.NEUTRAL_WIDTH_PERCENT
        ) {
            val width = settings.stereoWidthPercent / 100.0
            val left = frame[0]
            val right = frame[1]
            val mid = (left + right) * 0.5
            val side = (left - right) * 0.5
            frame[0] = mid + width * side
            frame[1] = mid - width * side
        }
        if (count >= 2 && settings.reverbWetPercent != 0) {
            reverb?.process(frame)
        }
        if (count >= 2 && surroundActive) {
            surround?.process(frame)
        }
    }

    private fun rebuildFilters() {
        bassFilters = Array(channelCount) { ShelfBiquad() }
        trebleFilters = Array(channelCount) { ShelfBiquad() }
    }

    private fun applyToneGains() {
        val rate = sampleRateHz.toDouble()
        bassFilters.forEach { it.setLowShelf(rate, BASS_SHELF_HZ, settings.bassDb.toDouble()) }
        trebleFilters.forEach { it.setHighShelf(rate, TREBLE_SHELF_HZ, settings.trebleDb.toDouble()) }
    }

    private fun rebuildReverb() {
        reverb = if (channelCount >= 2 && settings.reverbWetPercent != 0) {
            Freeverb(
                sampleRateHz = sampleRateHz,
                roomSize = settings.reverbRoomPercent / 100f,
                damping = settings.reverbDampingPercent / 100f,
                wet = settings.reverbWetPercent / 100f,
            )
        } else {
            null
        }
    }

    private fun rebuildSurround() {
        surround = if (channelCount >= 2 && settings.surroundIntensityPercent != 0) {
            val current = surround
            if (current != null && current.matches(sampleRateHz)) {
                current.setParams(
                    intensity = settings.surroundIntensityPercent / 100f,
                    rotationDegPerSec = settings.surroundRotationDegPerSec,
                )
                current
            } else {
                SphericalHeadSurround(
                    sampleRateHz = sampleRateHz,
                    intensity = settings.surroundIntensityPercent / 100f,
                    rotationDegPerSec = settings.surroundRotationDegPerSec,
                )
            }
        } else {
            surround?.reset()
            null
        }
    }

    private class ShelfBiquad {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun setLowShelf(sampleRate: Double, frequencyHz: Double, gainDb: Double) {
            setShelf(sampleRate, frequencyHz, gainDb, low = true)
        }

        fun setHighShelf(sampleRate: Double, frequencyHz: Double, gainDb: Double) {
            setShelf(sampleRate, frequencyHz, gainDb, low = false)
        }

        private fun setShelf(sampleRate: Double, frequencyHz: Double, gainDb: Double, low: Boolean) {
            if (gainDb == 0.0) {
                b0 = 1.0
                b1 = 0.0
                b2 = 0.0
                a1 = 0.0
                a2 = 0.0
                return
            }
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequencyHz.coerceIn(1.0, sampleRate * 0.49) / sampleRate.coerceAtLeast(1.0)
            val cosW = cos(w0)
            val sinW = sin(w0)
            val alpha = sinW / 2.0 * sqrt(2.0)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            if (low) {
                val b0n = a * ((a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha)
                val b1n = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW)
                val b2n = a * ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha)
                val a0n = (a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha
                val a1n = -2.0 * ((a - 1.0) + (a + 1.0) * cosW)
                val a2n = (a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha
                b0 = b0n / a0n
                b1 = b1n / a0n
                b2 = b2n / a0n
                a1 = a1n / a0n
                a2 = a2n / a0n
            } else {
                val b0n = a * ((a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha)
                val b1n = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW)
                val b2n = a * ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha)
                val a0n = (a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha
                val a1n = 2.0 * ((a - 1.0) - (a + 1.0) * cosW)
                val a2n = (a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha
                b0 = b0n / a0n
                b1 = b1n / a0n
                b2 = b2n / a0n
                a1 = a1n / a0n
                a2 = a2n / a0n
            }
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0.0
            x2 = 0.0
            y1 = 0.0
            y2 = 0.0
        }
    }

    /**
     * Compact stereo Freeverb. Delay tunings and mix mapping are Jezar's public-domain
     * constants (`fixedgain`, `scaleroom`, `offsetroom`, `scaledamp`), not a copy of
     * another player's source. Comb input is a mono send so eight parallel filters stay
     * at a usable level; dry is reduced as wet rises.
     */
    private class Freeverb(
        sampleRateHz: Int,
        roomSize: Float,
        damping: Float,
        wet: Float,
    ) {
        private val wet = wet.coerceIn(0f, 1f)
        private val dry = 1f - this.wet * 0.5f
        private val combL: List<CombFilter>
        private val combR: List<CombFilter>
        private val allpassL: List<AllpassFilter>
        private val allpassR: List<AllpassFilter>

        init {
            val feedback = roomSize.coerceIn(0f, 1f) * ROOM_SCALE + ROOM_OFFSET
            val damp = damping.coerceIn(0f, 1f) * DAMP_SCALE
            combL = COMB_TUNING.map { CombFilter(delayLength(it, sampleRateHz), feedback, damp) }
            combR = COMB_TUNING.map {
                CombFilter(delayLength(it + STEREO_SPREAD, sampleRateHz), feedback, damp)
            }
            allpassL = ALLPASS_TUNING.map { AllpassFilter(delayLength(it, sampleRateHz)) }
            allpassR = ALLPASS_TUNING.map {
                AllpassFilter(delayLength(it + STEREO_SPREAD, sampleRateHz))
            }
        }

        fun reset() {
            combL.forEach { it.reset() }
            combR.forEach { it.reset() }
            allpassL.forEach { it.reset() }
            allpassR.forEach { it.reset() }
        }

        fun process(frame: DoubleArray) {
            val inputL = frame[0].toFloat()
            val inputR = frame[1].toFloat()
            val send = (inputL + inputR) * FIXED_GAIN
            var accL = 0f
            var accR = 0f
            combL.forEach { accL += it.process(send) }
            combR.forEach { accR += it.process(send) }
            allpassL.forEach { accL = it.process(accL) }
            allpassR.forEach { accR = it.process(accR) }
            frame[0] = (accL * wet + inputL * dry).toDouble()
            frame[1] = (accR * wet + inputR * dry).toDouble()
        }

        private class CombFilter(length: Int, private val feedback: Float, private val damp: Float) {
            private val buffer = FloatArray(length.coerceAtLeast(1))
            private var index = 0
            private var filterStore = 0f

            fun process(input: Float): Float {
                val output = buffer[index]
                filterStore = output * (1f - damp) + filterStore * damp
                buffer[index] = input + filterStore * feedback
                index++
                if (index >= buffer.size) index = 0
                return output
            }

            fun reset() {
                buffer.fill(0f)
                index = 0
                filterStore = 0f
            }
        }

        private class AllpassFilter(length: Int) {
            private val buffer = FloatArray(length.coerceAtLeast(1))
            private var index = 0

            fun process(input: Float): Float {
                val buffered = buffer[index]
                val output = buffered - input
                buffer[index] = input + buffered * ALLPASS_FEEDBACK
                index++
                if (index >= buffer.size) index = 0
                return output
            }

            fun reset() {
                buffer.fill(0f)
                index = 0
            }
        }

        companion object {
            private const val BASE_SAMPLE_RATE = 44_100
            private const val STEREO_SPREAD = 23
            private const val ALLPASS_FEEDBACK = 0.5f
            private const val FIXED_GAIN = 0.015f
            private const val ROOM_SCALE = 0.28f
            private const val ROOM_OFFSET = 0.7f
            private const val DAMP_SCALE = 0.4f
            private val COMB_TUNING = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
            private val ALLPASS_TUNING = intArrayOf(556, 441, 341, 225)

            private fun delayLength(tuningAt44100: Int, sampleRateHz: Int): Int {
                val scaled = (tuningAt44100.toLong() * sampleRateHz.coerceAtLeast(8_000) / BASE_SAMPLE_RATE).toInt()
                return scaled.coerceAtLeast(1)
            }
        }
    }

    /**
     * Stereo-to-binaural wrap using Woodworth's spherical-head ITD plus ILD, contralateral
     * shadow, and a light rear allpass. L/R are two virtual sources at ±55°. Rotation orbits
     * that pair. Physical constants only; not a copy of another player's source.
     */
    private class SphericalHeadSurround(
        sampleRateHz: Int,
        intensity: Float,
        rotationDegPerSec: Int,
    ) {
        private val sampleRate = sampleRateHz.coerceAtLeast(8_000)
        private val delayMask: Int
        private val delayLeft: FloatArray
        private val delayRight: FloatArray
        private val shadowLeft = OnePoleLowPass(sampleRate)
        private val shadowRight = OnePoleLowPass(sampleRate)
        private val rearLeft = FirstOrderAllPass(sampleRate, 800f)
        private val rearRight = FirstOrderAllPass(sampleRate, 1_300f)
        private var wet = intensity.coerceIn(0f, 1f)
        private var rotationRadiansPerSample = 0f
        private var writeIndex = 0
        private var azimuth = 0f
        private var renderedLeft = 0f
        private var renderedRight = 0f

        init {
            val maxDelay = (
                (HEAD_RADIUS / SPEED_OF_SOUND) * (PI + 1.0) * sampleRate
                ).toInt() + 8
            var size = 64
            while (size < maxDelay) size = size shl 1
            delayMask = size - 1
            delayLeft = FloatArray(size)
            delayRight = FloatArray(size)
            setParams(intensity, rotationDegPerSec)
        }

        fun matches(sampleRateHz: Int): Boolean = sampleRate == sampleRateHz.coerceAtLeast(8_000)

        fun setParams(intensity: Float, rotationDegPerSec: Int) {
            wet = intensity.coerceIn(0f, 1f)
            rotationRadiansPerSample = Math.toRadians(
                rotationDegPerSec.coerceIn(
                    SoundFxSettings.MIN_SURROUND_ROTATION,
                    SoundFxSettings.MAX_SURROUND_ROTATION,
                ).toDouble(),
            ).toFloat() / sampleRate
        }

        fun reset() {
            delayLeft.fill(0f)
            delayRight.fill(0f)
            writeIndex = 0
            azimuth = 0f
            shadowLeft.reset()
            shadowRight.reset()
            rearLeft.reset()
            rearRight.reset()
        }

        fun process(frame: DoubleArray) {
            if (wet <= 0f) return
            val inputLeft = frame[0].toFloat()
            val inputRight = frame[1].toFloat()
            delayLeft[writeIndex] = inputLeft
            delayRight[writeIndex] = inputRight
            azimuth += rotationRadiansPerSample
            renderSource(inputLeft, delayLeft, writeIndex, azimuth - SPEAKER_SPREAD_RADIANS, shadowLeft, rearLeft)
            val fromLeftL = renderedLeft
            val fromLeftR = renderedRight
            renderSource(inputRight, delayRight, writeIndex, azimuth + SPEAKER_SPREAD_RADIANS, shadowRight, rearRight)
            val wetLeft = (fromLeftL + renderedLeft) * 0.5f
            val wetRight = (fromLeftR + renderedRight) * 0.5f
            val dry = 1f - wet
            frame[0] = (inputLeft * dry + wetLeft * wet).toDouble()
            frame[1] = (inputRight * dry + wetRight * wet).toDouble()
            writeIndex = (writeIndex + 1) and delayMask
        }

        private fun renderSource(
            current: Float,
            buffer: FloatArray,
            writeIndex: Int,
            azimuth: Float,
            shadow: OnePoleLowPass,
            rear: FirstOrderAllPass,
        ) {
            val theta = wrapPi(azimuth)
            val sine = sin(theta)
            val cosine = cos(theta)
            val gainLeft = 1f - 0.5f * sine
            val gainRight = 1f + 0.5f * sine
            val normalization = 1.41421356f / sqrt(gainLeft * gainLeft + gainRight * gainRight).coerceAtLeast(1e-6f)
            val thetaRadians = theta.toDouble()
            val sineValue = sine.toDouble()
            val delaySamples = (
                abs((HEAD_RADIUS / SPEED_OF_SOUND) * (thetaRadians + sineValue)) * sampleRate
                ).toFloat().coerceAtMost((delayMask - 1).toFloat())
            val delayed = readDelay(buffer, writeIndex, delaySamples)
            val shadowHz = (18_000.0 - 14_000.0 * abs(sineValue)).coerceAtLeast(1_200.0)
            var outLeft: Float
            var outRight: Float
            if (sine >= 0f) {
                shadow.setCutoff(shadowHz)
                outLeft = shadow.process(delayed * gainLeft * normalization)
                outRight = current * gainRight * normalization
            } else {
                shadow.setCutoff(shadowHz)
                outLeft = current * gainLeft * normalization
                outRight = shadow.process(delayed * gainRight * normalization)
            }
            val rearMix = max(0f, -cosine) * 0.35f
            if (rearMix > 0.001f) {
                val diffused = rear.process(current)
                val keep = 1f - rearMix
                outLeft = outLeft * keep + diffused * rearMix
                outRight = outRight * keep + diffused * rearMix
            }
            renderedLeft = outLeft
            renderedRight = outRight
        }

        private fun readDelay(buffer: FloatArray, writeIndex: Int, delaySamples: Float): Float {
            val readPosition = writeIndex - delaySamples
            val floorPosition = floor(readPosition.toDouble()).toInt()
            val fraction = (readPosition - floorPosition).toFloat()
            val read0 = floorPosition and delayMask
            val read1 = (read0 + 1) and delayMask
            return buffer[read0] * (1f - fraction) + buffer[read1] * fraction
        }

        private class OnePoleLowPass(private val sampleRate: Int) {
            private var coefficient = 1f
            private var state = 0f

            fun setCutoff(cutoffHz: Double) {
                val nyquist = sampleRate * 0.45
                val clamped = cutoffHz.coerceIn(20.0, nyquist)
                coefficient = (1.0 - exp(-2.0 * PI * clamped / sampleRate)).toFloat()
            }

            fun process(sample: Float): Float {
                state += coefficient * (sample - state)
                return state
            }

            fun reset() {
                state = 0f
            }
        }

        private class FirstOrderAllPass(sampleRate: Int, frequencyHz: Float) {
            private val coefficient: Float
            private var previousInput = 0f
            private var previousOutput = 0f

            init {
                val omega = (PI * frequencyHz / sampleRate.coerceAtLeast(8_000)).coerceIn(0.0, 1.4)
                val tangent = tan(omega)
                coefficient = ((1.0 - tangent) / (1.0 + tangent)).toFloat()
            }

            fun process(sample: Float): Float {
                val output = -coefficient * sample + previousInput + coefficient * previousOutput
                previousInput = sample
                previousOutput = output
                return output
            }

            fun reset() {
                previousInput = 0f
                previousOutput = 0f
            }
        }

        companion object {
            private const val HEAD_RADIUS = 0.0875
            private const val SPEED_OF_SOUND = 343.0
            private const val SPEAKER_SPREAD_RADIANS = 0.96f

            private fun wrapPi(value: Float): Float {
                val pi = PI.toFloat()
                val twoPi = (PI * 2.0).toFloat()
                return ((value + pi) % twoPi + twoPi) % twoPi - pi
            }
        }
    }

    companion object {
        private const val BASS_SHELF_HZ = 100.0
        private const val TREBLE_SHELF_HZ = 10_000.0
    }
}
