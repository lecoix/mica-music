package com.mica.music.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.sin

class SpectrumAnalysisEngineTest {
    private var now = 0L
    private var published = emptyList<Float>()
    private var envelope = 0f
    private fun engine(beforePublish: () -> Unit = {}) = SpectrumAnalysisEngine(
        nowNanos = { now },
        publish = { levels, energy -> published = levels; envelope = energy },
        beforePublish = beforePublish,
    ).apply { setEnabled(true); setAnalysisActive(true); setPlaybackAdvancing(true) }

    @Test fun oldFftCannotPublishAfterResetAndNewStreamAppend() {
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = engine { ready.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val old = engine.beginStream(Any())
        engine.append(old, tone(48_000), 48_000, 0)
        engine.updatePosition(old, 500_000)
        val worker = thread { engine.tick() }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            engine.reset()
            val fresh = engine.beginStream(Any())
            engine.append(fresh, FloatArray(4_800), 48_000, 2_000_000)
            // Both observable channels must stay reset when the old FFT completes.
            engine.append(old, tone(4_800), 48_000, 0)
            engine.updatePosition(old, 800_000)
            assertEquals(4_800, engine.bufferedSamples())
        } finally { release.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive)
        assertTrue(published.all { it == 0f })
        assertEquals(0f, envelope)
    }

    @Test fun bufferedAudioSurvivesLongProducerGapAndBackgroundRoundTrip() {
        val engine = engine()
        val token = engine.beginStream(Any())
        engine.append(token, tone(96_000), 48_000, 0)
        engine.setAnalysisActive(false)
        now = 50_000_000
        engine.setAnalysisActive(true)
        // No new PCM for 1.5 seconds: the sink still plays its already-buffered audio.
        repeat(90) {
            now += 16_666_667
            engine.updatePosition(token, (it + 1) * 16_667L)
            engine.tick()
            assertTrue("frame $it", envelope > 0f)
        }
        assertEquals(96_000, engine.bufferedSamples())
    }

    @Test fun mediaClockSelectsToneAfterSpeedChangeInsteadOfCountingTicks() {
        val engine = engine()
        val token = engine.beginStream(Any())
        engine.append(token, tone(48_000), 48_000, 0)
        engine.append(token, FloatArray(48_000), 48_000, 1_000_000)
        engine.updatePosition(token, 500_000)
        engine.tick()
        assertTrue(envelope > 0f)
        now += 20_000_000
        engine.updatePosition(token, 1_500_000)
        engine.tick()
        assertEquals(0f, envelope)
    }

    @Test fun transientFreshClockMissDoesNotPunchSpectrumDown() {
        val engine = engine()
        val token = engine.beginStream(Any())
        engine.append(token, tone(4_800), 48_000, 0)
        engine.updatePosition(token, 80_000)
        engine.tick()
        val initial = envelope

        now += 16_666_667L
        engine.updatePosition(token, 120_000)
        engine.tick()
        assertEquals(initial, envelope, 0f)

        now += 16_666_667L
        engine.updatePosition(token, 140_000)
        engine.tick()
        assertEquals(initial, envelope, 0f)
    }

    @Test fun sustainedMissingPcmStillDecaysAndNewPcmRecoversAtCurrentClock() {
        val engine = engine()
        val token = engine.beginStream(Any())
        engine.append(token, tone(4_800), 48_000, 0)
        engine.updatePosition(token, 80_000)
        engine.tick()
        val initial = envelope
        repeat(40) {
            now += 20_000_000
            engine.updatePosition(token, 200_000 + it * 20_000L)
            engine.tick()
        }
        assertTrue(envelope < initial * 0.01f)
        engine.append(token, tone(9_600), 48_000, 1_000_000)
        engine.updatePosition(token, 1_100_000)
        engine.tick()
        assertTrue(envelope > initial * 0.5f)
    }

    @Test fun staleClockDecaysEvenWithPcmAvailable() {
        val engine = engine()
        val token = engine.beginStream(Any())
        engine.append(token, tone(48_000), 48_000, 0)
        engine.updatePosition(token, 500_000)
        engine.tick()
        val initial = envelope
        now = 2_000_000_000L
        engine.tick()
        assertTrue(envelope < initial * 0.01f)
    }

    @Test fun retiredSinkCannotClearOrPublishOverItsSuccessor() {
        val engine = engine()
        val oldOwner = Any()
        val old = engine.beginStream(oldOwner)
        val fresh = engine.beginStream(Any())
        engine.append(fresh, tone(4_800), 48_000, 2_000_000)
        engine.updatePosition(fresh, 2_050_000)
        engine.resetStream(oldOwner)
        engine.updatePosition(old, 100_000)
        engine.append(old, FloatArray(4_800), 48_000, 0)
        engine.tick()
        assertEquals(4_800, engine.bufferedSamples())
        assertTrue(envelope > 0f)
    }

    @Test fun repeatedlyReportingFrozenClockDoesNotKeepOldBarsAlive() {
        val engine = engine()
        val token = engine.beginStream(Any())
        engine.append(token, tone(48_000), 48_000, 0)
        engine.updatePosition(token, 500_000)
        engine.tick()
        val initial = envelope
        repeat(100) {
            now += 20_000_000L
            engine.updatePosition(token, 500_000)
            engine.tick()
        }
        assertTrue(envelope < initial * 0.01f)
    }

    private fun tone(count: Int) = FloatArray(count) { sin(it * 0.13).toFloat() * 0.5f }
}
