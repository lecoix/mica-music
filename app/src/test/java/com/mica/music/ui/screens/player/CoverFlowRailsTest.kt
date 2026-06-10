package com.mica.music.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

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
