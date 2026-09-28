package com.mica.music.ui.screens.player.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStackInteractionGeometryTest {
    @Test
    fun progressHitBandKeepsThirtyTwoDpHeightAcrossTransition() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { progress ->
            val band = photoStackProgressHitBandDp(progress)
            assertEquals(32f, band.heightDp, 0.001f)
            assertTrue(band.topFromBottomDp > band.bottomFromBottomDp)
        }
    }

    @Test
    fun normalProgressHitBandExpandsAroundTheExistingVisualCenter() {
        val band = photoStackProgressHitBandDp(0f)

        assertEquals(37f, band.centerFromBottomDp, 0.001f)
        assertEquals(53f, band.topFromBottomDp, 0.001f)
        assertEquals(21f, band.bottomFromBottomDp, 0.001f)
    }

    @Test
    fun immersiveProgressHitBandKeepsExistingThirtyTwoDpRange() {
        val band = photoStackProgressHitBandDp(1f)

        assertEquals(32f, band.centerFromBottomDp, 0.001f)
        assertEquals(48f, band.topFromBottomDp, 0.001f)
        assertEquals(16f, band.bottomFromBottomDp, 0.001f)
    }
}
