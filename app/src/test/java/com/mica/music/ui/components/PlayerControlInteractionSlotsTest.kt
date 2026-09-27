package com.mica.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlInteractionSlotsTest {
    @Test
    fun fiveSlotsPartitionAvailableWidthAtVisualMidpointsWithoutOverlap() {
        val slots = equalPlayerControlInteractionSlots(
            availableWidthDp = 358f,
            count = 5,
            minimumTouchTargetDp = 48f,
        )!!

        assertEquals(5, slots.size)
        assertEquals(71.6f, slots.first().widthDp, 0.001f)
        assertEquals(35.8f, slots.first().centerDp, 0.001f)
        assertEquals(322.2f, slots.last().centerDp, 0.001f)
        slots.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endDp, right.startDp, 0.001f)
            assertTrue(left.widthDp >= 48f)
            assertTrue(right.widthDp >= 48f)
        }
    }

    @Test
    fun visualScaleDoesNotShrinkInteractionSlots() {
        val fullVisual = equalPlayerControlInteractionSlots(358f)!!
        val compactVisual = equalPlayerControlInteractionSlots(358f)!!

        assertEquals(fullVisual, compactVisual)
    }

    @Test
    fun insufficientWidthRequestsReflowInsteadOfOverlappingHitTargets() {
        assertNull(
            equalPlayerControlInteractionSlots(
                availableWidthDp = 239f,
                count = 5,
                minimumTouchTargetDp = 48f,
            ),
        )
    }
}
