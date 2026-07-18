package com.mica.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingCoverWipeOverlayTest {
    @Test
    fun targetChangeBeforeEffectRendersAsProgressZero() {
        assertEquals(
            0f,
            playerCoverWipeRenderProgress(
                visibleSongId = "old",
                targetSongId = "new",
                outgoingPresent = false,
                animationProgress = 1f,
            ),
        )
    }

    @Test
    fun installedTransitionUsesAnimationProgress() {
        assertEquals(
            0.35f,
            playerCoverWipeRenderProgress(
                visibleSongId = "new",
                targetSongId = "new",
                outgoingPresent = true,
                animationProgress = 0.35f,
            ),
        )
    }
}
