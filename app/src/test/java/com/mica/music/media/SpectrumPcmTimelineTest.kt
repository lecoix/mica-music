package com.mica.music.media

import org.junit.Assert.*
import org.junit.Test

class SpectrumPcmTimelineTest {
    @Test fun boundedHistoryKeepsRecentAudioAcrossLongBackgroundPlayback() {
        val history = SpectrumPcmTimeline()
        repeat(1_000) { block ->
            history.append(FloatArray(4_800) { block.toFloat() }, 48_000, block * 100_000L)
            assertTrue(history.size <= 96_000)
        }
        assertNull(history.windowAt(1_000_000))
        assertEquals(999f, history.windowAt(99_950_000)!!.last())
    }

    @Test fun mediaClockHistorySurvivesCoarseBlocksNearSoftRetentionBoundary() {
        val history = SpectrumPcmTimeline()
        val rate = 48_000
        val blockSamples = 4_096
        val blockUs = blockSamples * 1_000_000L / rate
        val positionUs = 1_000_000L
        history.protect(positionUs)

        // Model the affected ALAC shape: 85.3 ms blocks while capture runs ~1.9 s ahead.
        repeat(36) { block ->
            val startUs = block * blockUs
            history.append(FloatArray(blockSamples) { block.toFloat() }, rate, startUs)
        }

        assertNotNull(history.windowAt(positionUs))
        assertTrue(history.size > 96_000)
        assertTrue(history.size <= 192_000)
    }

    @Test fun clockProtectionRemainsHardBoundedWhenProducerRunsFarAhead() {
        val history = SpectrumPcmTimeline()
        history.protect(1_000_000L)
        repeat(100) { block ->
            history.append(FloatArray(4_096) { block.toFloat() }, 48_000, block * 4_096L * 1_000_000L / 48_000)
            assertTrue(history.size <= 192_000)
        }
        assertNotNull(history.windowAt(1_000_000L))
    }

    @Test fun windowCrossesChunkBoundaryWithoutGapOrDuplicate() {
        val history = SpectrumPcmTimeline()
        history.append(FloatArray(4_096) { it.toFloat() }, 44_100, 0)
        history.append(FloatArray(4_096) { (it + 4_096).toFloat() }, 44_100, 4_096 * 1_000_000L / 44_100)
        val frame = 4_500
        val window = history.windowAt(frame * 1_000_000L / 44_100)!!
        assertArrayEquals(FloatArray(2_048) { (frame - 2_047 + it).toFloat() }, window, 0f)
    }

    @Test fun largeBurstAndFormatChangeAreBounded() {
        val history = SpectrumPcmTimeline()
        history.append(FloatArray(500_000) { 1f }, 48_000, 0)
        assertEquals(192_000, history.size)
        assertNull(history.windowAt(0))
        history.append(FloatArray(4_096) { 2f }, 96_000, 0)
        assertEquals(4_096, history.size)
        assertEquals(2f, history.windowAt(30_000)!!.last())
    }

    @Test fun gapIsNotFilledByUnrelatedFutureOrOldAudio() {
        val history = SpectrumPcmTimeline()
        history.append(FloatArray(4_800) { 1f }, 48_000, 0)
        history.append(FloatArray(4_800) { 2f }, 48_000, 1_000_000)
        assertNull(history.windowAt(500_000))
        assertEquals(2f, history.windowAt(1_050_000)!!.last())
    }
}
