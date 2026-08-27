package com.mica.music.media.usbhybrid

import com.mica.music.usb.UsbStableIdentity

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
    fun transportClosedErrorIsRetiredOnlyAfterEpochChanges() {
        val error = "USB exclusive PCM transport is not open."
        assertEquals(UsbRealtimeResult.Failed(error), classifyUsbRealtimeResult(error, 4L, 4L))
        assertEquals(UsbRealtimeResult.Retired, classifyUsbRealtimeResult(error, 4L, 5L))
    }

    @Test
    fun physicalDisconnectErrorIsRecognizedWithoutMaskingOtherIoFailures() {
        assertTrue(isUsbRealtimeTransportUnavailableError("USBDEVFS_SUBMITURB failed: No such device"))
        assertTrue(isUsbRealtimeTransportUnavailableError("ENODEV"))
        assertFalse(isUsbRealtimeTransportUnavailableError("No such file or directory"))
    }

    @Test
    fun physicalDisconnectBackpressuresWithoutConsumingPcmBuffer() {
        val fixture = fixture()
        fixture.use {
            it.sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            it.sink.play()
            it.realtime.nextWriteResult = UsbRealtimeResult.Failed("USBDEVFS_SUBMITURB failed: No such device")
            val buffer = ByteBuffer.wrap(ByteArray(8) { 6 })

            assertFalse(it.sink.handleBuffer(buffer, 0L, 1))
            assertEquals(0, buffer.position())
            assertEquals(1, it.realtime.writes.size)
            assertFalse(it.sink.handleBuffer(buffer, 0L, 1))
            assertEquals(1, it.realtime.writes.size)
        }
    }

    @Test
    fun advertisesIntegerPcm16Pcm24Pcm32ButNotFloat() {
        val fixture = fixture()
        fixture.use {
            assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, it.sink.getFormatSupport(format(C.ENCODING_PCM_16BIT)))
            assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, it.sink.getFormatSupport(format(C.ENCODING_PCM_24BIT)))
            assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, it.sink.getFormatSupport(format(C.ENCODING_PCM_32BIT)))
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, it.sink.getFormatSupport(format(C.ENCODING_PCM_FLOAT)))
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

    @Test
    fun supersededEpochCannotSubmitPcmEvenBeforeSinkStops() {
        val fixture = fixture()
        fixture.use {
            it.sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            it.sink.play()
            it.owner.request(UsbExclusiveMode.SHARED_PCM, null, null)
            it.owner.awaitIdle()
            val buffer = ByteBuffer.wrap(ByteArray(8) { 3 })

            assertTrue(it.sink.handleBuffer(buffer, 0L, 1))
            assertEquals(buffer.limit(), buffer.position())
            assertEquals(0, it.realtime.writes.size)
            assertEquals(1, it.effects.openCount)
        }
    }

    @Test
    fun semanticPauseClosesSourceWritesBeforeDelayedAudioSinkPause() {
        val fixture = fixture()
        fixture.use {
            it.sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            it.sink.play()
            val first = ByteBuffer.wrap(ByteArray(8) { 1 })
            assertTrue(it.sink.handleBuffer(first, 0L, 1))
            assertEquals(1, it.realtime.writes.size)

            val epoch = it.owner.currentEpoch()
            assertTrue(it.owner.setSemanticPlayWhenReady(epoch, false))
            val staged = ByteBuffer.wrap(ByteArray(8) { 2 })
            val blocked = ByteBuffer.wrap(ByteArray(8) { 3 })
            assertTrue(it.sink.handleBuffer(staged, 1_000L, 1))
            assertFalse(it.sink.handleBuffer(blocked, 2_000L, 1))
            assertEquals(1, it.realtime.writes.size)

            assertTrue(it.owner.setSemanticPlayWhenReady(epoch, true))
            assertTrue(it.sink.handleBuffer(blocked, 2_000L, 1))
            assertEquals(3, it.realtime.writes.size)
        }
    }

    @Test
    fun sameEpochRetiredSessionReopensBeforeConsumingFirstPcmBuffer() {
        val fixture = fixture()
        fixture.use {
            it.sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            it.sink.play()
            it.realtime.nextWriteResult = UsbRealtimeResult.Retired
            val buffer = ByteBuffer.wrap(ByteArray(8) { 4 })

            assertTrue(it.sink.handleBuffer(buffer, 0L, 1))

            assertEquals(2, it.effects.openCount)
            assertEquals(2, it.realtime.writes.size)
            assertEquals(buffer.limit(), buffer.position())
            assertTrue(it.sink.getCurrentPositionUs(false) != AudioSink.CURRENT_POSITION_NOT_SET)
        }
    }

    @Test
    fun configureAfterRendererStartedKeepsPcmSinkPlaying() {
        val fixture = fixture()
        fixture.use {
            it.sink.play()
            it.sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            val buffer = ByteBuffer.wrap(ByteArray(8) { 5 })

            assertTrue(it.sink.handleBuffer(buffer, 0L, 1))

            assertEquals(1, it.realtime.writes.size)
            assertEquals(buffer.limit(), buffer.position())
            assertTrue(it.sink.getCurrentPositionUs(false) != AudioSink.CURRENT_POSITION_NOT_SET)
        }
    }

    @Test
    fun flushDuringStartedPlaybackKeepsSinkPlayingAtNewPosition() {
        val fixture = fixture()
        fixture.use {
            it.sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            it.sink.play()
            val beforeFlush = ByteBuffer.wrap(ByteArray(8) { 7 })
            assertTrue(it.sink.handleBuffer(beforeFlush, 1_000_000L, 1))

            it.sink.flush()

            val firstAfterFlush = ByteBuffer.wrap(ByteArray(8) { 8 })
            val secondAfterFlush = ByteBuffer.wrap(ByteArray(8) { 9 })
            assertTrue(it.sink.handleBuffer(firstAfterFlush, 0L, 1))
            assertTrue(it.sink.handleBuffer(secondAfterFlush, 1_000L, 1))
            assertEquals(3, it.realtime.writes.size)
            assertEquals(firstAfterFlush.limit(), firstAfterFlush.position())
            assertEquals(secondAfterFlush.limit(), secondAfterFlush.position())
            assertTrue(it.sink.getCurrentPositionUs(false) != AudioSink.CURRENT_POSITION_NOT_SET)
        }
    }

    @Test
    fun positionAdvancesFromSingleMediaClockWithoutWaitingForCompletedSourceFrames() {
        val clock = FakeClock()
        val fixture = fixture(clock)
        fixture.use {
            it.sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            it.sink.play()
            val buffer = ByteBuffer.wrap(ByteArray(19_200)) // 100 ms at 48 kHz stereo PCM16.
            assertTrue(it.sink.handleBuffer(buffer, 1_000_000L, 1))

            assertEquals(1_000_000L, it.sink.getCurrentPositionUs(false))
            clock.nowUs += 5_000L
            assertEquals(1_005_000L, it.sink.getCurrentPositionUs(false))
            clock.nowUs += 3_000L
            assertEquals(1_008_000L, it.sink.getCurrentPositionUs(false))
        }
    }

    @Test
    fun media3BufferIsSubmittedToNativeFifoWithoutJavaSideThrottling() {
        val fixture = fixture()
        fixture.use {
            it.sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            it.sink.play()
            val buffer = ByteBuffer.wrap(ByteArray(4_608)) // 24 ms.

            assertTrue(it.sink.handleBuffer(buffer, 2_000_000L, 1))
            assertEquals(buffer.limit(), buffer.position())
            assertEquals(1, it.realtime.writes.size)
            assertEquals(4_608, it.realtime.writes.single().size)
        }
    }

    @Test
    fun seekStartsNewConsumptionGenerationBeforeAcceptingNewPcm() {
        val clock = FakeClock()
        val fixture = fixture(clock)
        fixture.use {
            it.sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            it.sink.play()
            assertTrue(it.sink.handleBuffer(ByteBuffer.wrap(ByteArray(1_536)), 4_000_000L, 1))
            clock.nowUs += 8_000L
            assertEquals(4_008_000L, it.sink.getCurrentPositionUs(false))

            it.sink.handleDiscontinuity()
            assertEquals(2, it.realtime.timelineBegins)
            assertTrue(it.sink.handleBuffer(ByteBuffer.wrap(ByteArray(1_536)), 500_000L, 1))
            assertEquals(500_000L, it.sink.getCurrentPositionUs(false))
        }
    }

    @Test
    fun usbCompletionBurstsCannotCorrectOrJumpTheMediaClock() {
        val clock = FakeClock()
        val fixture = fixture(clock)
        fixture.use {
            it.sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            it.sink.play()
            assertTrue(it.sink.handleBuffer(ByteBuffer.wrap(ByteArray(18_432)), 1_000_000L, 1))

            it.realtime.consumedSourceFrames = 480L
            assertEquals(1_000_000L, it.sink.getCurrentPositionUs(false))
            clock.nowUs += 4_000L
            assertEquals(1_004_000L, it.sink.getCurrentPositionUs(false))

            // Neither a lagging completion sample nor a large completion burst may correct UI time.
            it.realtime.consumedSourceFrames = 120L
            clock.nowUs += 2_000L
            assertEquals(1_006_000L, it.sink.getCurrentPositionUs(false))

            it.realtime.consumedSourceFrames = 9_600L
            assertEquals(1_006_000L, it.sink.getCurrentPositionUs(false))
        }
    }

    @Test
    fun pauseAndResumeFreezeThenContinueTheSameMediaClock() {
        val clock = FakeClock()
        val fixture = fixture(clock)
        fixture.use {
            it.sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            it.sink.play()
            assertTrue(it.sink.handleBuffer(ByteBuffer.wrap(ByteArray(9_216)), 2_000_000L, 1))
            clock.nowUs += 5_000L
            assertEquals(2_005_000L, it.sink.getCurrentPositionUs(false))

            it.sink.pause()
            clock.nowUs += 1_000_000L
            assertEquals(2_005_000L, it.sink.getCurrentPositionUs(false))
            it.sink.play()
            clock.nowUs += 5_000L

            assertEquals(2_010_000L, it.sink.getCurrentPositionUs(false))
        }
    }

    private fun fixture(clock: FakeClock = FakeClock()): Fixture {
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
        assertTrue(owner.setSemanticPlayWhenReady(epoch, true))
        val realtime = Realtime()
        return Fixture(effects, owner, realtime, UsbHybridPcmAudioSink(owner, realtime, epoch, clock::read))
    }

    private fun format(encoding: Int) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(encoding)
        .setSampleRate(48_000)
        .setChannelCount(2)
        .build()

    private class Effects : UsbHybridControlEffects {
        val permission = CountDownLatch(1)
        var openCount = 0
        override fun publishActiveEpoch(epoch: UsbRequestEpoch) = Unit
        override fun requestPermission(request: UsbPermissionRequest) { permission.countDown() }
        override fun open(request: UsbOpenRequest): UsbOpenResult {
            openCount += 1
            return UsbOpenResult(
                sessionId = UsbTransportSessionId(request.epoch, 80L + openCount),
                claimed = true,
                transportExact = true,
                signalExact = true,
                sourceEncoding = C.ENCODING_PCM_16BIT,
                usbBitResolution = 32,
                sampleRate = 48_000,
                channels = 2,
            )
        }
        override fun close(sessionId: UsbTransportSessionId) = Unit
    }

    private class Realtime : UsbHybridRealtimePort {
        val writes = mutableListOf<ByteArray>()
        var nextWriteResult: UsbRealtimeResult = UsbRealtimeResult.Success
        var consumedSourceFrames = 0L
        var timelineBegins = 0
        override fun writePcm(sessionId: UsbTransportSessionId, data: ByteArray): UsbRealtimeResult {
            writes += data
            return nextWriteResult.also { nextWriteResult = UsbRealtimeResult.Success }
        }
        override fun finishPcm(sessionId: UsbTransportSessionId) = UsbRealtimeResult.Success
        override fun beginPcmTimeline(sessionId: UsbTransportSessionId): UsbRealtimeResult {
            timelineBegins += 1
            consumedSourceFrames = 0L
            return UsbRealtimeResult.Success
        }
        override fun consumedPcmSourceFrames(sessionId: UsbTransportSessionId): Long = consumedSourceFrames
        override fun resetPcmForSeek(sessionId: UsbTransportSessionId) = Unit
        override fun telemetry(sessionId: UsbTransportSessionId) = UsbRealtimeTelemetry(0, 0, 0, 0)
        override fun writeDsd(sessionId: UsbTransportSessionId, data: ByteArray) = UsbRealtimeResult.Failed("not-used")
        override fun prepareDsdSeek(sessionId: UsbTransportSessionId) = UsbRealtimeResult.Failed("not-used")
        override fun pauseDsd(sessionId: UsbTransportSessionId) = UsbRealtimeResult.Failed("not-used")
        override fun resumeDsd(sessionId: UsbTransportSessionId) = UsbRealtimeResult.Failed("not-used")
        override fun finishDsd(sessionId: UsbTransportSessionId) = UsbRealtimeResult.Failed("not-used")
    }

    private class FakeClock(var nowUs: Long = 1_000_000L) {
        fun read(): Long = nowUs
    }

    private data class Fixture(
        val effects: Effects,
        val owner: UsbHybridSessionOwner,
        val realtime: Realtime,
        val sink: UsbHybridPcmAudioSink,
    ) : AutoCloseable {
        override fun close() { owner.close() }
    }
}
