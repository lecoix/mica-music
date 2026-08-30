package com.mica.music.media.eq

import android.media.AudioFormat
import com.mica.music.audio.fx.SoundFxSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EqDspTest {

    @Test
    fun adjacentUiBandsCollapseIntoOneAndroidBand() {
        val levels = ShortArray(10)
        levels[0] = 1_200
        levels[1] = 1_200

        val deviceLevels = AndroidFiveBandEqModel.collapseUiLevels(levels)

        assertEquals(1_200, deviceLevels[0].toInt())
        assertTrue(deviceLevels.drop(1).all { it == 0.toShort() })
    }

    @Test
    fun singleUiBandContributesHalfOfItsPairLevel() {
        val levels = ShortArray(10)
        levels[0] = 1_200

        val deviceLevels = AndroidFiveBandEqModel.collapseUiLevels(levels)

        assertEquals(600, deviceLevels[0].toInt())
    }

    @Test
    fun pairedLowBoostHeadroomDoesNotDoubleStack() {
        val levels = ShortArray(10)
        levels[0] = 1_200
        levels[1] = 1_200

        val plan = EqHeadroomPlanner.plan(levels, 48_000)

        assertEquals(12.0, plan.responsePeakDb, 0.05)
        assertEquals(-12.25, plan.preampDb, 0.05)
    }

    @Test
    fun flatHeadroomPlanIsUnity() {
        val plan = EqHeadroomPlanner.plan(ShortArray(10), 44_100)

        assertEquals(0.0, plan.responsePeakDb, 0.0)
        assertEquals(0.0, plan.preampDb, 0.0)
    }

    @Test
    fun cutOnlyCurveDoesNotApplyUnnecessaryPreamp() {
        val levels = ShortArray(10)
        levels[4] = -600
        levels[5] = -900
        levels[6] = -300

        val plan = EqHeadroomPlanner.plan(levels, 44_100)

        assertEquals(0.0, plan.preampDb, 0.0)
    }

    @Test
    fun plannedEqPeakStaysBelowLimiterCeiling() {
        val levels = ShortArray(10)
        levels[0] = 1_200
        levels[1] = 1_200

        val plan = EqHeadroomPlanner.plan(levels, 48_000)
        val plannedOutputPeakDb = plan.responsePeakDb + plan.preampDb

        assertTrue(plannedOutputPeakDb < LinkedPeakLimiter.DEFAULT_CEILING_DB)
    }
    @Test
    fun linkedLimiterLeavesSubCeilingFrameUntouched() {
        val limiter = LinkedPeakLimiter()
        limiter.configure(48_000)
        val frame = doubleArrayOf(0.80, -0.50)
        val original = frame.copyOf()

        limiter.processFrame(frame, 2)

        assertArrayEquals(original, frame, 0.0)
        assertEquals(1.0, limiter.gainForTest(), 0.0)
    }

    @Test
    fun linkedLimiterPreservesStereoRatioWhenProtectingPeak() {
        val limiter = LinkedPeakLimiter()
        limiter.configure(48_000)
        val frame = doubleArrayOf(1.20, 0.60)

        limiter.processFrame(frame, 2)

        val ceiling = 10.0.pow(LinkedPeakLimiter.DEFAULT_CEILING_DB / 20.0)
        assertTrue(abs(frame[0]) <= ceiling + 1e-12)
        assertTrue(abs(frame[1]) <= ceiling + 1e-12)
        assertEquals(2.0, frame[0] / frame[1], 1e-9)
        assertTrue(limiter.gainForTest() < 1.0)
    }

    @Test
    fun flatEnabledEqPreservesPcm16BytesExactly() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 2)
        equalizer.setEnabled(true)
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(Short.MIN_VALUE)
            putShort((-20_000).toShort())
            putShort((-1).toShort())
            putShort(0)
            putShort(1)
            putShort(20_000)
            putShort(Short.MAX_VALUE)
            putShort(1234)
        }.array()
        val original = bytes.copyOf()

        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_16BIT)

        assertArrayEquals(original, bytes)
    }

    @Test
    fun flatEnabledEqPreservesMedia3FloatBitsExactly() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 2)
        equalizer.setEnabled(true)
        val inputBytes = ByteBuffer.allocate(8 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            floatArrayOf(-1.0f, -0.99f, -0.5f, -0.0f, 0.0f, 0.5f, 0.99f, 1.0f)
                .forEach(::putFloat)
        }.array()
        val input = ByteBuffer.wrap(inputBytes.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)

        equalizer.processMedia3Buffer(input, AudioFormat.ENCODING_PCM_FLOAT, output)

        assertArrayEquals(inputBytes, output.array())
    }

    @Test
    fun disabledSoundFxDoesNotBreakFlatEqPassthrough() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 2)
        equalizer.setEnabled(true)
        equalizer.setSoundFx(SoundFxSettings(enabled = true))
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(Short.MIN_VALUE)
            putShort((-20_000).toShort())
            putShort((-1).toShort())
            putShort(0)
            putShort(1)
            putShort(20_000)
            putShort(Short.MAX_VALUE)
            putShort(1234)
        }.array()
        val original = bytes.copyOf()

        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_16BIT)

        assertArrayEquals(original, bytes)
        assertFalse(equalizer.isSoundFxDspActive())
    }

    @Test
    fun stereoWidthCollapseIsAppliedWhenSoundFxIsActive() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(44_100, 2)
        equalizer.setSoundFx(
            SoundFxSettings(
                enabled = true,
                stereoWidthPercent = 0,
            ),
        )
        val inputBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(0.50f)
            putFloat(-0.25f)
        }.array()
        val bytes = inputBytes.copyOf()

        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_FLOAT)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val left = buffer.float
        val right = buffer.float
        assertEquals(0.125f, left, 1e-6f)
        assertEquals(right, left, 0.0f)
        assertTrue(equalizer.isProcessingRequired())
    }

    @Test
    fun liveBandChangeSettlesToSameResultAsReloadedCurve() {
        val live = SoftwareEqualizer()
        live.configure(44_100, 1)
        live.setEnabled(true)
        val warm = floatBytes(FloatArray(32))
        live.processInterleaved(warm, 0, warm.size, AudioFormat.ENCODING_PCM_FLOAT)
        live.setBandLevel(5, 1_200)
        val liveSettle = floatBytes(FloatArray(8_192))
        live.processInterleaved(liveSettle, 0, liveSettle.size, AudioFormat.ENCODING_PCM_FLOAT)

        val restored = SoftwareEqualizer()
        restored.configure(44_100, 1)
        val levels = ShortArray(10)
        levels[5] = 1_200
        restored.setLevels(levels)
        restored.setEnabled(true)
        val restoredSettle = floatBytes(FloatArray(8_192))
        restored.processInterleaved(restoredSettle, 0, restoredSettle.size, AudioFormat.ENCODING_PCM_FLOAT)

        val source = FloatArray(2_048) { index ->
            (0.20 * sin(2.0 * Math.PI * 1_000.0 * index / 44_100.0)).toFloat()
        }
        val liveBytes = floatBytes(source)
        val restoredBytes = floatBytes(source)
        live.processInterleaved(liveBytes, 0, liveBytes.size, AudioFormat.ENCODING_PCM_FLOAT)
        restored.processInterleaved(restoredBytes, 0, restoredBytes.size, AudioFormat.ENCODING_PCM_FLOAT)
        val liveOutput = floats(liveBytes)
        val restoredOutput = floats(restoredBytes)

        liveOutput.indices.forEach { index ->
            assertEquals(restoredOutput[index], liveOutput[index], 1e-4f)
        }
    }

    @Test
    fun linearEqDoesNotCreateWaveshaperHarmonicsBelowLimiter() {
        val sampleRate = 48_000
        val frequency = 1_000.0
        val equalizer = SoftwareEqualizer()
        equalizer.configure(sampleRate, 1)
        equalizer.setBandLevel(5, 600)
        equalizer.setEnabled(true)

        val warm = FloatArray(4_800) { index ->
            (0.20 * sin(2.0 * Math.PI * frequency * index / sampleRate)).toFloat()
        }
        val warmBytes = floatBytes(warm)
        equalizer.processInterleaved(warmBytes, 0, warmBytes.size, AudioFormat.ENCODING_PCM_FLOAT)

        val source = FloatArray(4_800) { index ->
            (0.20 * sin(2.0 * Math.PI * frequency * index / sampleRate)).toFloat()
        }
        val bytes = floatBytes(source)
        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_FLOAT)
        val output = floats(bytes)

        val fundamental = magnitudeAt(output, frequency, sampleRate)
        val third = magnitudeAt(output, frequency * 3.0, sampleRate)
        val fifth = magnitudeAt(output, frequency * 5.0, sampleRate)
        assertTrue(fundamental > 0.05)
        assertTrue(third / fundamental < 1e-5)
        assertTrue(fifth / fundamental < 1e-5)
    }

    @Test
    fun highBandAtLowSampleRateStaysFiniteAndBounded() {
        val equalizer = SoftwareEqualizer()
        equalizer.configure(22_050, 1)
        equalizer.setBandLevel(9, 1_200)
        equalizer.setEnabled(true)
        val source = FloatArray(4_096) { index ->
            (0.50 * sin(2.0 * Math.PI * 9_000.0 * index / 22_050.0)).toFloat()
        }
        val bytes = floatBytes(source)

        equalizer.processInterleaved(bytes, 0, bytes.size, AudioFormat.ENCODING_PCM_FLOAT)

        val ceiling = 10.0.pow(LinkedPeakLimiter.DEFAULT_CEILING_DB / 20.0).toFloat()
        floats(bytes).forEach { sample ->
            assertTrue(sample.isFinite())
            assertTrue(abs(sample) <= ceiling + 1e-5f)
        }
    }

    private fun magnitudeAt(samples: FloatArray, frequency: Double, sampleRate: Int): Double {
        var real = 0.0
        var imaginary = 0.0
        samples.indices.forEach { index ->
            val phase = 2.0 * Math.PI * frequency * index / sampleRate
            real += samples[index] * cos(phase)
            imaginary -= samples[index] * sin(phase)
        }
        return 2.0 * kotlin.math.sqrt(real * real + imaginary * imaginary) / samples.size
    }

    private fun floatBytes(samples: FloatArray): ByteArray =
        ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach(::putFloat)
        }.array()

    private fun floats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }
}

