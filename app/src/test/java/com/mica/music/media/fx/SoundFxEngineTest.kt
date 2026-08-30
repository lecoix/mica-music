package com.mica.music.media.fx

import com.mica.music.audio.fx.SoundFxSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SoundFxEngineTest {
    @Test
    fun inactiveEngineLeavesFrameUntouched() {
        val engine = SoundFxEngine()
        engine.configure(44_100, 2)
        val frame = doubleArrayOf(0.25, -0.40)
        val original = frame.copyOf()

        engine.processFrame(frame, 2)

        assertArrayEquals(original, frame, 0.0)
        assertFalse(engine.isActive())
    }

    @Test
    fun enabledNeutralSettingsLeaveFrameUntouched() {
        val engine = SoundFxEngine()
        engine.configure(44_100, 2)
        engine.setSettings(SoundFxSettings(enabled = true))
        val frame = doubleArrayOf(0.25, -0.40)
        val original = frame.copyOf()

        engine.processFrame(frame, 2)

        assertArrayEquals(original, frame, 0.0)
        assertFalse(engine.isActive())
    }

    @Test
    fun zeroWidthCollapsesStereoToMono() {
        val engine = SoundFxEngine()
        engine.configure(44_100, 2)
        engine.setSettings(
            SoundFxSettings(enabled = true, stereoWidthPercent = 0),
        )
        val frame = doubleArrayOf(0.80, -0.20)

        engine.processFrame(frame, 2)

        assertEquals(0.30, frame[0], 1e-12)
        assertEquals(0.30, frame[1], 1e-12)
        assertTrue(engine.isActive())
    }

    @Test
    fun bassBoostChangesALowToneMoreThanAHighTone() {
        val lowDelta = toneEnergyDelta(frequencyHz = 100.0, bassDb = 6, trebleDb = 0)
        val highDelta = toneEnergyDelta(frequencyHz = 8_000.0, bassDb = 6, trebleDb = 0)

        assertTrue(lowDelta > highDelta)
        assertTrue(lowDelta > 0.0)
    }

    @Test
    fun reverbLeavesAQuietTailAfterAnImpulse() {
        val engine = SoundFxEngine()
        engine.configure(44_100, 2)
        engine.setSettings(
            SoundFxSettings(enabled = true, reverbWetPercent = 20),
        )
        val impulse = doubleArrayOf(1.0, 1.0)
        engine.processFrame(impulse, 2)

        var tailEnergy = 0.0
        var peak = 0.0
        repeat(2_000) {
            val frame = doubleArrayOf(0.0, 0.0)
            engine.processFrame(frame, 2)
            tailEnergy += abs(frame[0]) + abs(frame[1])
            peak = maxOf(peak, abs(frame[0]), abs(frame[1]))
        }

        assertTrue(tailEnergy > 0.01)
        assertTrue(peak < 0.2)
    }

    @Test
    fun rotationAloneDoesNotChangeTheFrame() {
        val engine = SoundFxEngine()
        engine.configure(44_100, 2)
        engine.setSettings(
            SoundFxSettings(enabled = true, surroundRotationDegPerSec = 90),
        )
        val frame = doubleArrayOf(0.25, -0.40)
        val original = frame.copyOf()

        engine.processFrame(frame, 2)

        assertArrayEquals(original, frame, 0.0)
        assertFalse(engine.isActive())
    }

    @Test
    fun surroundPutsALeftImpulseIntoTheRightEarLater() {
        val engine = SoundFxEngine()
        engine.configure(44_100, 2)
        engine.setSettings(
            SoundFxSettings(
                enabled = true,
                surroundIntensityPercent = 100,
                surroundRotationDegPerSec = 0,
            ),
        )
        val impulse = doubleArrayOf(1.0, 0.0)
        engine.processFrame(impulse, 2)

        assertTrue(abs(impulse[0]) > abs(impulse[1]) * 1.2)

        var delayFrames = 0
        var sawRight = false
        for (index in 1..80) {
            val frame = doubleArrayOf(0.0, 0.0)
            engine.processFrame(frame, 2)
            if (abs(frame[1]) > 0.02) {
                delayFrames = index
                sawRight = true
                break
            }
        }

        assertTrue(sawRight)
        assertTrue(delayFrames in 4..60)
    }

    @Test
    fun surroundDoesNotCollapseToMonoWhenWidthIsZero() {
        val engine = SoundFxEngine()
        engine.configure(44_100, 2)
        engine.setSettings(
            SoundFxSettings(
                enabled = true,
                stereoWidthPercent = 0,
                surroundIntensityPercent = 100,
                surroundRotationDegPerSec = 0,
            ),
        )
        val frame = doubleArrayOf(0.80, -0.20)
        engine.processFrame(frame, 2)

        assertTrue(abs(frame[0] - frame[1]) > 0.05)
    }

    private fun toneEnergyDelta(frequencyHz: Double, bassDb: Int, trebleDb: Int): Double {
        val sampleRate = 44_100
        val frames = 4_096
        val dry = DoubleArray(frames)
        val wet = DoubleArray(frames)
        val omega = 2.0 * Math.PI * frequencyHz / sampleRate
        val engine = SoundFxEngine()
        engine.configure(sampleRate, 1)
        engine.setSettings(SoundFxSettings(enabled = true, bassDb = bassDb, trebleDb = trebleDb))
        repeat(frames) { index ->
            val sample = kotlin.math.sin(omega * index)
            dry[index] = sample
            val frame = doubleArrayOf(sample)
            engine.processFrame(frame, 1)
            wet[index] = frame[0]
        }
        return energy(wet) - energy(dry)
    }

    private fun energy(samples: DoubleArray): Double =
        samples.fold(0.0) { acc, sample -> acc + sample * sample }
}
