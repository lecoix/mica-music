package com.mica.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetHostStateTest {

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
