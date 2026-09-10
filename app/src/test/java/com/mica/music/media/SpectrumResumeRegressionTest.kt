package com.mica.music.media

import android.media.AudioFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SpectrumResumeRegressionTest {
    @After fun cleanup() {
        MicaSpectrumAnalyzer.setPlaybackAdvancing(false)
        MicaSpectrumAnalyzer.setEnabled(false)
        MicaSpectrumAnalyzer.setAnalysisActive(false)
    }

    @Test fun briefBackgroundRoundTripWithoutNewPcmRetainsAlreadyBufferedAudio() {
        MicaSpectrumAnalyzer.setEnabled(true, notifyPipeline = false)
        MicaSpectrumAnalyzer.setAnalysisActive(true)
        MicaSpectrumAnalyzer.setPlaybackAdvancing(false)
        feed()
        val buffered = MicaSpectrumAnalyzer.queuedPcmSampleCount()
        MicaSpectrumAnalyzer.setAnalysisActive(false)
        // AudioTrack can backpressure the renderer throughout this entire round trip.
        MicaSpectrumAnalyzer.setAnalysisActive(true)
        assertEquals(buffered, MicaSpectrumAnalyzer.queuedPcmSampleCount())
    }

    @Test fun backgroundPlaybackCapturesWithoutRunningAnalysis() {
        MicaSpectrumAnalyzer.setEnabled(true, notifyPipeline = false)
        MicaSpectrumAnalyzer.setAnalysisActive(false)
        MicaSpectrumAnalyzer.setPlaybackAdvancing(true)
        feed()
        repeat(120) { MicaSpectrumAnalyzer.analyzeTickForTest() }
        assertEquals(48_000, MicaSpectrumAnalyzer.queuedPcmSampleCount())
    }

    private fun feed() = MicaSpectrumAnalyzer.processPcmBuffer(
        ByteArray(48_000 * 4), 0, 48_000 * 4,
        AudioFormat.ENCODING_PCM_16BIT, 48_000, 2,
    )
}
