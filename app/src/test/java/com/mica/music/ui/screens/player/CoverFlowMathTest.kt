package com.mica.music.ui.screens.player

import com.mica.music.data.PlayerCoverFlowMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverFlowMathTest {

    @Test
    fun pauseFold_slotTranslationLinearWithOffset() {
        val screenWidthPx = 1080f
        val offset = 0.42f
        val expected = offset * CoverFlowMath.LaneStepFraction * screenWidthPx
        assertEquals(
            expected,
            CoverFlowMath.slotTranslation(
                offset = offset,
                screenWidthPx = screenWidthPx,
                mode = PlayerCoverFlowMode.PAUSE_FOLD,
            ),
            0.01f,
        )
    }

    @Test
    fun pauseFold_anchorSnapPreservesPixelPosition() {
        val screenWidthPx = 1000f
        val laneFraction = 1f
        val laneOffsetBefore = 1
        val before = CoverFlowMath.slotTranslation(
            offset = laneOffsetBefore - laneFraction,
            screenWidthPx = screenWidthPx,
            mode = PlayerCoverFlowMode.PAUSE_FOLD,
        )
        val after = CoverFlowMath.slotTranslation(
            offset = (laneOffsetBefore - 1) - 0f,
            screenWidthPx = screenWidthPx,
            mode = PlayerCoverFlowMode.PAUSE_FOLD,
        )
        assertEquals(before, after, 0.01f)
    }

    @Test
    fun retro3d_anchorSnapPreservesPixelPosition_atCenter() {
        val screenWidthPx = 1000f
        val laneFraction = 1f
        val before = CoverFlowMath.slotTranslation(
            offset = 1 - laneFraction,
            screenWidthPx = screenWidthPx,
            mode = PlayerCoverFlowMode.RETRO_3D,
        )
        val after = CoverFlowMath.slotTranslation(
            offset = 0f,
            screenWidthPx = screenWidthPx,
            mode = PlayerCoverFlowMode.RETRO_3D,
        )
        assertEquals(before, after, 0.01f)
    }

    @Test
    fun pauseFold_carouselDecompositionMatchesFloatOffset() {
        val screenWidthPx = 1080f
        val laneFraction = 0.42f
        val laneOffset = 1
        val floatOffset = laneOffset - laneFraction
        val carousel = CoverFlowMath.carouselShiftPx(
            laneFraction = laneFraction,
            screenWidthPx = screenWidthPx,
            mode = PlayerCoverFlowMode.PAUSE_FOLD,
        )
        val slot = CoverFlowMath.slotTranslation(
            offset = laneOffset.toFloat(),
            screenWidthPx = screenWidthPx,
            mode = PlayerCoverFlowMode.PAUSE_FOLD,
        )
        val combined = CoverFlowMath.slotTranslation(
            offset = floatOffset,
            screenWidthPx = screenWidthPx,
            mode = PlayerCoverFlowMode.PAUSE_FOLD,
        )
        assertEquals(combined, carousel + slot, 0.01f)
    }

    @Test
    fun retro3d_translationContinuousThroughCenter() {
        val screenWidthPx = 1000f
        val nearZero = CoverFlowMath.slotTranslation(
            offset = 0.001f,
            screenWidthPx = screenWidthPx,
            mode = PlayerCoverFlowMode.RETRO_3D,
        )
        val center = CoverFlowMath.slotTranslation(
            offset = 0f,
            screenWidthPx = screenWidthPx,
            mode = PlayerCoverFlowMode.RETRO_3D,
        )
        assertEquals(0f, center, 0.0001f)
        assertEquals(0.81f, nearZero, 0.01f)
    }
}
