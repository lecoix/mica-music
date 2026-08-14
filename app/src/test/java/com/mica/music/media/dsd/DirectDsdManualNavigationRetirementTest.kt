package com.mica.music.media.dsd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDsdManualNavigationRetirementTest {
    @Test
    fun pausedManualRetirementSuppressesGapButPreservesPausedReleaseHistory() {
        val coordinator = DirectDsdTrackTransitionCoordinator()
        coordinator.beforeDirectAccept(isPlaying = true)
        val bridge = ManualNavigationTransitionBridge()
        bridge.publish(
            targetMediaId = "B",
            requestedPlaying = false,
            sourceFamily = DirectDsdTrackTransportFamily.DOP,
        )
        val renderer = renderer(coordinator, bridge)

        invoke(renderer, "onStopped")
        invoke(renderer, "onDisabled")

        val snapshot = coordinator.snapshot()
        assertEquals(DirectDsdTrackTransportFamily.NONE, snapshot.activeFamily)
        assertEquals(DirectDsdTrackTransportFamily.DOP, snapshot.lastReleasedFamily)
        assertTrue(snapshot.lastReleasedWasPaused)
        assertTrue(coordinator.shouldDeferPcmUntilResume())
        assertTrue(bridge.snapshot() != null)
    }

    @Test
    fun playingManualRetirementPreservesPlayingReleaseHistory() {
        val coordinator = DirectDsdTrackTransitionCoordinator()
        coordinator.beforeDirectAccept(isPlaying = true)
        val bridge = ManualNavigationTransitionBridge()
        bridge.publish(
            targetMediaId = "B",
            requestedPlaying = true,
            sourceFamily = DirectDsdTrackTransportFamily.DOP,
        )
        val renderer = renderer(coordinator, bridge)

        invoke(renderer, "onStopped")
        invoke(renderer, "onDisabled")

        val snapshot = coordinator.snapshot()
        assertEquals(DirectDsdTrackTransportFamily.DOP, snapshot.lastReleasedFamily)
        assertFalse(snapshot.lastReleasedWasPaused)
        assertFalse(coordinator.shouldDeferPcmUntilResume())
        assertTrue(bridge.snapshot() != null)
    }

    private fun renderer(
        coordinator: DirectDsdTrackTransitionCoordinator,
        bridge: ManualNavigationTransitionBridge,
    ) = DirectDsdMedia3Renderer(
        sessionFactory = DirectDsdTransportSessionFactory {
            error("manual retirement must not open a Direct runtime")
        },
        transitionCoordinator = coordinator,
        manualNavigationTransitionBridge = bridge,
    )

    private fun invoke(renderer: DirectDsdMedia3Renderer, methodName: String) {
        val method = DirectDsdMedia3Renderer::class.java.getDeclaredMethod(methodName)
        method.isAccessible = true
        method.invoke(renderer)
    }
}
