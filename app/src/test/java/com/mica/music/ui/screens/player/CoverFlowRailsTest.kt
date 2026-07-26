package com.mica.music.ui.screens.player

import com.mica.music.data.PlayerCoverFlowMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CoverFlowRailsTest {

    @Test
    fun adjacentCommit_preservesRailOffsetForEveryLane() {
        val strip = 1f
        for (lane in -3..3) {
            val before = CoverFlowRails.railOffset(lane, strip)
            val after = CoverFlowRails.railOffset(lane - 1, 0f)
            assertEquals("lane $lane rail should be continuous at commit", before, after, 0.0001f)
        }
    }

    @Test
    fun adjacentCommit_previousTrack_preservesRailOffset() {
        val strip = -1f
        for (lane in -3..3) {
            val before = CoverFlowRails.railOffset(lane, strip)
            val after = CoverFlowRails.railOffset(lane + 1, 0f)
            assertEquals("lane $lane rail should be continuous at commit", before, after, 0.0001f)
        }
    }

    @Test
    fun nextTrackAnimation_movesNeighborToCenter() {
        val step = CoverFlowRails.PauseFoldStep
        val w = 300f
        val start = CoverFlowRails.translationPx(1f, w, com.mica.music.data.PlayerCoverFlowMode.PAUSE_FOLD)
        val end = CoverFlowRails.translationPx(0f, w, com.mica.music.data.PlayerCoverFlowMode.PAUSE_FOLD)
        assertEquals(step * w, start, 0.01f)
        assertEquals(0f, end, 0.01f)
    }

    @Test
    fun expandedRetroRails_spreadAllThirteenLanesWithoutStacking() {
        val w = 300f
        var previous = 0f
        for (lane in 1..6) {
            val translation = CoverFlowRails.translationPx(
                railOffset = lane.toFloat(),
                coverWidthPx = w,
                mode = PlayerCoverFlowMode.RETRO_3D,
                expandedRetro = true,
            )
            assertTrue("lane $lane must be farther from center", translation > previous)
            previous = translation
        }
        assertEquals(2.08f * w, previous, 0.01f)
    }

    @Test
    fun portraitRetroRails_keepOriginalSpacing() {
        val w = 300f
        assertEquals(
            CoverFlowRails.RetroFirstStep * w,
            CoverFlowRails.translationPx(1f, w, PlayerCoverFlowMode.RETRO_3D),
            0.01f,
        )
    }

    @Test
    fun expandedRetroRails_pullNearestPairOutAndEnlargeAllSideCovers() {
        val w = 300f
        assertEquals(
            CoverFlowRails.LandscapeRetroFirstStep * w,
            CoverFlowRails.translationPx(
                1f,
                w,
                PlayerCoverFlowMode.RETRO_3D,
                expandedRetro = true,
            ),
            0.01f,
        )

        for (lane in 1..6) {
            val normal = CoverFlowRails.drawScale(
                lane.toFloat(),
                PlayerCoverFlowMode.RETRO_3D,
                foldProgress = 1f,
            )
            val expanded = CoverFlowRails.drawScale(
                lane.toFloat(),
                PlayerCoverFlowMode.RETRO_3D,
                foldProgress = 1f,
                expandedRetro = true,
            )
            assertEquals(
                "lane $lane should use the landscape side-cover multiplier",
                normal * CoverFlowRails.LandscapeRetroSideScaleMultiplier,
                expanded,
                0.0001f,
            )
        }
    }

    @Test
    fun expandedRetroRails_reduceRotationOneDegreePerOuterLane() {
        assertEquals(
            -CoverFlowRails.LandscapeRetroFirstRotationY,
            CoverFlowRails.rotationY(
                1f,
                PlayerCoverFlowMode.RETRO_3D,
                expandedRetro = true,
            ),
            0.0001f,
        )
        for (lane in 2..6) {
            val expectedAngle =
                CoverFlowRails.LandscapeRetroFirstRotationY -
                    (lane - 1) * CoverFlowRails.LandscapeRetroOuterRotationStep
            assertEquals(
                -expectedAngle,
                CoverFlowRails.rotationY(
                    lane.toFloat(),
                    PlayerCoverFlowMode.RETRO_3D,
                    expandedRetro = true,
                ),
                0.0001f,
            )
        }
        assertEquals(
            72f,
            CoverFlowRails.rotationY(
                -2f,
                PlayerCoverFlowMode.RETRO_3D,
                expandedRetro = true,
            ),
            0.0001f,
        )
        assertEquals(
            -72.5f,
            CoverFlowRails.rotationY(
                1.5f,
                PlayerCoverFlowMode.RETRO_3D,
                expandedRetro = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun expandedRetroScale_staysContinuousWhenCenterCoverStartsMovingSideways() {
        val before = CoverFlowRails.drawScale(
            railOffset = 0.0799f,
            mode = PlayerCoverFlowMode.RETRO_3D,
            foldProgress = 0f,
            expandedRetro = true,
        )
        val after = CoverFlowRails.drawScale(
            railOffset = 0.0801f,
            mode = PlayerCoverFlowMode.RETRO_3D,
            foldProgress = 0f,
            expandedRetro = true,
        )

        assertTrue(
            "scale must not jump at the old side-cover threshold: $before -> $after",
            abs(after - before) < 0.01f,
        )
    }

    @Test
    fun clampTrackChangeStartVisual_limitsOvershootWhenVisualLags() {
        val clamped = CoverFlowRails.clampTrackChangeStartVisual(
            fromLogicalCenter = 6,
            startVisual = 5.5f,
            endVisual = 7f,
            signedDelta = 1,
        )
        assertEquals(6f, clamped, 0.0001f)
    }

    @Test
    fun clampTrackChangeStartVisual_preservesDragProgressWithinSlot() {
        val clamped = CoverFlowRails.clampTrackChangeStartVisual(
            fromLogicalCenter = 5,
            startVisual = 5.4f,
            endVisual = 6f,
            signedDelta = 1,
        )
        assertEquals(5.4f, clamped, 0.0001f)
    }
}
