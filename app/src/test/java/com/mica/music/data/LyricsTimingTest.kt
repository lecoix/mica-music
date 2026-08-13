package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTimingTest {
    @Test
    fun globalAndSongOffsetsAddAndPositiveValuesAdvanceLyrics() {
        val offset = LyricsTiming.effectiveOffsetMs(globalOffsetMs = 200, songOffsetMs = 300)

        assertEquals(500, offset)
        assertEquals(10_000, LyricsTiming.effectivePositionMs(9_500, offset))
        assertEquals(9_500, LyricsTiming.seekPositionMs(10_000, offset))
    }

    @Test
    fun negativeOffsetDelaysLyricsAndSeekLandsAtAdjustedDisplayStart() {
        assertEquals(9_500, LyricsTiming.effectivePositionMs(10_000, -500))
        assertEquals(10_500, LyricsTiming.seekPositionMs(10_000, -500))
    }

    @Test
    fun eachStoredLayerIsClampedButCombinedOffsetMayReachTenSeconds() {
        assertEquals(10_000, LyricsTiming.effectiveOffsetMs(9_000, 8_000))
        assertEquals(-10_000, LyricsTiming.effectiveOffsetMs(-9_000, -8_000))
    }
}
