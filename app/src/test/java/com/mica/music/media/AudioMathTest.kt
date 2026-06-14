package com.mica.music.media

import com.mica.music.media.eq.EqBandConstants
import com.mica.music.media.eq.EqBandMapper
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMathTest {

    @Test
    fun pcmFormatUsesSafeDefaultsAndFrameAlignedOffsets() {
        val song = SongFixtures.song().copy(
            metadata = SongFixtures.song().metadata.copy(
                sampleRateHz = 0,
                bitsPerSample = null,
                channelCount = 8,
            ),
        )
        val format = AlacPcmFormat.fromSong(song)
        assertEquals(44_100, format.sampleRateHz)
        assertEquals(2, format.channelCount)
        assertEquals(16, format.bitsPerSample)
        assertEquals(176_400, format.byteOffsetForMs(1_000))
    }

    @Test
    fun equalizerEmptyAndExactInputsAreDeterministic() {
        assertArrayEquals(
            EqBandConstants.defaultLevels(),
            EqBandMapper.mapToSoftwareBands(emptyList()),
        )
        val exact = EqBandConstants.CENTER_HZ.mapIndexed { index, hz ->
            hz to (index * 100).toShort()
        }
        assertArrayEquals(
            exact.map { it.second }.toShortArray(),
            EqBandMapper.mapToSoftwareBands(exact),
        )
    }

    @Test
    fun fiveBandInputMapsToTenFiniteBands() {
        val mapped = EqBandMapper.normalizeLevels(listOf(-1_000, -500, 0, 500, 1_000))
        assertEquals(EqBandConstants.BAND_COUNT, mapped.size)
        assertTrue(mapped.all { it in -2_000..2_000 })
    }
}
