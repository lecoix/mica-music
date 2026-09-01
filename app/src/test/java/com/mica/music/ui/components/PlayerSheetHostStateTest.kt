package com.mica.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetHostStateTest {

    @Test
    fun onlyFullyCoveredSteadyPlayerCanPauseUnderlay() {
        assertTrue(playerSheetUnderlayOccluded(true, 1f, null))
        assertFalse(playerSheetUnderlayOccluded(true, 0.999f, null))
        assertFalse(playerSheetUnderlayOccluded(false, 1f, null))
        assertFalse(playerSheetUnderlayOccluded(false, 0f, null))
        // The first predictive-back event must wake content before it becomes exposed.
        assertFalse(playerSheetUnderlayOccluded(true, 1f, 0f))
        assertFalse(playerSheetUnderlayOccluded(true, 0.7f, 0.3f))
        // Cancelling the gesture can pause again only after returning to the endpoint.
        assertFalse(playerSheetUnderlayOccluded(true, 0.7f, null))
        assertTrue(playerSheetUnderlayOccluded(true, 1f, null))
    }

    @Test
    fun externalCloseKeepsOverlayOpenUntilClosingFinishes() {
        val phase = playerSheetPhaseForExternalExpanded(
            current = PlayerSheetPhase.Expanded,
            expanded = false,
            progress = 1f,
        )

        assertEquals(PlayerSheetPhase.Closing, phase)
        assertTrue(phase.keepsOverlayOpen(externalExpanded = false))
    }

    @Test
    fun closingSheetKeepsOverlayOpenUntilAnimationFinishes() {
        val phase = playerSheetPhaseForExternalExpanded(
            current = PlayerSheetPhase.Closing,
            expanded = false,
            progress = 0.4f,
        )

        assertEquals(PlayerSheetPhase.Closing, phase)
        assertTrue(phase.keepsOverlayOpen(externalExpanded = false))
    }

    @Test
    fun collapsedSheetDoesNotKeepOverlayOpenWhenOwnerIsCollapsed() {
        assertFalse(PlayerSheetPhase.Collapsed.keepsOverlayOpen(externalExpanded = false))
    }

    @Test
    fun predictiveBackProgressDrivesSheetClosed() {
        assertEquals(
            0.75f,
            playerSheetProgressForPredictiveBack(
                animatedProgress = 1f,
                predictiveBackProgress = 0.25f,
            ),
        )
    }

    @Test
    fun predictiveBackProgressIsClamped() {
        assertEquals(
            0f,
            playerSheetProgressForPredictiveBack(
                animatedProgress = 1f,
                predictiveBackProgress = 2f,
            ),
        )
    }
}
