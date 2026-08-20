package com.mica.music.media.usbhybrid

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHybridPcmAudioSinkTest {
    @Test
    fun advertisesPcm16AndPcm32ButNotFloatOrPcm24() {
        val fixture = fixture()
        fixture.use {
            assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, it.sink.getFormatSupport(format(C.ENCODING_PCM_16BIT)))
            assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, it.sink.getFormatSupport(format(C.ENCODING_PCM_32BIT)))
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, it.sink.getFormatSupport(format(C.ENCODING_PCM_FLOAT)))
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, it.sink.getFormatSupport(format(C.ENCODING_PCM_24BIT)))
        }
    }

    @Test
    fun pausedSinkKeepsOneBufferThenBackpressuresUntilPlay() {
        val fixture = fixture()
        fixture.use {
            it.sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            val first = ByteBuffer.wrap(ByteArray(8) { 1 })
            val second = ByteBuffer.wrap(ByteArray(8) { 2 })

            assertTrue(it.sink.handleBuffer(first, 0L, 1))
            assertFalse(it.sink.handleBuffer(second, 1_000L, 1))
            assertEquals(0, it.realtime.writes.size)
            assertEquals(0, second.position())

            it.sink.play()
            assertTrue(it.sink.handleBuffer(second, 1_000L, 1))
            assertEquals(2, it.realtime.writes.size)
        }
    }

    private fun fixture(): Fixture {
        val effects = Effects()
        val owner = UsbHybridSessionOwner(effects)
        val identity = UsbStableIdentity(0x262a, 1, 0x0100, "digest")
        val runtime = UsbRuntimeHandle(7, "/dev/bus/usb/001/007")
        val epoch = owner.request(UsbExclusiveMode.USB_EXACT_PCM, identity, runtime)
        assertTrue(effects.permission.await(2, TimeUnit.SECONDS))
        owner.onPermissionResult(
            UsbPermissionResult(epoch, UsbExclusiveMode.USB_EXACT_PCM, identity, runtime, true),
        )
        owner.awaitIdle()
        val realtime = Realtime()
        return Fixture(owner, realtime, UsbHybridPcmAudioSink(owner, realtime, epoch))
    }

    private fun format(encoding: Int) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(encoding)
        .setSampleRate(48_000)
        .setChannelCount(2)
        .build()

    private class Effects : UsbHybridControlEffects {
        val permission = CountDownLatch(1)
        override fun publishActiveEpoch(epoch: UsbRequestEpoch) = Unit
        override fun requestPermission(request: UsbPermissionRequest) { permission.countDown() }
        override fun open(request: UsbOpenRequest) = UsbOpenResult(
            sessionId = UsbTransportSessionId(request.epoch, 81L),
            claimed = true,
            transportExact = true,
            signalExact = true,
            sourceEncoding = C.ENCODING_PCM_16BIT,
            usbBitResolution = 32,
            sampleRate = 48_000,
            channels = 2,
        )
        override fun close(sessionId: UsbTransportSessionId) = Unit
    }

    private class Realtime : UsbHybridRealtimePort {
        val writes = mutableListOf<ByteArray>()
        override fun writePcm(sessionId: UsbTransportSessionId, data: ByteArray): String? {
            writes += data
            return null
        }
        override fun finishPcm(sessionId: UsbTransportSessionId): String? = null
        override fun resetPcmForSeek(sessionId: UsbTransportSessionId) = Unit
        override fun telemetry(sessionId: UsbTransportSessionId) = UsbRealtimeTelemetry(0, 0, 0, 0)
    }

    private data class Fixture(
        val owner: UsbHybridSessionOwner,
        val realtime: Realtime,
        val sink: UsbHybridPcmAudioSink,
    ) : AutoCloseable {
        override fun close() { owner.close() }
    }
}
