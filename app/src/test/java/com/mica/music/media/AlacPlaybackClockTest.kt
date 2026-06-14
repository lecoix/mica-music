package com.mica.music.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlacPlaybackClockTest {

    @Test
    fun staleGenerationCannotMutateCurrentTrack() {
        val clock = AlacPlaybackClock()
        clock.resetForNewTrack(10_000)
        val oldGeneration = clock.generation
        clock.resetForNewTrack(20_000)

        assertNull(clock.applyPosition(oldGeneration, 8_000, 20_000))
        clock.applyPlaying(oldGeneration, true)
        assertEquals(0, clock.positionMs)
        assertFalse(clock.isPlaying)
    }

    @Test
    fun seekAnchorRejectsOldProgressAndReleasesNearTarget() {
        val clock = AlacPlaybackClock()
        clock.resetForNewTrack(30_000)
        val generation = clock.beginSeek(12_000, playWhenReady = true)

        assertNull(clock.applyPosition(generation, 2_000, 30_000))
        assertEquals(12_000, clock.positionMs)
        assertEquals(12_200L, clock.applyPosition(generation, 12_200, 30_000))
        assertEquals(13_000L, clock.applyPosition(generation, 13_000, 30_000))
    }

    @Test
    fun preparedAndBufferingStateProduceSessionSnapshot() {
        val clock = AlacPlaybackClock()
        clock.resetForNewTrack(5_000)
        val generation = clock.generation
        clock.applyPrepared(generation, 10)
        clock.applyPlaying(generation, true)

        val state = clock.toSessionState()
        assertEquals(10_000, state.durationMs)
        assertTrue(state.playWhenReady)
        assertTrue(state.isPlaying)
        assertFalse(state.buffering)
    }
}
