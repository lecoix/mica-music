package com.mica.music.media.usbhybrid

import com.mica.music.media.PlaybackOutputAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbOutputPresentationTest {
    @Test
    fun internalPhasesCollapseToStablePresentationStates() {
        val cases = mapOf(
            UsbOutputPhase.SharedActive to PlaybackOutputAvailability.STABLE,
            UsbOutputPhase.ExclusiveActive(DesiredUsbOutput.ExactPcm) to PlaybackOutputAvailability.STABLE,
            UsbOutputPhase.SharedQuiescing to PlaybackOutputAvailability.SWITCHING,
            UsbOutputPhase.ExclusivePreparing to PlaybackOutputAvailability.SWITCHING,
            UsbOutputPhase.ExclusiveOpening to PlaybackOutputAvailability.SWITCHING,
            UsbOutputPhase.SharedRouteWaiting to PlaybackOutputAvailability.SWITCHING,
            UsbOutputPhase.PermissionWaiting to PlaybackOutputAvailability.WAITING_FOR_PERMISSION,
            UsbOutputPhase.Disconnected to PlaybackOutputAvailability.WAITING_FOR_DEVICE,
            UsbOutputPhase.SharedReconnectRequired to PlaybackOutputAvailability.RECONNECT_REQUIRED,
            UsbOutputPhase.Failed("OPEN_FAILED", "failed") to PlaybackOutputAvailability.FAILED,
        )

        cases.forEach { (phase, expected) ->
            assertEquals(phase.toString(), expected, phase.toPlaybackOutputAvailability())
        }
    }
}
