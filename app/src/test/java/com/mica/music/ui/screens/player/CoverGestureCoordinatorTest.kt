package com.mica.music.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverGestureCoordinatorTest {

    @Test
    fun dragCommitThresholds_areReducedByTwentyFivePercent() {
        assertEquals(0.35f * 0.75f, CoverFlowDragCommitFraction, 0.0001f)
        assertEquals(0.35f * 0.5f * 0.75f, StandardDragCommitFraction, 0.0001f)
    }

    @Test
    fun standardSwipe_commitsBeyondReducedThreshold() {
        var swipe = StandardDragCommitFraction + 0.001f
        var previousCalls = 0
        var nextCalls = 0
        val handlers = CoverGestureHandlers(
            gesturesEnabled = { true },
            standardMode = { true },
            screenWidthPx = { 1000f },
            standardSwipeFraction = { swipe },
            setStandardSwipeFraction = { swipe = it },
            onPrevious = { previousCalls++ },
            onNext = { nextCalls++ },
        )

        handlers.onDragEnd()

        assertEquals(1, previousCalls)
        assertEquals(0, nextCalls)
        assertEquals(0f, swipe, 0.0001f)
    }

    @Test
    fun standardSwipe_returnsToCenterBelowReducedThreshold() {
        var swipe = StandardDragCommitFraction - 0.001f
        var previousCalls = 0
        var nextCalls = 0
        val handlers = CoverGestureHandlers(
            gesturesEnabled = { true },
            standardMode = { true },
            screenWidthPx = { 1000f },
            standardSwipeFraction = { swipe },
            setStandardSwipeFraction = { swipe = it },
            onPrevious = { previousCalls++ },
            onNext = { nextCalls++ },
        )

        handlers.onDragEnd()

        assertEquals(0, previousCalls)
        assertEquals(0, nextCalls)
        assertEquals(0f, swipe, 0.0001f)
    }
}
