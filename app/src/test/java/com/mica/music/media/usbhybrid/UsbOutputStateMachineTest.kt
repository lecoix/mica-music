package com.mica.music.media.usbhybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbOutputStateMachineTest {
    private fun reduce(state: UsbOutputState, event: UsbOutputEvent) = UsbOutputStateMachine.reduce(state, event)

    @Test fun earlyNativeSelectionWaitsForTarget() {
        val selected = reduce(UsbOutputState(), UsbOutputEvent.UserSelected(DesiredUsbOutput.NativeDsd, true))
        assertEquals(UsbOutputPhase.SharedQuiescing, selected.state.phase)
        assertTrue(UsbOutputEffect.WaitForSharedQuiescence in selected.effects)

        val quiesced = reduce(selected.state, UsbOutputEvent.SharedQuiesced(selected.state.generation))
        assertEquals(UsbOutputPhase.ExclusivePreparing, quiesced.state.phase)
        assertTrue(UsbOutputEffect.WaitForTarget in quiesced.effects)

        val ready = reduce(quiesced.state, UsbOutputEvent.TargetStable)
        assertEquals(UsbOutputPhase.PermissionWaiting, ready.state.phase)
        assertTrue(UsbOutputEffect.RequestPermission in ready.effects)
    }

    @Test fun transportOwnedNativeEntryActivatesWithoutAppLayerWarmupOrReopen() {
        var state = reduce(UsbOutputState(), UsbOutputEvent.UserSelected(DesiredUsbOutput.NativeDsd, true)).state
        state = reduce(state, UsbOutputEvent.SharedQuiesced(state.generation)).state
        state = reduce(state, UsbOutputEvent.TargetStable).state
        state = reduce(state, UsbOutputEvent.PermissionResult(state.generation, true)).state

        val opened = reduce(
            state,
            UsbOutputEvent.ExclusiveOpenSucceeded(
                state.generation,
                UsbActiveTransport.NATIVE_DSD,
                sessionId = 41L,
            ),
        )

        assertEquals(UsbOutputPhase.ExclusiveActive(DesiredUsbOutput.NativeDsd), opened.state.phase)
        assertEquals(41L, opened.state.activeSessionId)
        assertEquals(listOf(UsbOutputEffect.RestoreFrozenPlaybackIntent), opened.effects)
    }

    @Test fun activeNativeSessionChangeUpdatesFactsWithoutRestartEffect() {
        val active = UsbOutputState(
            desiredMode = DesiredUsbOutput.NativeDsd,
            phase = UsbOutputPhase.ExclusiveActive(DesiredUsbOutput.NativeDsd),
            generation = 7L,
            frozenIntent = FrozenPlaybackIntent(7L, true),
            targetStable = true,
            permissionGranted = true,
            activeTransport = UsbActiveTransport.NATIVE_DSD,
            activeSessionId = 41L,
        )

        val changed = reduce(
            active,
            UsbOutputEvent.ExclusiveOpenSucceeded(7L, UsbActiveTransport.NATIVE_DSD, sessionId = 42L),
        )

        assertEquals(UsbOutputPhase.ExclusiveActive(DesiredUsbOutput.NativeDsd), changed.state.phase)
        assertEquals(42L, changed.state.activeSessionId)
        assertTrue(changed.effects.isEmpty())
    }

    @Test fun attachDoesNotRestartAnExclusiveSessionThatAlreadyOwnsItsTarget() {
        val active = UsbOutputState(
            desiredMode = DesiredUsbOutput.ExactPcm,
            phase = UsbOutputPhase.ExclusiveActive(DesiredUsbOutput.ExactPcm),
            generation = 8L,
            frozenIntent = FrozenPlaybackIntent(8L, true),
            targetStable = true,
            permissionGranted = true,
            activeTransport = UsbActiveTransport.PCM,
            activeSessionId = 52L,
        )

        val attached = reduce(active, UsbOutputEvent.UsbAttached)

        assertEquals(active, attached.state)
        assertTrue(attached.effects.isEmpty())

        listOf(UsbOutputPhase.PermissionWaiting, UsbOutputPhase.ExclusiveOpening).forEach { phase ->
            val transition = active.copy(phase = phase, activeTransport = null, activeSessionId = null)
            val unchanged = reduce(transition, UsbOutputEvent.UsbAttached)
            assertEquals(transition, unchanged.state)
            assertTrue(unchanged.effects.isEmpty())
        }
    }

    @Test fun stalePermissionCallbackCannotAdvanceNewGeneration() {
        val first = reduce(UsbOutputState(), UsbOutputEvent.UserSelected(DesiredUsbOutput.NativeDsd, true)).state
        val prepared = reduce(first, UsbOutputEvent.SharedQuiesced(first.generation)).state
        val waiting = reduce(prepared, UsbOutputEvent.TargetStable).state
        val switched = reduce(waiting, UsbOutputEvent.UserSelected(DesiredUsbOutput.Dop, true)).state
        val stale = reduce(switched, UsbOutputEvent.PermissionResult(waiting.generation, true))
        assertEquals(switched, stale.state)
        assertTrue(stale.effects.isEmpty())
    }

    @Test fun reconnectRequiredSharedReturnWaitsForReconnectAndRoute() {
        val active = UsbOutputState(
            DesiredUsbOutput.NativeDsd,
            UsbOutputPhase.ExclusiveActive(DesiredUsbOutput.NativeDsd),
            5L,
            FrozenPlaybackIntent(5L, true),
            targetStable = true,
            permissionGranted = true,
            activeTransport = UsbActiveTransport.NATIVE_DSD,
            activeSessionId = 33L,
        )
        val off = reduce(
            active,
            UsbOutputEvent.UserSelected(DesiredUsbOutput.Shared, true, sharedReturnRequiresReconnect = true),
        )
        assertEquals(UsbOutputPhase.SharedReconnectRequired, off.state.phase)
        assertTrue(UsbOutputEffect.CloseExclusive in off.effects)

        val detached = reduce(off.state, UsbOutputEvent.UsbDetached(true))
        assertEquals(UsbOutputPhase.Disconnected, detached.state.phase)
        val attached = reduce(detached.state, UsbOutputEvent.UsbAttached)
        assertEquals(UsbOutputPhase.SharedRouteWaiting, attached.state.phase)
        assertEquals(listOf(UsbOutputEffect.WaitForSharedRoute), attached.effects)
        val route = reduce(attached.state, UsbOutputEvent.AndroidSharedRouteReady)
        assertEquals(UsbOutputPhase.SharedActive, route.state.phase)
        assertTrue(UsbOutputEffect.BuildSharedPlayer in route.effects)
    }

    @Test fun automaticSharedReturnWaitsForAndroidRouteBeforeBuildingSharedPlayer() {
        val active = UsbOutputState(
            desiredMode = DesiredUsbOutput.ExactPcm,
            phase = UsbOutputPhase.ExclusiveActive(DesiredUsbOutput.ExactPcm),
            generation = 9L,
            frozenIntent = FrozenPlaybackIntent(9L, true),
            targetStable = true,
            permissionGranted = true,
            activeTransport = UsbActiveTransport.PCM,
            activeSessionId = 51L,
        )

        val off = reduce(
            active,
            UsbOutputEvent.UserSelected(
                DesiredUsbOutput.Shared,
                true,
                sharedReturnRequiresReconnect = false,
            ),
        )

        assertEquals(UsbOutputPhase.SharedRouteWaiting, off.state.phase)
        assertEquals(
            listOf(
                UsbOutputEffect.CloseExclusive,
                UsbOutputEffect.RetirePlayer,
                UsbOutputEffect.WaitForSharedRoute,
            ),
            off.effects,
        )
        assertFalse(UsbOutputEffect.BuildSharedPlayer in off.effects)

        val ready = reduce(off.state, UsbOutputEvent.AndroidSharedRouteReady)
        assertEquals(UsbOutputPhase.SharedActive, ready.state.phase)
        assertEquals(listOf(UsbOutputEffect.BuildSharedPlayer), ready.effects)
    }

    @Test fun automaticSharedReturnTimeoutRequiresPhysicalReconnect() {
        val waiting = UsbOutputState(
            desiredMode = DesiredUsbOutput.Shared,
            phase = UsbOutputPhase.SharedRouteWaiting,
            generation = 12L,
            frozenIntent = FrozenPlaybackIntent(12L, false),
        )

        val timeout = reduce(waiting, UsbOutputEvent.AndroidSharedRouteUnavailable)

        assertEquals(UsbOutputPhase.SharedReconnectRequired, timeout.state.phase)
        assertTrue(timeout.state.sharedReturnRequiresReconnect)
        assertEquals(listOf(UsbOutputEffect.ShowReconnectRequired), timeout.effects)
    }

    @Test fun nativeDuringSharedRouteWaitCancelsLateSharedBuild() {
        val waiting = UsbOutputState(
            DesiredUsbOutput.Shared,
            UsbOutputPhase.SharedRouteWaiting,
            3L,
            FrozenPlaybackIntent(3L, true),
        )
        val native = reduce(waiting, UsbOutputEvent.UserSelected(DesiredUsbOutput.NativeDsd, true))
        val lateRoute = reduce(native.state, UsbOutputEvent.AndroidSharedRouteReady)
        assertEquals(UsbOutputPhase.ExclusivePreparing, lateRoute.state.phase)
        assertTrue(lateRoute.effects.isEmpty())
    }

    @Test fun transientOwnershipRaceReturnsToPrepareWithBoundedRetry() {
        var state = reduce(UsbOutputState(), UsbOutputEvent.UserSelected(DesiredUsbOutput.NativeDsd, true)).state
        state = reduce(state, UsbOutputEvent.SharedQuiesced(state.generation)).state
        state = reduce(state, UsbOutputEvent.TargetStable).state
        state = reduce(state, UsbOutputEvent.PermissionResult(state.generation, true)).state

        val failed = reduce(
            state,
            UsbOutputEvent.ExclusiveOpenFailed(
                state.generation,
                "OPEN_FAILED",
                "USBDEVFS_SUBMITURB ENOENT",
                transientOwnershipRace = true,
            ),
        )
        assertEquals(UsbOutputPhase.ExclusivePreparing, failed.state.phase)
        assertEquals(1, failed.state.openAttempt)
        assertTrue(UsbOutputEffect.WaitForTarget in failed.effects)
    }

    @Test fun frozenPlaybackIntentRestoresOnlyWhenOpeningBecomesActive() {
        var state = reduce(UsbOutputState(), UsbOutputEvent.UserSelected(DesiredUsbOutput.NativeDsd, true)).state
        val generation = state.generation
        state = reduce(state, UsbOutputEvent.SharedQuiesced(generation)).state
        state = reduce(state, UsbOutputEvent.TargetStable).state
        state = reduce(state, UsbOutputEvent.PermissionResult(generation, true)).state
        state = reduce(state, UsbOutputEvent.UserPlayIntentChanged(false)).state

        val opened = reduce(
            state,
            UsbOutputEvent.ExclusiveOpenSucceeded(generation, UsbActiveTransport.NATIVE_DSD, 9L),
        )
        assertEquals(false, opened.state.frozenIntent?.playWhenReady)
        assertEquals(listOf(UsbOutputEffect.RestoreFrozenPlaybackIntent), opened.effects)

        val sameSession = reduce(
            opened.state,
            UsbOutputEvent.ExclusiveOpenSucceeded(generation, UsbActiveTransport.NATIVE_DSD, 9L),
        )
        assertTrue(sameSession.effects.isEmpty())
        assertFalse(sameSession.state.phase is UsbOutputPhase.Failed)
    }
}
