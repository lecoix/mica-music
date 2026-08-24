package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun floatFormat(sampleRate: Int = 96_000, channelCount: Int = 2): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelCount(channelCount)
            .build()

    private fun sourceBuffer(sizeBytes: Int = 16): ByteBuffer =
        ByteBuffer.allocate(sizeBytes).apply {
            repeat(sizeBytes) { put(it, 0x11) }
            position(0)
            limit(sizeBytes)
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
        assertNotSame(source, captured.captured)
        assertEquals(0x11.toByte(), captured.captured.get(0))
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
    fun innerReject_consumesSourceAndReturnsFalseUntilWriteDrains() {
        val inner = mockk<AudioSink>(relaxed = true)
        every { inner.handleBuffer(any(), any(), any()) } returnsMany listOf(false, true)
        val tap = FakeTap()
        val sink = MicaFloatDspAudioSink(inner, tap)
        sink.configure(floatFormat(), 0, null)

        val source = sourceBuffer()
        assertFalse(sink.handleBuffer(source, 0L, 1))
        assertEquals(1, tap.processCalls)
        assertEquals(source.limit(), source.position())

        assertTrue(sink.handleBuffer(sourceBuffer(0), 0L, 1))
        assertEquals(1, tap.processCalls)
    }

    @Test
    fun partialInnerWriteKeepsPendingRemainderAndContinuesWithoutReprocessing() {
        val inner = mockk<AudioSink>(relaxed = true)
        every { inner.handleBuffer(any(), any(), any()) } answers {
            val pending = firstArg<ByteBuffer>()
            if (pending.remaining() > 8) {
                pending.position(pending.position() + 8)
                false
            } else {
                pending.position(pending.limit())
                true
            }
        }
        val tap = FakeTap()
        val sink = MicaFloatDspAudioSink(inner, tap)
        sink.configure(floatFormat(), 0, null)

        val source = sourceBuffer(16)
        assertFalse(sink.handleBuffer(source, 0L, 1))
        assertEquals(1, tap.processCalls)
        assertTrue(sink.handleBuffer(sourceBuffer(0), 0L, 1))
        assertEquals(1, tap.processCalls)
        verify(exactly = 2) { inner.handleBuffer(any(), 0L, 1) }
    }

    @Test
    fun innerRejectOnFirstBuffer_doesNotBlockTapOnSecondBuffer() {
        val inner = mockk<AudioSink>(relaxed = true)
        every { inner.handleBuffer(any(), any(), any()) } returnsMany listOf(false, false, true, true)
        val tap = FakeTap()
        val sink = MicaFloatDspAudioSink(inner, tap)
        sink.configure(floatFormat(), 0, null)

        val first = sourceBuffer()
        val second = sourceBuffer()

        assertFalse(sink.handleBuffer(first, 0L, 1))
        assertFalse(sink.handleBuffer(second, 1L, 1))
        assertEquals(2, tap.processCalls)
        assertTrue(sink.handleBuffer(sourceBuffer(0), 1L, 1))
        verify(exactly = 4) { inner.handleBuffer(any(), any(), any()) }
    }

    @Test
    fun passthroughRetry_keepsOriginalBuffer_whenTapBecomesActive() {
        val inner = mockk<AudioSink>(relaxed = true)
        val captured = mutableListOf<ByteBuffer>()
        every { inner.handleBuffer(capture(captured), any(), any()) } returnsMany listOf(false, true)
        val tap = FakeTap().apply { active = false }
        val sink = MicaFloatDspAudioSink(inner, tap)
        sink.configure(floatFormat(), 0, null)

        val source = sourceBuffer()
        assertFalse(sink.handleBuffer(source, 0L, 1))

        tap.active = true
        assertTrue(sink.handleBuffer(sourceBuffer(0), 0L, 1))

        assertEquals(0, tap.processCalls)
        assertEquals(2, captured.size)
        assertEquals(0x11.toByte(), captured[0].get(0))
        assertEquals(0x11.toByte(), captured[1].get(0))
    }

    @Test
    fun processedRetry_keepsProcessedBuffer_whenTapBecomesInactive() {
        val inner = mockk<AudioSink>(relaxed = true)
        val captured = mutableListOf<ByteBuffer>()
        every { inner.handleBuffer(capture(captured), any(), any()) } returnsMany listOf(false, true)
        val tap = FakeTap().apply { active = true }
        val sink = MicaFloatDspAudioSink(inner, tap)
        sink.configure(floatFormat(), 0, null)

        val source = sourceBuffer()
        assertFalse(sink.handleBuffer(source, 0L, 1))

        tap.active = false
        assertTrue(sink.handleBuffer(sourceBuffer(0), 0L, 1))

        assertEquals(1, tap.processCalls)
        assertEquals(2, captured.size)
        assertNotSame(source, captured[0])
        assertEquals(MARKER, captured[0].get(0))
        assertEquals(MARKER, captured[1].get(0))
        verify(exactly = 2) { inner.handleBuffer(any(), 0L, 1) }
    }

    @Test
    fun flush_clearsPendingWrite_allowingFreshProcessing() {
        val inner = mockk<AudioSink>(relaxed = true)
        every { inner.handleBuffer(any(), any(), any()) } returnsMany listOf(false, true)
        val tap = FakeTap()
        val sink = MicaFloatDspAudioSink(inner, tap)
        sink.configure(floatFormat(), 0, null)

        val source = sourceBuffer()
        assertFalse(sink.handleBuffer(source, 0L, 1))
        assertEquals(1, tap.processCalls)

        sink.flush()

        val replay = sourceBuffer()
        assertTrue(sink.handleBuffer(replay, 0L, 1))
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
        assertNotSame(source, captured.captured)
    }

    private companion object {
        const val MARKER: Byte = 0x7F
    }
}
