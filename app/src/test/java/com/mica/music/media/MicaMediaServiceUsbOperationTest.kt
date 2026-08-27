package com.mica.music.media

import androidx.media3.common.util.UnstableApi
import com.mica.music.media.usbhybrid.DesiredUsbOutput
import com.mica.music.media.usbhybrid.FrozenPlaybackIntent
import com.mica.music.media.usbhybrid.UsbActiveTransport
import com.mica.music.media.usbhybrid.UsbDeviceCandidate
import com.mica.music.media.usbhybrid.UsbOutputEvent
import com.mica.music.media.usbhybrid.UsbOutputPhase
import com.mica.music.media.usbhybrid.UsbOutputState
import com.mica.music.media.usbhybrid.UsbRuntimeHandle
import com.mica.music.media.usbhybrid.UsbStableIdentity
import com.mica.music.media.usbhybrid.UsbTopologyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MicaMediaServiceUsbOperationTest {
    private val dacRuntime = UsbRuntimeHandle(7, "/dev/bus/usb/001/007")
    private val otherRuntime = UsbRuntimeHandle(9, "/dev/bus/usb/001/009")
    private val identity = UsbStableIdentity(0x1234, 0x5678, 0x0100, "test-dac")
    private val candidate = UsbDeviceCandidate(
        identity = identity,
        runtimeHandle = dacRuntime,
        manufacturerName = "Test",
        productName = "DAC",
        hasAudioOutput = true,
    )

    @Test fun restartingSameRouteWaitInvalidatesItsPreviousTimer() {
        val service = serviceWith(routeWaiting())
        val first = service.beginOperation(4L, UsbOutputPhase.SharedRouteWaiting)
        val second = service.beginOperation(4L, UsbOutputPhase.SharedRouteWaiting)

        assertFalse(service.isCurrent(first))
        assertTrue(service.isCurrent(second))
    }

    @Test fun playIntentAndNoOpAttachDoNotCancelCurrentRouteWait() {
        val service = serviceWith(routeWaiting())
        val operation = service.beginOperation(4L, UsbOutputPhase.SharedRouteWaiting)

        service.dispatch(UsbOutputEvent.UserPlayIntentChanged(false))
        service.topology(UsbTopologyEvent.Attached)

        assertTrue(service.isCurrent(operation))
    }

    @Test fun recreatedServiceDoesNotAcceptPreviousServicesPermissionId() {
        val first = serviceWith(permissionWaiting()).beginOperation(6L, UsbOutputPhase.PermissionWaiting)
        val second = serviceWith(permissionWaiting()).beginOperation(6L, UsbOutputPhase.PermissionWaiting)

        assertNotEquals(first.operationId(), second.operationId())
    }

    @Test fun destroyedServiceRejectsItsCurrentAndFutureOperations() {
        val service = serviceWith(routeWaiting())
        val operation = service.beginOperation(4L, UsbOutputPhase.SharedRouteWaiting)

        service.setField("usbOutputDestroyed", true)

        assertFalse(service.isCurrent(operation))
        assertNull(service.beginOperationOrNull(4L, UsbOutputPhase.SharedRouteWaiting))
    }

    @Test fun oldRouteTimeoutCannotMatchReattachedWait() {
        val service = serviceWith(routeWaiting()).also { it.setField("usbOutputCandidate", candidate) }
        val old = service.beginOperation(4L, UsbOutputPhase.SharedRouteWaiting)

        service.topology(UsbTopologyEvent.Detached(dacRuntime))
        service.topology(UsbTopologyEvent.Attached)

        assertFalse(service.isCurrent(old))
        assertEquals(UsbOutputPhase.SharedRouteWaiting, service.state().phase)
    }

    @Test fun oldProbeCannotMatchReattachedTargetProbe() {
        val preparing = UsbOutputState(
            desiredMode = DesiredUsbOutput.ExactPcm,
            phase = UsbOutputPhase.ExclusivePreparing,
            generation = 8L,
            frozenIntent = FrozenPlaybackIntent(8L, true),
        )
        val service = serviceWith(preparing).also { it.setField("usbOutputCandidate", candidate) }
        val old = service.beginOperation(8L, UsbOutputPhase.ExclusivePreparing)

        service.topology(UsbTopologyEvent.Detached(dacRuntime))
        service.topology(UsbTopologyEvent.Attached)

        assertFalse(service.isCurrent(old))
        assertEquals(UsbOutputPhase.ExclusivePreparing, service.state().phase)
    }

    @Test fun activeUnrelatedDetachKeepsTargetAndPlaybackState() {
        val active = UsbOutputState(
            desiredMode = DesiredUsbOutput.ExactPcm,
            phase = UsbOutputPhase.ExclusiveActive(DesiredUsbOutput.ExactPcm),
            generation = 10L,
            frozenIntent = FrozenPlaybackIntent(10L, true),
            targetStable = true,
            permissionGranted = true,
            activeTransport = UsbActiveTransport.PCM,
            activeSessionId = 71L,
        )
        val service = serviceWith(active).also { it.setField("usbOutputCandidate", candidate) }

        service.topology(UsbTopologyEvent.Detached(otherRuntime))

        assertEquals(active, service.state())
        assertSame(candidate, service.getField("usbOutputCandidate"))
    }

    @Test fun targetDetachDisconnectsAndClearsCandidate() {
        val active = UsbOutputState(
            desiredMode = DesiredUsbOutput.ExactPcm,
            phase = UsbOutputPhase.ExclusiveActive(DesiredUsbOutput.ExactPcm),
            generation = 10L,
            frozenIntent = FrozenPlaybackIntent(10L, true),
            targetStable = true,
            permissionGranted = true,
            activeTransport = UsbActiveTransport.PCM,
            activeSessionId = 71L,
        )
        val service = serviceWith(active).also { it.setField("usbOutputCandidate", candidate) }

        service.topology(UsbTopologyEvent.Detached(dacRuntime))

        assertEquals(UsbOutputPhase.Disconnected, service.state().phase)
        assertNull(service.getField("usbOutputCandidate"))
    }

    @Test fun lateOldRuntimeDetachCannotCancelNewPermissionWait() {
        val service = serviceWith(permissionWaiting()).also { it.setField("usbOutputCandidate", candidate) }
        val operation = service.beginOperation(6L, UsbOutputPhase.PermissionWaiting)

        service.topology(UsbTopologyEvent.Detached(otherRuntime))

        assertEquals(UsbOutputPhase.PermissionWaiting, service.state().phase)
        assertTrue(service.isCurrent(operation))
    }

    @Test fun unknownTargetPreservesExistingDisconnectHandling() {
        val preparing = UsbOutputState(
            desiredMode = DesiredUsbOutput.ExactPcm,
            phase = UsbOutputPhase.ExclusivePreparing,
            generation = 12L,
        )
        val service = serviceWith(preparing)

        service.topology(UsbTopologyEvent.Detached(otherRuntime))

        assertEquals(UsbOutputPhase.Disconnected, service.state().phase)
    }

    private fun routeWaiting() = UsbOutputState(
        desiredMode = DesiredUsbOutput.Shared,
        phase = UsbOutputPhase.SharedRouteWaiting,
        generation = 4L,
        frozenIntent = FrozenPlaybackIntent(4L, true),
    )

    private fun permissionWaiting() = UsbOutputState(
        desiredMode = DesiredUsbOutput.ExactPcm,
        phase = UsbOutputPhase.PermissionWaiting,
        generation = 6L,
        frozenIntent = FrozenPlaybackIntent(6L, true),
        targetStable = true,
    )

    private fun serviceWith(state: UsbOutputState) = MicaMediaService().also {
        it.setField("usbOutputState", state)
    }

    private fun MicaMediaService.beginOperation(generation: Long, phase: UsbOutputPhase): Any =
        requireNotNull(beginOperationOrNull(generation, phase))

    private fun MicaMediaService.beginOperationOrNull(generation: Long, phase: UsbOutputPhase): Any? =
        javaClass.getDeclaredMethod("beginUsbOutputOperation", Long::class.javaPrimitiveType, UsbOutputPhase::class.java)
            .apply { isAccessible = true }
            .invoke(this, generation, phase)

    private fun MicaMediaService.isCurrent(operation: Any): Boolean =
        javaClass.getDeclaredMethod("isCurrentUsbOutputOperation", operation.javaClass)
            .apply { isAccessible = true }
            .invoke(this, operation) as Boolean

    private fun Any.operationId(): Long = javaClass.getDeclaredField("id")
        .apply { isAccessible = true }
        .getLong(this)

    private fun MicaMediaService.dispatch(event: UsbOutputEvent) {
        javaClass.getDeclaredMethod("dispatchUsbOutput", UsbOutputEvent::class.java)
            .apply { isAccessible = true }
            .invoke(this, event)
    }

    private fun MicaMediaService.topology(event: UsbTopologyEvent) {
        javaClass.getDeclaredMethod("onUsbTopologyEvent", UsbTopologyEvent::class.java)
            .apply { isAccessible = true }
            .invoke(this, event)
    }

    private fun MicaMediaService.state(): UsbOutputState = getField("usbOutputState") as UsbOutputState

    private fun Any.setField(name: String, value: Any?) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun Any.getField(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
}
