package com.mica.music.ui.screens.player

import com.mica.music.data.PlayerCoverFlowMode
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapePlayerPolicyTest {
    @Test
    fun portraitAndSquareWindowsKeepExistingLayout() {
        assertNull(landscapePlayerLayoutPlan(widthDp = 400f, heightDp = 800f))
        assertNull(landscapePlayerLayoutPlan(widthDp = 600f, heightDp = 600f))
    }

    @Test
    fun classifiesCompactWideAndStageAtExplicitBoundaries() {
        assertEquals(
            LandscapePlayerViewport.Compact,
            landscapePlayerLayoutPlan(719f, 400f)?.viewport,
        )
        assertEquals(
            LandscapePlayerViewport.Wide,
            landscapePlayerLayoutPlan(720f, 400f)?.viewport,
        )
        assertEquals(
            LandscapePlayerViewport.Stage,
            landscapePlayerLayoutPlan(1_200f, 600f)?.viewport,
        )
    }

    @Test
    fun columnGapUsesBoundedFourPercentOfLandscapeWidth() {
        assertEquals(24f, landscapePlayerLayoutPlan(500f, 320f)?.columnGapDp ?: -1f, 0.001f)
        assertEquals(25.6f, landscapePlayerLayoutPlan(640f, 360f)?.columnGapDp ?: -1f, 0.001f)
        assertEquals(36f, landscapePlayerLayoutPlan(900f, 500f)?.columnGapDp ?: -1f, 0.001f)
        assertEquals(48f, landscapePlayerLayoutPlan(1_200f, 600f)?.columnGapDp ?: -1f, 0.001f)
        assertEquals(76.8f, landscapePlayerLayoutPlan(1_920f, 1_080f)?.columnGapDp ?: -1f, 0.001f)
        assertEquals(96f, landscapePlayerLayoutPlan(3_840f, 2_160f)?.columnGapDp ?: -1f, 0.001f)
    }

    @Test
    fun coverAlwaysFitsItsLaneAndAvailableHeight() {
        listOf(
            640f to 360f,
            900f to 500f,
            1_280f to 720f,
            2_560f to 1_600f,
        ).forEach { (width, height) ->
            val plan = checkNotNull(landscapePlayerLayoutPlan(width, height))
            assertNotNull(plan)
            assertTrue(plan.coverSizeDp <= plan.coverLaneWidthDp)
            assertTrue(plan.coverSizeDp <= height)
            assertTrue(plan.detailLaneWidthDp > 0f)
        }
    }

    @Test
    fun supportedCoverFlowThemesKeepTheirModesInLandscape() {
        listOf(
            PlayerCoverFlowMode.PAUSE_FOLD,
            PlayerCoverFlowMode.RETRO_3D,
        ).forEach { mode ->
            assertEquals(mode, landscapeFallbackCoverMode(mode))
        }
    }

    @Test
    fun unfinishedThemesUseStaticStandardFallback() {
        listOf(
            PlayerCoverFlowMode.CUSTOM_STANDARD,
            PlayerCoverFlowMode.PARTICLE_COVER,
            PlayerCoverFlowMode.PHOTO_STACK,
        ).forEach { mode ->
            assertEquals(PlayerCoverFlowMode.STANDARD, landscapeFallbackCoverMode(mode))
        }
    }

    @Test
    fun movingControlsToBottomDoesNotTurnOldBottomPaddingIntoMiddleWhitespace() {
        assertEquals(144.dp, landscapeChromeHeight(168.dp, 24.dp))
        assertEquals(0.dp, landscapeChromeHeight(16.dp, 24.dp))
    }
}
