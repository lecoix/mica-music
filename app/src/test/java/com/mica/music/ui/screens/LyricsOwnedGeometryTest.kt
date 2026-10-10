package com.mica.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LyricsOwnedGeometryTest {
    private fun at(key: String, y: Int, height: Int = 47) =
        OwnedLyricGeometry(key, offsetPx = y, heightPx = height)

    @Test fun scrollOnlyDoesNotGenerateASecondPlacementAnimation() {
        val before = at("line-0", -497)
        val after = at("line-0", -537)
        assertEquals(0f, ownedLyricStructuralDelta(before, after, -40f))
    }

    @Test fun heightChangeUsesRealMeasuredPlacementRatherThanUnreliableMovingAwayTarget() {
        val before = at("line-0", -497)
        val grown = at("line-0", -527)
        assertEquals(-30f, ownedLyricStructuralDelta(before, grown, 0f))
        assertEquals(30f, ownedLyricContinuationPx(0f, -30f))
    }

    @Test fun faithfulOldRowSequenceCannotSynthesizeFoundationPlus463PxTarget() {
        // Exactly the -497 -> -527 -> scroll -40 sequence in B2's faithful probe.
        val old = at("line-0", -497)
        val grown = at("line-0", -527)
        val afterScroll = at("line-0", -567)
        val afterGrow = ownedLyricContinuationPx(
            0f, ownedLyricStructuralDelta(old, grown, 0f),
        )
        assertEquals(30f, afterGrow)
        val afterScrollResidual = ownedLyricContinuationPx(
            afterGrow, ownedLyricStructuralDelta(grown, afterScroll, -40f),
        )
        assertEquals(30f, afterScrollResidual)
        // There is no -35 target because B3 never computes moving-away positions.
        assertEquals(-537f, afterScroll.offsetPx + afterScrollResidual)
    }

    @Test fun interludeCompensationLeavesLowerLineVisuallyAnchored() {
        // Insert 27px before the next line and scroll +27px forward (screen -27px):
        // upper row rises -27, lower row's measured root position stays fixed.
        val upperBefore = at("upper", 120)
        val upperAfter = at("upper", 93)
        val lowerBefore = at("lower", 250)
        val lowerAfter = at("lower", 250)
        assertEquals(0f, ownedLyricStructuralDelta(upperBefore, upperAfter, -27f))
        assertEquals(27f, ownedLyricStructuralDelta(lowerBefore, lowerAfter, -27f))
        // Stationary geometry is a deliberate anchor commit, not a FLIP impulse.
        assertEquals(0, lowerAfter.offsetPx - lowerBefore.offsetPx)
    }

    @Test fun consecutiveChangesContinueFromCurrentVisualOffset() {
        assertEquals(10f, ownedLyricContinuationPx(30f, 20f))
        assertEquals(32f, ownedLyricContinuationPx(10f, -22f))
    }

    @Test fun changedKeysCannotBorrowObsoleteMotion() {
        assertThrows(IllegalArgumentException::class.java) {
            ownedLyricStructuralDelta(at("old", 40), at("new", 80), 0f)
        }
    }

    @Test fun recycleReentryAndSeekClearIdentityAndTransform() {
        val motion = OwnedLyricsPositionMotion(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        motion.accept(listOf(at("first", 40)), animate = false, stiffness = 170f, scrolling = false)
        assertEquals(0f, motion.translationFor("first"))
        motion.accept(listOf(at("second", 40)), animate = false, stiffness = 170f, scrolling = false)
        assertEquals(0f, motion.translationFor("first"))
        assertEquals(0f, motion.translationFor("second"))
        motion.reset()
        assertEquals(0f, motion.translationFor("second"))
        motion.accept(listOf(at("first", 55)), animate = false, stiffness = 170f, scrolling = false)
        assertEquals(0f, motion.translationFor("first"))
    }
}
