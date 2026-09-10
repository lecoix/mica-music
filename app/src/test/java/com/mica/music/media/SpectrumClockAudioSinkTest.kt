package com.mica.music.media

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class SpectrumClockAudioSinkTest {
    private var now = 0L
    private var envelope = 0f
    private fun engine() = SpectrumAnalysisEngine(nowNanos = { now }, publish = { _, e -> envelope = e })
        .apply { setEnabled(true); setAnalysisActive(true); setPlaybackAdvancing(true) }

    @Test fun floatBackpressureKeepsRealTimestampedAudioThroughBackgroundResume() {
        val analyzer = engine()
        val session = SpectrumSinkSession(analyzer)
        val inner = mockk<AudioSink>(relaxed = true)
        every { inner.handleBuffer(any(), any(), any()) } returns false
        var clock = 100_000L
        every { inner.getCurrentPositionUs(any()) } answers { clock }
        val tap = object : MicaFloatDspAudioSink.FloatPcmDspTap {
            override fun configure(sampleRate: Int, channelCount: Int) = Unit
            override fun isActive() = analyzer.isCaptureActive()
            override fun process(bytes: ByteArray, offset: Int, length: Int, androidEncoding: Int, sampleRate: Int, channelCount: Int) {
                session.capture(bytes, offset, length, androidEncoding, sampleRate, channelCount, true)
            }
        }
        val sink = SpectrumClockAudioSink(MicaFloatDspAudioSink(inner, tap), session)
        sink.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT).setSampleRate(48_000).setChannelCount(1).build(), 0, null)
        val buffer = ByteBuffer.allocateDirect(96_000 * 4).order(ByteOrder.nativeOrder())
        repeat(96_000) { buffer.putFloat(sin(it * 0.13).toFloat() * 0.5f) }
        buffer.flip()
        assertFalse(sink.handleBuffer(buffer, 0, 1))
        analyzer.setAnalysisActive(false)
        analyzer.setAnalysisActive(true)
        repeat(90) {
            now += 16_666_667
            clock += 16_667
            assertFalse(sink.handleBuffer(buffer, 0, 1)) // actual renderer retry, no new PCM
            assertEquals(clock, sink.getCurrentPositionUs(false))
            analyzer.tick()
            assertTrue("frame $it", envelope > 0)
        }
        assertEquals(96_000, analyzer.bufferedSamples())
        sink.flush()
        assertEquals(0, analyzer.bufferedSamples())
        assertEquals(0f, envelope)
    }

    @Test fun processorClockAnchorsAtSeekAndReanchorsAfterTuningFlushWithoutChangingBytes() {
        val analyzer = engine()
        val session = SpectrumSinkSession(analyzer)
        val processor = SpectrumAudioProcessor(session)
        val format = AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        val inner = mockk<AudioSink>(relaxed = true)
        var flushProcessors = true
        var written = ByteArray(0)
        every { inner.handleBuffer(any(), any(), any()) } answers {
            if (flushProcessors) { processor.flush(); flushProcessors = false }
            processor.queueInput(firstArg())
            val output = processor.getOutput()
            written = ByteArray(output.remaining()).also(output::get)
            true
        }
        var clock = 10_050_000L
        every { inner.getCurrentPositionUs(any()) } answers { clock }
        val sink = SpectrumClockAudioSink(inner, session)
        val bytes = ByteBuffer.allocate(4_800 * 2).order(ByteOrder.nativeOrder()).apply {
            repeat(4_800) { putShort((sin(it * 0.13) * 16_000).toInt().toShort()) }
        }.array()
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(bytes), 10_000_000L, 1))
        assertArrayEquals(bytes, written)
        sink.getCurrentPositionUs(false)
        analyzer.tick()
        assertTrue(envelope > 0)
        // Media3 drains and flushes processors at the next buffer when Sonic speed changes.
        flushProcessors = true
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(bytes), 10_100_000L, 1))
        clock = 10_150_000L
        now += 50_000_000
        sink.getCurrentPositionUs(false)
        analyzer.tick()
        assertTrue(envelope > 0)
        assertArrayEquals(bytes, written)
        sink.flush()
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(bytes), 50_000_000L, 1))
        clock = 50_050_000L
        sink.getCurrentPositionUs(false)
        analyzer.tick()
        assertTrue(envelope > 0)
    }
}
