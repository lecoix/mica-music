package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumAudioProcessorTest {

    @After
    fun tearDown() {
        MicaSpectrumAnalyzer.onEnabledChanged = null
        MicaSpectrumAnalyzer.setPlaybackAdvancing(false)
        MicaSpectrumAnalyzer.setAnalysisActive(true)
        MicaSpectrumAnalyzer.setEnabled(false)
    }

    @Test
    fun softPauseStopsAnalysisWithoutDisablingAudioProcessorOrRefreshingPipeline() {
        MicaSpectrumAnalyzer.setEnabled(true)
        val processor = SpectrumAudioProcessor()
        val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        var pipelineNotifications = 0
        MicaSpectrumAnalyzer.onEnabledChanged = { pipelineNotifications += 1 }

        MicaSpectrumAnalyzer.setAnalysisActive(false)
        val pausedInput = ByteBuffer.wrap(byteArrayOf(0x01, 0x02, 0x03, 0x04))
            .order(ByteOrder.LITTLE_ENDIAN)
        processor.queueInput(pausedInput)
        processor.getOutput()

        assertTrue(processor.isActive())
        assertFalse(MicaSpectrumAnalyzer.isAnalysisActive())
        assertEquals(0, MicaSpectrumAnalyzer.queuedPcmSampleCount())
        assertEquals(0, pipelineNotifications)

        MicaSpectrumAnalyzer.setAnalysisActive(true)
        val activeInput = ByteBuffer.wrap(byteArrayOf(0x01, 0x02, 0x03, 0x04))
            .order(ByteOrder.LITTLE_ENDIAN)
        processor.queueInput(activeInput)
        processor.getOutput()

        assertTrue(processor.isActive())
        assertTrue(MicaSpectrumAnalyzer.isAnalysisActive())
        assertTrue(MicaSpectrumAnalyzer.queuedPcmSampleCount() > 0)
        assertEquals(0, pipelineNotifications)
    }

    @Test
    fun inactiveWhenSpectrumDisabled() {
        MicaSpectrumAnalyzer.setEnabled(false)
        val processor = SpectrumAudioProcessor()
        val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        assertFalse(processor.isActive())
    }

    @Test
    fun passesThroughPcmWhenDisabled() {
        MicaSpectrumAnalyzer.setEnabled(false)
        val processor = SpectrumAudioProcessor()
        val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)

        val input = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val buffer = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        processor.queueInput(buffer)
        val output = processor.getOutput()

        val outBytes = ByteArray(output.remaining())
        output.get(outBytes)
        assertArrayEquals(input, outBytes)
    }

    @Test
    fun passesThroughPcmWhenEnabled() {
        MicaSpectrumAnalyzer.setEnabled(true)
        val processor = SpectrumAudioProcessor()
        val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        assertTrue(processor.isActive())

        val input = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val buffer = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        processor.queueInput(buffer)
        val output = processor.getOutput()

        val outBytes = ByteArray(output.remaining())
        output.get(outBytes)
        assertArrayEquals(input, outBytes)
    }

    @Test
    fun queueCapacityRetainsCompleteLargeApePcmFrame() {
        val sampleRate = 44_100
        val apeFrameSamples = 73_728

        assertTrue(
            "APE's 1.67 second decoder frame must not be truncated to the old 1.2 second queue",
            MicaSpectrumAnalyzer.maxQueuedPcmSampleCount(sampleRate) >= apeFrameSamples,
        )
    }

    @Test
    fun queueCapacityAdaptsToTwoLargeApeFramesWithoutChangingNormalFormats() {
        val policy = SpectrumQueueCapacityPolicy()
        val sampleRate = 44_100

        assertEquals(88_200, policy.capacitySamples(sampleRate, inputBlockSamples = 4_096))
        assertEquals(147_456, policy.capacitySamples(sampleRate, inputBlockSamples = 73_728))
    }

    @Test
    fun dynamicQueueCapacityIsBoundedAndResetsWithAnalysisState() {
        val policy = SpectrumQueueCapacityPolicy()
        val sampleRate = 44_100

        assertEquals(176_400, policy.capacitySamples(sampleRate, inputBlockSamples = 200_000))

        policy.reset()

        assertEquals(88_200, policy.capacitySamples(sampleRate, inputBlockSamples = 4_096))
        assertEquals(192_000, policy.capacitySamples(96_000, inputBlockSamples = 4_096))
    }

    @Test
    fun pausedPlaybackRetainsPrebufferUntilPlaybackAdvances() {
        MicaSpectrumAnalyzer.setEnabled(true)
        MicaSpectrumAnalyzer.setAnalysisActive(true)
        MicaSpectrumAnalyzer.setPlaybackAdvancing(false)
        MicaSpectrumAnalyzer.processPcmBuffer(
            buffer = ByteArray(4_096 * 2 * 2),
            offset = 0,
            length = 4_096 * 2 * 2,
            encoding = android.media.AudioFormat.ENCODING_PCM_16BIT,
            sampleRateHz = 44_100,
            channelCount = 2,
        )
        val prebufferedSamples = MicaSpectrumAnalyzer.queuedPcmSampleCount()

        repeat(5) { MicaSpectrumAnalyzer.analyzeTickForTest() }

        assertEquals(prebufferedSamples, MicaSpectrumAnalyzer.queuedPcmSampleCount())

        MicaSpectrumAnalyzer.setPlaybackAdvancing(true)
        MicaSpectrumAnalyzer.analyzeTickForTest()

        assertTrue(MicaSpectrumAnalyzer.queuedPcmSampleCount() < prebufferedSamples)
    }
}
