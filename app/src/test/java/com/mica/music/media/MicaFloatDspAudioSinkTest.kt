package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MicaFloatDspAudioSinkTest {

    private class FakeTap : MicaFloatDspAudioSink.FloatPcmDspTap {
        var active = true
        var processCalls = 0
        var configureCalls = 0
        override fun configure(sampleRate: Int, channelCount: Int) {
            configureCalls++
        }
        override fun isActive(): Boolean = active
        override fun process(
            bytes: ByteArray,
            offset: Int,
            length: Int,
            androidEncoding: Int,
            sampleRate: Int,
            channelCount: Int,
        ) {
            processCalls++
            if (length > 0) bytes[offset] = MARKER
        }
    }

    private fun floatFormat(): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(96_000)
            .setChannelCount(2)
            .build()

    private fun sourceBuffer(): ByteBuffer =
        ByteBuffer.allocate(16).apply {
            repeat(16) { put(it, 0x11) }
            position(0)
            limit(16)
        }

    @Test
    fun passthrough_whenTapInactive_forwardsOriginalBuffer() {
        val inner = mockk<AudioSink>(relaxed = true)
        val captured = slot<ByteBuffer>()
        every { inner.handleBuffer(capture(captured), any(), any()) } returns true
        val tap = FakeTap().apply { active = false }
        val sink = MicaFloatDspAudioSink(inner, tap)
        sink.configure(floatFormat(), 0, null)

        val source = sourceBuffer()
        assertTrue(sink.handleBuffer(source, 0L, 1))

        assertEquals(0, tap.processCalls)
        assertSame(source, captured.captured)
    }

    @Test
    fun active_processesOnce_andForwardsProcessedBuffer() {
        val inner = mockk<AudioSink>(relaxed = true)
        val captured = slot<ByteBuffer>()
        every { inner.handleBuffer(capture(captured), any(), any()) } returns true
        val tap = FakeTap()
        val sink = MicaFloatDspAudioSink(inner, tap)
        sink.configure(floatFormat(), 0, null)

        val source = sourceBuffer()
        assertTrue(sink.handleBuffer(source, 0L, 1))

        assertEquals(1, tap.processCalls)
        assertNotSame(source, captured.captured)
        assertEquals(MARKER, captured.captured.get(0))
    }

    @Test
    fun rejectThenRetry_doesNotReprocess_andConsumesSourceOnAccept() {
        val inner = mockk<AudioSink>(relaxed = true)
        every { inner.handleBuffer(any(), any(), any()) } returnsMany listOf(false, true)
        val tap = FakeTap()
        val sink = MicaFloatDspAudioSink(inner, tap)
        sink.configure(floatFormat(), 0, null)

        val source = sourceBuffer()
        // First attempt rejected: processed once, source not yet consumed.
        assertEquals(false, sink.handleBuffer(source, 0L, 1))
        assertEquals(1, tap.processCalls)
        assertTrue(source.hasRemaining())

        // Retry with the same source: no reprocessing, accepted, source fully consumed.
        assertTrue(sink.handleBuffer(source, 0L, 1))
        assertEquals(1, tap.processCalls)
        assertEquals(source.limit(), source.position())
    }

    @Test
    fun flush_clearsInFlightState_allowingFreshProcessing() {
        val inner = mockk<AudioSink>(relaxed = true)
        every { inner.handleBuffer(any(), any(), any()) } returnsMany listOf(false, true)
        val tap = FakeTap()
        val sink = MicaFloatDspAudioSink(inner, tap)
        sink.configure(floatFormat(), 0, null)

        val source = sourceBuffer()
        assertEquals(false, sink.handleBuffer(source, 0L, 1))
        assertEquals(1, tap.processCalls)

        sink.flush()

        // After flush the same buffer object is treated as a fresh source → reprocessed.
        assertTrue(sink.handleBuffer(source, 0L, 1))
        assertEquals(2, tap.processCalls)
    }

    @Test
    fun nonRawMime_passesThroughWithoutProcessing() {
        val inner = mockk<AudioSink>(relaxed = true)
        val captured = slot<ByteBuffer>()
        every { inner.handleBuffer(capture(captured), any(), any()) } returns true
        val tap = FakeTap()
        val sink = MicaFloatDspAudioSink(inner, tap)
        sink.configure(
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).build(),
            0,
            null,
        )

        val source = sourceBuffer()
        assertTrue(sink.handleBuffer(source, 0L, 1))

        assertEquals(0, tap.processCalls)
        assertSame(source, captured.captured)
    }

    private companion object {
        const val MARKER: Byte = 0x7F
    }
}
