package com.mica.music.media.usbhybrid

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHybridSessionOwnerTest {
    private val sk02 = UsbStableIdentity(0x262a, 0x1001, 0x0100, "descriptor-a")
    private val runtimeA = UsbRuntimeHandle(11, "/dev/bus/usb/001/011")

    @Test
    fun oldPermissionResultCannotOpenAfterNewModeRequest() {
        val effects = RecordingEffects()
        UsbHybridSessionOwner(effects).use { owner ->
            val oldEpoch = owner.request(UsbExclusiveMode.USB_EXACT_PCM, sk02, runtimeA)
            effects.awaitPermissionRequest()

            val newEpoch = owner.request(UsbExclusiveMode.SHARED_PCM, null, null)
            owner.onPermissionResult(
                UsbPermissionResult(oldEpoch, UsbExclusiveMode.USB_EXACT_PCM, sk02, runtimeA, granted = true),
            )
            owner.awaitIdle()

            assertTrue(newEpoch.value > oldEpoch.value)
            assertEquals(0, effects.openCount)
            assertEquals(UsbExclusiveMode.SHARED_PCM, owner.facts.value.requestedMode)
            assertNull(owner.facts.value.activeMode)
        }
    }

    @Test
    fun permissionResultFromReplacedRuntimeTargetFailsClosed() {
        val effects = RecordingEffects()
        UsbHybridSessionOwner(effects).use { owner ->
            val epoch = owner.request(UsbExclusiveMode.USB_EXACT_PCM, sk02, runtimeA)
            effects.awaitPermissionRequest()
            owner.onPermissionResult(
                UsbPermissionResult(
                    epoch,
                    UsbExclusiveMode.USB_EXACT_PCM,
                    sk02.copy(descriptorDigest = "descriptor-replacement"),
                    runtimeA,
                    granted = true,
                ),
            )
            owner.awaitIdle()

            assertEquals("TARGET_CHANGED", owner.facts.value.failure?.code)
            assertEquals(PermissionState.DENIED, owner.facts.value.permission)
            assertEquals(0, effects.openCount)
        }
    }

    @Test
    fun openThatFinishesAfterSupersedeIsClosedWithoutPublishingActiveFacts() {
        val openStarted = CountDownLatch(1)
        val allowOpen = CountDownLatch(1)
        val effects = RecordingEffects(openStarted, allowOpen)
        UsbHybridSessionOwner(effects).use { owner ->
            val oldEpoch = owner.request(UsbExclusiveMode.USB_EXACT_PCM, sk02, runtimeA)
            effects.awaitPermissionRequest()
            owner.onPermissionResult(
                UsbPermissionResult(oldEpoch, UsbExclusiveMode.USB_EXACT_PCM, sk02, runtimeA, granted = true),
            )
            owner.awaitIdle()
            owner.requestOpen(oldEpoch, UsbStreamFormat.Pcm(96_000, 2, 32))
            assertTrue(openStarted.await(2, TimeUnit.SECONDS))

            owner.request(UsbExclusiveMode.SHARED_PCM, null, null)
            allowOpen.countDown()
            owner.awaitIdle()

            assertEquals(listOf(71L), effects.closedSessionIds)
            assertNull(owner.facts.value.sessionId)
            assertFalse(owner.facts.value.exclusive)
        }
    }

    @Test
    fun unrelatedDetachOnlyAdvancesDiscoveryRevision() {
        val effects = RecordingEffects()
        UsbHybridSessionOwner(effects).use { owner ->
            val epoch = owner.request(UsbExclusiveMode.USB_EXACT_PCM, sk02, runtimeA)
            effects.awaitPermissionRequest()
            owner.onPermissionResult(
                UsbPermissionResult(epoch, UsbExclusiveMode.USB_EXACT_PCM, sk02, runtimeA, granted = true),
            )
            owner.awaitIdle()
            owner.requestOpen(epoch, UsbStreamFormat.Pcm(96_000, 2, 32))
            owner.awaitIdle()

            owner.onDetached(UsbRuntimeHandle(99, "/dev/bus/usb/001/099"))
            owner.awaitIdle()

            assertEquals(epoch, owner.currentEpoch())
            assertEquals(1L, owner.currentDiscoveryRevision().value)
            assertEquals(71L, owner.facts.value.sessionId)
        }
    }

    @Test
    fun failedReconfigureRetiresPreviousSessionAndClearsActiveFacts() {
        val effects = RecordingEffects()
        UsbHybridSessionOwner(effects).use { owner ->
            val epoch = owner.request(UsbExclusiveMode.USB_DOP, sk02, runtimeA)
            effects.awaitPermissionRequest()
            owner.onPermissionResult(
                UsbPermissionResult(epoch, UsbExclusiveMode.USB_DOP, sk02, runtimeA, granted = true),
            )
            owner.awaitIdle()
            owner.requestOpen(epoch, UsbStreamFormat.Dsd(2_822_400, 2, native = false)).get()

            effects.nextOpenResult = UsbOpenResult(
                failure = UsbFailure("DOP_OPEN_FAILED", "DSD256 exceeds the verified DoP limit."),
            )
            val failed = owner.requestOpen(
                epoch,
                UsbStreamFormat.Dsd(11_289_600, 2, native = false),
            ).get()

            assertEquals(listOf(71L), effects.closedSessionIds)
            assertNull(failed.activeMode)
            assertNull(failed.sessionId)
            assertFalse(failed.claimed)
            assertFalse(failed.exclusive)
            assertFalse(failed.transportExact)
            assertFalse(failed.signalExact)
            assertNull(failed.streamFormat)
            assertEquals("DOP_OPEN_FAILED", failed.failure?.code)
        }
    }

    @Test
    fun simultaneousRequestsPublishMonotonicEpochsToNativeAndFacts() {
        val effects = RecordingEffects()
        UsbHybridSessionOwner(effects).use { owner ->
            val first = AtomicReference<UsbRequestEpoch>()
            val second = AtomicReference<UsbRequestEpoch>()
            val start = CountDownLatch(1)
            val a = thread { start.await(); first.set(owner.request(UsbExclusiveMode.SHARED_PCM, null, null)) }
            val b = thread { start.await(); second.set(owner.request(UsbExclusiveMode.SHARED_PCM, null, null)) }
            start.countDown()
            a.join()
            b.join()
            owner.awaitIdle()

            assertEquals(listOf(1L, 2L), effects.publishedEpochs)
            assertEquals(2L, owner.facts.value.requestEpoch)
            assertEquals(setOf(1L, 2L), setOf(first.get().value, second.get().value))
        }
    }

    @Test
    fun staleTelemetrySampleCannotPublishAfterNewEpoch() {
        val effects = RecordingEffects()
        UsbHybridSessionOwner(effects).use { owner ->
            val epoch = owner.request(UsbExclusiveMode.USB_EXACT_PCM, sk02, runtimeA)
            effects.awaitPermissionRequest()
            owner.onPermissionResult(
                UsbPermissionResult(epoch, UsbExclusiveMode.USB_EXACT_PCM, sk02, runtimeA, true),
            )
            owner.awaitIdle()
            owner.requestOpen(epoch, UsbStreamFormat.Pcm(48_000, 2, 16))
            owner.awaitIdle()

            val sampled = CountDownLatch(1)
            val releaseSample = CountDownLatch(1)
            owner.refreshTelemetry(object : UsbHybridRealtimePort {
                override fun writePcm(sessionId: UsbTransportSessionId, data: ByteArray) = null
                override fun finishPcm(sessionId: UsbTransportSessionId) = null
                override fun resetPcmForSeek(sessionId: UsbTransportSessionId) = Unit
                override fun telemetry(sessionId: UsbTransportSessionId): UsbRealtimeTelemetry {
                    sampled.countDown()
                    releaseSample.await(2, TimeUnit.SECONDS)
                    return UsbRealtimeTelemetry(1, 2, 3, 4)
                }
                override fun writeDsd(sessionId: UsbTransportSessionId, data: ByteArray) = "not-used"
                override fun prepareDsdSeek(sessionId: UsbTransportSessionId) = "not-used"
                override fun pauseDsd(sessionId: UsbTransportSessionId) = "not-used"
                override fun resumeDsd(sessionId: UsbTransportSessionId) = "not-used"
                override fun finishDsd(sessionId: UsbTransportSessionId) = "not-used"
            })
            assertTrue(sampled.await(2, TimeUnit.SECONDS))
            owner.request(UsbExclusiveMode.SHARED_PCM, null, null)
            releaseSample.countDown()
            owner.awaitIdle()

            assertNull(owner.facts.value.telemetry)
            assertEquals(UsbExclusiveMode.SHARED_PCM, owner.facts.value.requestedMode)
        }
    }

    private class RecordingEffects(
        private val openStarted: CountDownLatch? = null,
        private val allowOpen: CountDownLatch? = null,
    ) : UsbHybridControlEffects {
        private val permissionRequested = CountDownLatch(1)
        val publishedEpochs = mutableListOf<Long>()
        val closedSessionIds = mutableListOf<Long>()
        @Volatile var openCount = 0
        @Volatile var nextOpenResult: UsbOpenResult? = null

        override fun publishActiveEpoch(epoch: UsbRequestEpoch) {
            synchronized(publishedEpochs) { publishedEpochs += epoch.value }
        }

        override fun requestPermission(request: UsbPermissionRequest) {
            permissionRequested.countDown()
        }

        override fun open(request: UsbOpenRequest): UsbOpenResult {
            openCount += 1
            openStarted?.countDown()
            allowOpen?.await(2, TimeUnit.SECONDS)
            return nextOpenResult?.also { nextOpenResult = null }
                ?: UsbOpenResult(
                    sessionId = UsbTransportSessionId(request.epoch, 71L),
                    claimed = true,
                    transportExact = true,
                    signalExact = true,
                    streamFormat = "test",
                )
        }

        override fun close(sessionId: UsbTransportSessionId) {
            closedSessionIds += sessionId.nativeId
        }

        fun awaitPermissionRequest() {
            assertTrue(permissionRequested.await(2, TimeUnit.SECONDS))
        }
    }
}
