package com.mica.music.media

import com.mica.music.data.TrackMetadata
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PcmDeliveryProbeTest {

    @Test
    fun buildNoDspLadder_keepsSourceThenFallsBackTo16Bit() {
        val source = AlacPcmFormat(96_000, 2, 24)

        val ladder = PcmDeliveryProbe.buildNoDspLadder(source)

        assertEquals(2, ladder.size)
        assertEquals(
            PcmDeliveryFormat.IntPcm(96_000, 2, 24),
            ladder[0],
        )
        assertEquals(
            PcmDeliveryFormat.IntPcm(96_000, 2, 16),
            ladder[1],
        )
    }

    @Test
    fun buildNoDspLadder_for16BitSource_hasSingleStep() {
        val ladder = PcmDeliveryProbe.buildNoDspLadder(AlacPcmFormat(44_100, 2, 16))

        assertEquals(1, ladder.size)
        assertEquals(PcmDeliveryFormat.IntPcm(44_100, 2, 16), ladder[0])
    }

    @Test
    fun buildDspLadder_prefersFloatThenInt24ThenInt16() {
        val ladder = PcmDeliveryProbe.buildDspLadder(AlacPcmFormat(96_000, 2, 24))

        assertTrue(ladder[0] is PcmDeliveryFormat.FloatPcm)
        assertEquals(PcmDeliveryFormat.FloatPcm(96_000, 2), ladder[0])
        assertEquals(PcmDeliveryFormat.IntPcm(96_000, 2, 24), ladder[1])
        assertEquals(PcmDeliveryFormat.IntPcm(96_000, 2, 16), ladder[2])
    }

    @Test
    fun selectedNoDsp_picksFirstSupportedCandidate() {
        val steps = listOf(
            PcmDeliveryLadderStep(PcmDeliveryFormat.IntPcm(96_000, 2, 24), supported = false),
            PcmDeliveryLadderStep(PcmDeliveryFormat.IntPcm(96_000, 2, 16), supported = true),
        )

        val selected = steps.firstOrNull { it.supported }?.format

        assertEquals(PcmDeliveryFormat.IntPcm(96_000, 2, 16), selected)
    }

    @Test
    fun selectedDsp_canPreferFloatOverInt24() {
        val steps = listOf(
            PcmDeliveryLadderStep(PcmDeliveryFormat.FloatPcm(96_000, 2), supported = true),
            PcmDeliveryLadderStep(PcmDeliveryFormat.IntPcm(96_000, 2, 24), supported = true),
        )

        val selected = steps.firstOrNull { it.supported }?.format

        assertEquals(PcmDeliveryFormat.FloatPcm(96_000, 2), selected)
    }

    @Test
    fun selectedNoDsp_returnsNullWhenNothingSupported() {
        val steps = listOf(
            PcmDeliveryLadderStep(PcmDeliveryFormat.IntPcm(96_000, 2, 24), supported = false),
            PcmDeliveryLadderStep(PcmDeliveryFormat.IntPcm(96_000, 2, 16), supported = false),
        )

        assertNull(steps.firstOrNull { it.supported }?.format)
    }

    @Test
    fun probe_dsdSong_skipsNativeRatePcmLadders() {
        val song = SongFixtures.song("dsd256", container = "DSD", mime = "audio/x-dsf").copy(
            metadata = TrackMetadata(
                containerName = "DSD",
                sampleRateHz = 11_289_600,
                bitsPerSample = 1,
                bitrateKbps = 1_411,
                channelCount = 2,
                playbackMimeType = "audio/x-dsf",
            ),
        )
        val context = RuntimeEnvironment.getApplication()

        val result = PcmDeliveryProbe.probe(context, song, dspPathActive = false)

        assertTrue(result.isDsd)
        assertTrue(result.noDspLadder.isEmpty())
        assertTrue(result.dspLadder.isEmpty())
        assertNull(result.selectedNoDsp)
        assertNull(result.selectedDsp)
    }

    @Test
    fun queryFloatSupport_rejectsDsdNativeSampleRateWithoutThrowing() {
        val context = RuntimeEnvironment.getApplication()

        assertFalse(
            AudioOutputCapabilities.queryFloatSupport(context, 11_289_600, 2),
        )
    }
}
