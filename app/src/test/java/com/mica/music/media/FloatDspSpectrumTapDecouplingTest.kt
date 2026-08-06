package com.mica.music.media

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies spectrum PCM capture stays fed when inner AudioTrack writes are backpressured.
 */
class FloatDspSpectrumTapDecouplingTest {

    private class AnalyzerTap : MicaFloatDspAudioSink.FloatPcmDspTap {
        override fun configure(sampleRate: Int, channelCount: Int) = Unit
        override fun isActive(): Boolean = MicaSpectrumAnalyzer.isAnalysisActive()
        override fun process(
            bytes: ByteArray,
            offset: Int,
            length: Int,
            androidEncoding: Int,
            sampleRate: Int,
            channelCount: Int,
        ) {
            MicaSpectrumAnalyzer.processPcmBuffer(
                buffer = bytes,
                offset = offset,
                length = length,
                encoding = androidEncoding,
                sampleRateHz = sampleRate,
                channelCount = channelCount,
            )
        }
    }

    @After
    fun tearDown() {
        MicaSpectrumAnalyzer.onEnabledChanged = null
        MicaSpectrumAnalyzer.setPlaybackAdvancing(false)
        MicaSpectrumAnalyzer.setAnalysisActive(true)
        MicaSpectrumAnalyzer.setEnabled(false)
        MicaSpectrumAnalyzer.resetBufferedPcm("test-teardown")
    }

    @Test
    fun flacSizedBuffer_secondBufferTappedWhileFirstWritePending() {
        MicaSpectrumAnalyzer.setEnabled(true)
        MicaSpectrumAnalyzer.setAnalysisActive(true)
        MicaSpectrumAnalyzer.setPlaybackAdvancing(false)

        val inner = mockk<AudioSink>(relaxed = true)
        every { inner.handleBuffer(any(), any(), any()) } returnsMany listOf(false, false, true, true)
        val sink = MicaFloatDspAudioSink(inner, AnalyzerTap())
        sink.configure(floatFormat(sampleRate = 48_000), 0, null)

        val flacFrames = 4_096
        val first = floatStereoBuffer(frames = flacFrames, baseSample = 0.1f)
        val second = floatStereoBuffer(frames = flacFrames, baseSample = 0.5f)

        assertFalse(sink.handleBuffer(first, 0L, 1))
        assertEquals(flacFrames, MicaSpectrumAnalyzer.queuedPcmSampleCount())

        assertFalse(sink.handleBuffer(second, 1L, 1))
        assertEquals(flacFrames * 2, MicaSpectrumAnalyzer.queuedPcmSampleCount())

        MicaSpectrumAnalyzer.setPlaybackAdvancing(true)
        repeat(5) { MicaSpectrumAnalyzer.analyzeTickForTest() }
        assertTrue(MicaSpectrumAnalyzer.queuedPcmSampleCount() > 0)

        assertTrue(sink.handleBuffer(emptyBuffer(), 1L, 1))
    }

    @Test
    fun apeSizedFrame_retainedWhileWritePending() {
        MicaSpectrumAnalyzer.setEnabled(true)
        MicaSpectrumAnalyzer.setAnalysisActive(true)
        MicaSpectrumAnalyzer.setPlaybackAdvancing(false)

        val inner = mockk<AudioSink>(relaxed = true)
        every { inner.handleBuffer(any(), any(), any()) } returns false
        val sink = MicaFloatDspAudioSink(inner, AnalyzerTap())
        sink.configure(floatFormat(sampleRate = 44_100), 0, null)

        val apeFrames = 73_728
        val frame = floatStereoBuffer(frames = apeFrames, baseSample = 0.25f)

        assertFalse(sink.handleBuffer(frame, 0L, 1))
        assertEquals(apeFrames, MicaSpectrumAnalyzer.queuedPcmSampleCount())

        MicaSpectrumAnalyzer.setPlaybackAdvancing(true)
        repeat(60) { MicaSpectrumAnalyzer.analyzeTickForTest() }
        assertTrue(
            "APE frame should survive analysis drain while inner write is pending",
            MicaSpectrumAnalyzer.queuedPcmSampleCount() > 0,
        )
    }

    @Test
    fun apeThenFlac_bothRetainedUnderSustainedInnerReject() {
        MicaSpectrumAnalyzer.setEnabled(true)
        MicaSpectrumAnalyzer.setAnalysisActive(true)
        MicaSpectrumAnalyzer.setPlaybackAdvancing(false)

        val inner = mockk<AudioSink>(relaxed = true)
        every { inner.handleBuffer(any(), any(), any()) } returns false
        val sink = MicaFloatDspAudioSink(inner, AnalyzerTap())
        sink.configure(floatFormat(sampleRate = 44_100), 0, null)

        val apeFrames = 73_728
        val flacFrames = 4_096
        assertFalse(sink.handleBuffer(floatStereoBuffer(apeFrames, 0.2f), 0L, 1))
        assertFalse(sink.handleBuffer(floatStereoBuffer(flacFrames, 0.6f), 1L, 1))

        assertEquals(apeFrames + flacFrames, MicaSpectrumAnalyzer.queuedPcmSampleCount())

        MicaSpectrumAnalyzer.setPlaybackAdvancing(true)
        val queuedBeforeDrain = MicaSpectrumAnalyzer.queuedPcmSampleCount()
        repeat(120) { MicaSpectrumAnalyzer.analyzeTickForTest() }
        assertTrue(MicaSpectrumAnalyzer.queuedPcmSampleCount() < queuedBeforeDrain)
    }

    @Test
    fun int16Path_unaffectedByFloatSinkDecoupling() {
        MicaSpectrumAnalyzer.setEnabled(true)
        MicaSpectrumAnalyzer.setAnalysisActive(true)
        MicaSpectrumAnalyzer.setPlaybackAdvancing(true)

        val processor = SpectrumAudioProcessor()
        processor.configure(
            androidx.media3.common.audio.AudioProcessor.AudioFormat(
                48_000,
                2,
                C.ENCODING_PCM_16BIT,
            ),
        )
        val pcm = int16StereoBuffer(frames = 2_048)
        processor.queueInput(pcm)
        val output = processor.getOutput()
        assertTrue(output.hasRemaining())

        assertEquals(2_048, MicaSpectrumAnalyzer.queuedPcmSampleCount())
    }

    private fun floatFormat(sampleRate: Int, channelCount: Int = 2): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelCount(channelCount)
            .build()

    private fun floatStereoBuffer(frames: Int, baseSample: Float): ByteBuffer {
        val bytes = frames * 2 * Float.SIZE_BYTES
        val buffer = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { index ->
            buffer.putFloat(baseSample + (index % 16) * 0.001f)
        }
        buffer.flip()
        return buffer
    }

    private fun int16StereoBuffer(frames: Int): ByteBuffer {
        val bytes = frames * 2 * Short.SIZE_BYTES
        val buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { index ->
            buffer.putShort((index * 100).toShort())
        }
        buffer.flip()
        return buffer
    }

    private fun emptyBuffer(): ByteBuffer = ByteBuffer.allocate(0)
}
