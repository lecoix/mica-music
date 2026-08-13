package com.mica.music.media.usbprototype

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDirectDsdPauseLivenessSupportTest {
    @Test
    fun closeDrainOnlyPumpsAlreadyAcceptedCarrier() {
        val source = File(
            "src/debug/java/com/mica/music/media/usbprototype/UsbDirectDsdTransportSession.kt",
        ).readText()
        val body = source.substringAfter("private fun drainCommittedCarrierBeforeClose()")
            .substringBefore("private fun maybeMarkStartupPrefillReady")

        assertTrue(body.contains("feeder.pump()"))
        assertFalse(body.contains("writeContentBytes("))
        assertFalse(body.contains("writeGapFrames("))
    }

    @Test
    fun dsd128WatermarkIs250msWith50msHysteresisAndNeverCrossesHigh() {
        val policy = UsbDirectDsdBufferPolicy.create(
            carrierRateHz = 352_800,
            requiredPrefillFrames = 6_400,
            capacityFrames = 705_600,
        )

        assertEquals(88_200L, policy.highWatermarkFrames)
        assertEquals(70_560L, policy.lowWatermarkFrames)
        assertFalse(policy.shouldBeginRefill(70_560L))
        assertTrue(policy.shouldBeginRefill(70_559L))
        assertEquals(0, policy.allowedSinkBytes(88_200L, 6 * 4_096, 6))
        assertEquals(6 * 10, policy.allowedSinkBytes(88_190L, 6 * 4_096, 6))
        assertEquals(4_096, policy.refillRequestFrames(70_000L, 4_096))
        assertEquals(100, policy.refillRequestFrames(88_100L, 4_096))
        assertEquals(0, policy.refillRequestFrames(88_200L, 4_096))
    }

    @Test
    fun watermarkClampsAboveNativeMinimumAndBelowCapacity() {
        val policy = UsbDirectDsdBufferPolicy.create(
            carrierRateHz = 176_400,
            requiredPrefillFrames = 50_000,
            capacityFrames = 60_000,
        )

        assertEquals(50_000L, policy.highWatermarkFrames)
        assertEquals(50_000L, policy.lowWatermarkFrames)
        assertTrue(policy.highWatermarkFrames < policy.capacityFrames)
    }

    @Test
    fun gapAndContentWriterNeverOverlap() {
        val controller = UsbDirectDsdPauseLivenessController(joinTimeoutMs = 1_000)
        val gapEntered = CountDownLatch(1)
        val releaseGap = CountDownLatch(1)
        val contentEntered = AtomicBoolean(false)
        val contentFailure = AtomicReference<Throwable?>()

        controller.startGap {
            gapEntered.countDown()
            assertTrue(releaseGap.await(1, TimeUnit.SECONDS))
            50L
        }
        assertTrue(gapEntered.await(1, TimeUnit.SECONDS))

        val content = Thread {
            try {
                controller.withContentWriter { contentEntered.set(true) }
            } catch (error: Throwable) {
                contentFailure.set(error)
            }
        }
        content.start()
        Thread.sleep(25)
        assertFalse(contentEntered.get())

        releaseGap.countDown()
        content.join(1_000)
        assertFalse(content.isAlive)
        assertFalse(contentEntered.get())
        assertNotNull(contentFailure.get())

        controller.stopGapAndJoin()
        assertEquals(UsbDirectDsdWriterPhase.CONTENT, controller.snapshot().phase)
    }

    @Test
    fun stopGapJoinsBeforeContentOwnershipReturns() {
        val controller = UsbDirectDsdPauseLivenessController(joinTimeoutMs = 1_000)
        val gapEntered = CountDownLatch(1)

        controller.startGap {
            gapEntered.countDown()
            1_000L
        }
        assertTrue(gapEntered.await(1, TimeUnit.SECONDS))
        assertEquals(UsbDirectDsdWriterPhase.GAP, controller.snapshot().phase)

        controller.stopGapAndJoin()
        val stopped = controller.snapshot()
        assertEquals(UsbDirectDsdWriterPhase.CONTENT, stopped.phase)
        assertFalse(stopped.workerAlive)

        var contentRuns = 0
        controller.withContentWriter { contentRuns++ }
        assertEquals(1, contentRuns)
        controller.stopGapAndJoin()
    }

    @Test
    fun workerFailureFailsClosedAndCloseRemainsIdempotent() {
        val controller = UsbDirectDsdPauseLivenessController(joinTimeoutMs = 1_000)
        val failed = CountDownLatch(1)

        controller.startGap {
            failed.countDown()
            error("boom")
        }
        assertTrue(failed.await(1, TimeUnit.SECONDS))
        repeat(100) {
            if (controller.snapshot().phase == UsbDirectDsdWriterPhase.FAILED) return@repeat
            Thread.sleep(2)
        }
        assertEquals(UsbDirectDsdWriterPhase.FAILED, controller.snapshot().phase)

        var stopFailed = false
        try {
            controller.stopGapAndJoin()
        } catch (_: IllegalStateException) {
            stopFailed = true
        }
        assertTrue(stopFailed)

        var contentFailed = false
        try {
            controller.withContentWriter { error("must not run") }
        } catch (_: IllegalStateException) {
            contentFailed = true
        }
        assertTrue(contentFailed)

        var closeFailed = false
        try {
            controller.closeAndJoin()
        } catch (_: IllegalStateException) {
            closeFailed = true
        }
        assertTrue(closeFailed)
        assertEquals(UsbDirectDsdWriterPhase.CLOSED, controller.snapshot().phase)
        controller.closeAndJoin()
    }
}
