package com.mica.music.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSinkDeliveryDeciderTest {

    @Test
    fun enableFloatOutput_dsdSong_keepsIntSink() {
        val probe = PcmDeliveryProbeResult(
            route = AudioRouteSnapshot(null, "test", false, false),
            songId = "dsf-1",
            sourceFormat = AlacPcmFormat(11_289_600, 2, 1),
            isDsd = true,
            dspPathActive = false,
            noDspLadder = emptyList(),
            dspLadder = emptyList(),
            selectedNoDsp = null,
            selectedDsp = null,
            dsdIntCandidates = listOf(AlacPcmFormat(176_400, 2, 24)),
        )

        assertFalse(PcmSinkDeliveryDecider.enableFloatOutput(probe))
    }

    @Test
    fun enableFloatOutput_pcmHiResNoDsp_requestsFloatSinkWhenFloatRouteIsSupported() {
        val probe = pcmProbe(
            selectedNoDsp = PcmDeliveryFormat.IntPcm(96_000, 2, 24),
            selectedDsp = PcmDeliveryFormat.FloatPcm(96_000, 2),
            dspPathActive = false,
        )

        assertTrue(PcmSinkDeliveryDecider.enableFloatOutput(probe))
    }

    @Test
    fun enableFloatOutput_pcmHiResNoDsp_keepsIntSinkWhenOnlyInt24RouteIsSupported() {
        val probe = pcmProbe(
            selectedNoDsp = PcmDeliveryFormat.IntPcm(96_000, 2, 24),
            selectedDsp = PcmDeliveryFormat.IntPcm(96_000, 2, 24),
            dspPathActive = false,
        )

        assertFalse(PcmSinkDeliveryDecider.enableFloatOutput(probe))
    }

    @Test
    fun enableFloatOutput_pcm16NoDsp_keepsInt16Sink() {
        val probe = pcmProbe(
            selectedNoDsp = PcmDeliveryFormat.IntPcm(44_100, 2, 16),
            dspPathActive = false,
        )

        assertFalse(PcmSinkDeliveryDecider.enableFloatOutput(probe))
    }

    @Test
    fun enableFloatOutput_dspFloatSelected_requestsFloatSink() {
        val probe = pcmProbe(
            selectedNoDsp = PcmDeliveryFormat.IntPcm(96_000, 2, 24),
            selectedDsp = PcmDeliveryFormat.FloatPcm(96_000, 2),
            dspPathActive = true,
        )

        assertTrue(PcmSinkDeliveryDecider.enableFloatOutput(probe))
    }

    @Test
    fun enableFloatOutput_dspOnlyInt24_keepsInt16Sink() {
        val probe = pcmProbe(
            selectedNoDsp = PcmDeliveryFormat.IntPcm(96_000, 2, 24),
            selectedDsp = PcmDeliveryFormat.IntPcm(96_000, 2, 24),
            dspPathActive = true,
        )

        assertFalse(PcmSinkDeliveryDecider.enableFloatOutput(probe))
    }

    private fun pcmProbe(
        selectedNoDsp: PcmDeliveryFormat?,
        selectedDsp: PcmDeliveryFormat? = null,
        dspPathActive: Boolean,
    ): PcmDeliveryProbeResult =
        PcmDeliveryProbeResult(
            route = AudioRouteSnapshot(null, "test", false, false),
            songId = "flac-1",
            sourceFormat = AlacPcmFormat(96_000, 2, 24),
            isDsd = false,
            dspPathActive = dspPathActive,
            noDspLadder = emptyList(),
            dspLadder = emptyList(),
            selectedNoDsp = selectedNoDsp,
            selectedDsp = selectedDsp,
            dsdIntCandidates = emptyList(),
        )
}
