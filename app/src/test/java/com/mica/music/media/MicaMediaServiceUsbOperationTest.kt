package com.mica.music.media

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.preferences.UsbHybridOutputMode
import com.mica.music.data.preferences.UsbHybridPreferences
import com.mica.music.media.usbhybrid.AndroidUsbHybridControlEffects
import com.mica.music.media.usbhybrid.DesiredUsbOutput
import com.mica.music.media.usbhybrid.FrozenPlaybackIntent
import com.mica.music.media.usbhybrid.UsbActiveTransport
import com.mica.music.media.usbhybrid.UsbDeviceCandidate
import com.mica.music.media.usbhybrid.UsbHybridPlaybackBinding
import com.mica.music.media.usbhybrid.UsbHybridSessionOwner
import com.mica.music.media.usbhybrid.UsbOutputEvent
import com.mica.music.media.usbhybrid.UsbOutputPhase
import com.mica.music.media.usbhybrid.UsbOutputState
import com.mica.music.media.usbhybrid.UsbRuntimeHandle
import com.mica.music.media.usbhybrid.UsbStableIdentity
import com.mica.music.media.usbhybrid.UsbTopologyEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    @Test fun restartingSameRouteWaitInvalidatesItsPreviousTimer() = coordinatorWith(routeWaiting()).use { fixture ->
        val first = fixture.coordinator.beginOperation(4L, UsbOutputPhase.SharedRouteWaiting)
        val second = fixture.coordinator.beginOperation(4L, UsbOutputPhase.SharedRouteWaiting)

        assertFalse(fixture.coordinator.isCurrent(first))
        assertTrue(fixture.coordinator.isCurrent(second))
    }

    @Test fun playIntentAndNoOpAttachDoNotCancelCurrentRouteWait() = coordinatorWith(routeWaiting()).use { fixture ->
        val operation = fixture.coordinator.beginOperation(4L, UsbOutputPhase.SharedRouteWaiting)

        fixture.coordinator.dispatch(UsbOutputEvent.UserPlayIntentChanged(false))
        fixture.coordinator.onTopologyEvent(UsbTopologyEvent.Attached(dacRuntime, hasAudioOutput = true))

        assertTrue(fixture.coordinator.isCurrent(operation))
    }

    @Test fun recreatedCoordinatorDoesNotAcceptPreviousPermissionId() {
        coordinatorWith(permissionWaiting()).use { firstFixture ->
            coordinatorWith(permissionWaiting()).use { secondFixture ->
                val first = firstFixture.coordinator.beginOperation(6L, UsbOutputPhase.PermissionWaiting)
                val second = secondFixture.coordinator.beginOperation(6L, UsbOutputPhase.PermissionWaiting)
                assertNotEquals(first.operationId(), second.operationId())
            }
        }
    }

    @Test fun closedCoordinatorRejectsItsCurrentAndFutureOperations() = coordinatorWith(routeWaiting()).use { fixture ->
        val operation = fixture.coordinator.beginOperation(4L, UsbOutputPhase.SharedRouteWaiting)

        fixture.coordinator.close()

        assertFalse(fixture.coordinator.isCurrent(operation))
        assertNull(fixture.coordinator.beginOperationOrNull(4L, UsbOutputPhase.SharedRouteWaiting))
    }

    @Test fun queuedUsbDispatchCannotMutateStateAfterServiceDestroy() {
        val controller = Robolectric.buildService(MicaMediaService::class.java)
        val service = controller.create().get()
        shadowOf(Looper.getMainLooper()).idle()
        val coordinator = service.coordinator()
        val beforeDestroy = routeWaiting()
        coordinator.setField("state", beforeDestroy)
        val mainHandler = service.getField("mainHandler") as Handler
        mainHandler.post {
            coordinator.submit(UsbOutputCommand.PlaybackIntentChanged(false))
        }

        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(beforeDestroy, coordinator.stateForTest())
    }

    @Test fun queuedUsbPreferenceCannotRewriteBootstrapModeAfterServiceDestroy() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val previousMode = UsbHybridPreferences.outputMode(application)
        val queuedMode = if (previousMode != UsbHybridOutputMode.ExactPcm) {
            UsbHybridOutputMode.ExactPcm
        } else {
            UsbHybridOutputMode.Dop
        }
        val controller = Robolectric.buildService(MicaMediaService::class.java)
        var destroyed = false

        try {
            val service = controller.create().get()
            shadowOf(Looper.getMainLooper()).idle()
            service.setField("usbServiceCreateBootstrapMode", UsbHybridOutputMode.SharedPcm)
            UsbHybridPreferences.setOutputMode(application, queuedMode)

            controller.destroy()
            destroyed = true
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                UsbHybridOutputMode.SharedPcm,
                service.getField("usbServiceCreateBootstrapMode"),
            )
        } finally {
            if (!destroyed) controller.destroy()
            UsbHybridPreferences.setOutputMode(application, previousMode)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun applyUsbOutputModeCannotWriteCoordinatorStateAfterServiceDestroy() {
        val controller = Robolectric.buildService(MicaMediaService::class.java)
        val service = controller.create().get()
        shadowOf(Looper.getMainLooper()).idle()
        val coordinator = service.coordinator()
        val beforeDestroy = routeWaiting()
        coordinator.setField("state", beforeDestroy)
        coordinator.setField("switchReason", "before-destroy")

        controller.destroy()
        service.applyMode(UsbHybridOutputMode.Dop, "late-preference")

        assertEquals(beforeDestroy, coordinator.stateForTest())
        assertEquals("before-destroy", coordinator.getField("switchReason"))
        assertNull(coordinator.getField("outputHandoff"))
    }

    @Test fun queuedAudioDeviceCallbackCannotWriteSerialAfterServiceDestroy() {
        val controller = Robolectric.buildService(MicaMediaService::class.java)
        val service = controller.create().get()
        shadowOf(Looper.getMainLooper()).idle()
        val coordinator = service.coordinator()
        coordinator.setField("sharedAudioAddSerial", 17L)
        val callback = service.getField("usbSharedAudioDeviceCallback") as AudioDeviceCallback
        val mainHandler = service.getField("mainHandler") as Handler
        val usbAudioDevice = mockk<AudioDeviceInfo>()
        every { usbAudioDevice.isSink } returns true
        every { usbAudioDevice.type } returns AudioDeviceInfo.TYPE_USB_DEVICE
        every { usbAudioDevice.id } returns 99
        mainHandler.post { callback.onAudioDevicesAdded(arrayOf(usbAudioDevice)) }

        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(17L, coordinator.getField("sharedAudioAddSerial"))
    }

    @Test fun oldRouteTimeoutCannotMatchReattachedWait() = coordinatorWith(routeWaiting()).use { fixture ->
        fixture.coordinator.setField("candidate", candidate)
        val old = fixture.coordinator.beginOperation(4L, UsbOutputPhase.SharedRouteWaiting)

        fixture.coordinator.onTopologyEvent(UsbTopologyEvent.Detached(dacRuntime))
        fixture.coordinator.onTopologyEvent(UsbTopologyEvent.Attached(dacRuntime, hasAudioOutput = true))

        assertFalse(fixture.coordinator.isCurrent(old))
        assertEquals(UsbOutputPhase.SharedRouteWaiting, fixture.coordinator.stateForTest().phase)
    }

    @Test fun oldProbeCannotMatchReattachedTargetProbe() {
        val preparing = UsbOutputState(
            desiredMode = DesiredUsbOutput.ExactPcm,
            phase = UsbOutputPhase.ExclusivePreparing,
            generation = 8L,
            frozenIntent = FrozenPlaybackIntent(8L, true),
        )
        coordinatorWith(preparing).use { fixture ->
            fixture.coordinator.setField("candidate", candidate)
            val old = fixture.coordinator.beginOperation(8L, UsbOutputPhase.ExclusivePreparing)

            fixture.coordinator.onTopologyEvent(UsbTopologyEvent.Detached(dacRuntime))
            fixture.coordinator.onTopologyEvent(UsbTopologyEvent.Attached(dacRuntime, hasAudioOutput = true))

            assertFalse(fixture.coordinator.isCurrent(old))
            assertEquals(UsbOutputPhase.ExclusivePreparing, fixture.coordinator.stateForTest().phase)
        }
    }

    @Test fun activeUnrelatedDetachKeepsTargetAndPlaybackState() {
        val active = exclusiveActive(generation = 10L, sessionId = 71L)
        coordinatorWith(active).use { fixture ->
            fixture.coordinator.setField("candidate", candidate)

            fixture.coordinator.onTopologyEvent(UsbTopologyEvent.Detached(otherRuntime))

            assertEquals(active, fixture.coordinator.stateForTest())
            assertSame(candidate, fixture.coordinator.getField("candidate"))
        }
    }

    @Test fun nonAudioAttachDoesNotRestartAnExclusiveTargetProbe() {
        val preparing = UsbOutputState(
            desiredMode = DesiredUsbOutput.ExactPcm,
            phase = UsbOutputPhase.ExclusivePreparing,
            generation = 11L,
            frozenIntent = FrozenPlaybackIntent(11L, true),
        )
        coordinatorWith(preparing).use { fixture ->
            val operation = fixture.coordinator.beginOperation(11L, UsbOutputPhase.ExclusivePreparing)

            fixture.coordinator.onTopologyEvent(UsbTopologyEvent.Attached(otherRuntime, hasAudioOutput = false))

            assertEquals(preparing, fixture.coordinator.stateForTest())
            assertTrue(fixture.coordinator.isCurrent(operation))
        }
    }

    @Test fun activeUnrelatedAudioAttachKeepsTargetAndPlaybackState() {
        val active = exclusiveActive(generation = 12L, sessionId = 72L)
        coordinatorWith(active).use { fixture ->
            fixture.coordinator.setField("candidate", candidate)

            fixture.coordinator.onTopologyEvent(UsbTopologyEvent.Attached(otherRuntime, hasAudioOutput = true))

            assertEquals(active, fixture.coordinator.stateForTest())
            assertSame(candidate, fixture.coordinator.getField("candidate"))
        }
    }

    @Test fun unrelatedAudioAttachDoesNotReplacePermissionWaitOperation() = coordinatorWith(permissionWaiting()).use { fixture ->
        fixture.coordinator.setField("candidate", candidate)
        val operation = fixture.coordinator.beginOperation(6L, UsbOutputPhase.PermissionWaiting)

        fixture.coordinator.onTopologyEvent(UsbTopologyEvent.Attached(otherRuntime, hasAudioOutput = true))

        assertEquals(UsbOutputPhase.PermissionWaiting, fixture.coordinator.stateForTest().phase)
        assertTrue(fixture.coordinator.isCurrent(operation))
    }

    @Test fun targetDetachDisconnectsAndClearsCandidate() {
        val active = exclusiveActive(generation = 10L, sessionId = 71L)
        coordinatorWith(active).use { fixture ->
            fixture.coordinator.setField("candidate", candidate)

            fixture.coordinator.onTopologyEvent(UsbTopologyEvent.Detached(dacRuntime))

            assertEquals(UsbOutputPhase.Disconnected, fixture.coordinator.stateForTest().phase)
            assertNull(fixture.coordinator.getField("candidate"))
        }
    }

    @Test fun lateOldRuntimeDetachCannotCancelNewPermissionWait() = coordinatorWith(permissionWaiting()).use { fixture ->
        fixture.coordinator.setField("candidate", candidate)
        val operation = fixture.coordinator.beginOperation(6L, UsbOutputPhase.PermissionWaiting)

        fixture.coordinator.onTopologyEvent(UsbTopologyEvent.Detached(otherRuntime))

        assertEquals(UsbOutputPhase.PermissionWaiting, fixture.coordinator.stateForTest().phase)
        assertTrue(fixture.coordinator.isCurrent(operation))
    }

    @Test fun unknownTargetPreservesExistingDisconnectHandling() {
        val preparing = UsbOutputState(
            desiredMode = DesiredUsbOutput.ExactPcm,
            phase = UsbOutputPhase.ExclusivePreparing,
            generation = 12L,
        )
        coordinatorWith(preparing).use { fixture ->
            fixture.coordinator.onTopologyEvent(UsbTopologyEvent.Detached(otherRuntime))
            assertEquals(UsbOutputPhase.Disconnected, fixture.coordinator.stateForTest().phase)
        }
    }

    private fun exclusiveActive(generation: Long, sessionId: Long) = UsbOutputState(
        desiredMode = DesiredUsbOutput.ExactPcm,
        phase = UsbOutputPhase.ExclusiveActive(DesiredUsbOutput.ExactPcm),
        generation = generation,
        frozenIntent = FrozenPlaybackIntent(generation, true),
        targetStable = true,
        permissionGranted = true,
        activeTransport = UsbActiveTransport.PCM,
        activeSessionId = sessionId,
    )

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

    private fun coordinatorWith(state: UsbOutputState): CoordinatorFixture {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        lateinit var coordinator: DefaultUsbOutputCoordinator
        val effects = AndroidUsbHybridControlEffects(
            application,
            permissionResultSink = { result -> coordinator.onPermissionResult(result) },
            topologyEventSink = { event -> coordinator.onTopologyEvent(event) },
        )
        val owner = UsbHybridSessionOwner(effects)
        coordinator = DefaultUsbOutputCoordinator(
            context = application,
            mainHandler = Handler(Looper.getMainLooper()),
            effects = effects,
            owner = owner,
            playback = FakePlaybackPort(),
        )
        coordinator.setField("state", state)
        return CoordinatorFixture(coordinator, owner, effects)
    }

    private data class CoordinatorFixture(
        val coordinator: DefaultUsbOutputCoordinator,
        val owner: UsbHybridSessionOwner,
        val effects: AndroidUsbHybridControlEffects,
    ) : AutoCloseable {
        override fun close() {
            coordinator.close()
            owner.close()
            effects.close()
        }
    }

    private class FakePlaybackPort : UsbOutputPlaybackPort {
        override fun captureHandoff(): UsbPlaybackStackHandoff? = null
        override fun hasPlaybackStack(): Boolean = false
        override fun isSharedOutputActive(): Boolean = true
        override fun currentPlayWhenReady(): Boolean = false
        override fun currentAudioSessionId(): Int? = null
        override fun retireBeforeUsbRequest() = Unit
        override fun rebuildShared(handoff: UsbPlaybackStackHandoff?, reason: String) = Unit
        override fun rebuildExclusive(
            mode: DesiredUsbOutput,
            binding: UsbHybridPlaybackBinding,
            handoff: UsbPlaybackStackHandoff?,
            reason: String,
        ) = Unit
        override fun restorePlaybackIntent(playWhenReady: Boolean) = Unit
    }

    private fun MicaMediaService.coordinator(): DefaultUsbOutputCoordinator =
        getField("usbOutputCoordinator") as DefaultUsbOutputCoordinator

    private fun DefaultUsbOutputCoordinator.beginOperation(generation: Long, phase: UsbOutputPhase): Any =
        requireNotNull(beginOperationOrNull(generation, phase))

    private fun DefaultUsbOutputCoordinator.beginOperationOrNull(generation: Long, phase: UsbOutputPhase): Any? =
        javaClass.getDeclaredMethod("beginOperation", Long::class.javaPrimitiveType, UsbOutputPhase::class.java)
            .apply { isAccessible = true }
            .invoke(this, generation, phase)

    private fun DefaultUsbOutputCoordinator.isCurrent(operation: Any): Boolean =
        javaClass.getDeclaredMethod("isCurrentOperation", operation.javaClass)
            .apply { isAccessible = true }
            .invoke(this, operation) as Boolean

    private fun Any.operationId(): Long = javaClass.getDeclaredField("id")
        .apply { isAccessible = true }
        .getLong(this)

    private fun DefaultUsbOutputCoordinator.dispatch(event: UsbOutputEvent) {
        javaClass.getDeclaredMethod("dispatch", UsbOutputEvent::class.java)
            .apply { isAccessible = true }
            .invoke(this, event)
    }

    private fun MicaMediaService.applyMode(mode: UsbHybridOutputMode, reason: String) {
        javaClass.getDeclaredMethod(
            "applyUsbOutputMode",
            UsbHybridOutputMode::class.java,
            String::class.java,
        ).apply { isAccessible = true }
            .invoke(this, mode, reason)
    }

    private fun Any.setField(name: String, value: Any?) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun Any.getField(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
}
