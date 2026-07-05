package com.mica.music.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationCoordinatorTest {

    @Test
    fun playerExpandedGivesBackOwnershipToPlayerOverlay() {
        assertTrue(
            playerOverlayOwnsBack(
                playerExpanded = true,
                overlayFullScreen = false,
            ),
        )
    }

    @Test
    fun closingOverlayKeepsBackOwnershipUntilAnimationFinishes() {
        assertTrue(
            playerOverlayOwnsBack(
                playerExpanded = false,
                overlayFullScreen = true,
            ),
        )
    }

    @Test
    fun settingsBackCanHandleWhenPlayerOverlayIsClear() {
        assertFalse(
            playerOverlayOwnsBack(
                playerExpanded = false,
                overlayFullScreen = false,
            ),
        )
    }
}
