package com.mica.music.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumPcmPipelineDiagnosticsTest {

    @Test
    fun upstreamGapThreshold_is200ms() {
        assertTrue(shouldLogUpstreamGap(200L))
        assertFalse(shouldLogUpstreamGap(199L))
    }

    @Test
    fun analyzerStarvationThreshold_is100ms() {
        assertTrue(shouldLogAnalyzerStarvation(100L))
        assertFalse(shouldLogAnalyzerStarvation(99L))
    }

    @Test
    fun queueBurstThreshold_requiresAbsoluteOrDelta() {
        assertTrue(shouldLogQueueBurst(previousQueued = 1_000, queued = 31_000, offered = 30_000))
        assertTrue(shouldLogQueueBurst(previousQueued = 5_000, queued = 26_000, offered = 21_000))
        assertFalse(shouldLogQueueBurst(previousQueued = 2_000, queued = 8_000, offered = 6_000))
    }

    private fun shouldLogUpstreamGap(gapMs: Long): Boolean = gapMs >= 200L

    private fun shouldLogAnalyzerStarvation(durationMs: Long): Boolean = durationMs >= 100L

    private fun shouldLogQueueBurst(previousQueued: Int, queued: Int, offered: Int): Boolean {
        val delta = queued - previousQueued
        return queued >= 30_000 || delta >= 20_000
    }
}
