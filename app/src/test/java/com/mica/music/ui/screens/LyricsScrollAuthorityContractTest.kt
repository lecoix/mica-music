package com.mica.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Architecture Contract A-01/A-03 and Behavior D1/D2. */
class LyricsScrollAuthorityContractTest {
    @Test fun manualScrollOwnsListUntilFourSecondRecoveryIsCommitted() {
        val authority = LyricsScrollAuthority()
        assertTrue(authority.mayFollow)
        authority.beginGesture()
        assertFalse(authority.mayFollow)
        assertEquals(LyricsScrollMode.BROWSING_DRAG, authority.mode)
        val token = authority.handOffToFling()
        assertEquals(LyricsScrollMode.BROWSING_FLING, authority.mode)
        assertTrue(authority.settleGesture(token))
        assertEquals(LyricsScrollMode.BROWSING_HOLD, authority.mode)
        assertFalse(authority.mayFollow)
        assertTrue(authority.returnToCurrent(token))
        assertTrue(authority.mayFollow)
        assertEquals(LyricsScrollMode.RECOVERING, authority.mode)
        authority.followSettled()
        assertEquals(LyricsScrollMode.FOLLOWING, authority.mode)
    }

    @Test fun newerDragInvalidatesOldFourSecondTimerAndReturn() {
        val authority = LyricsScrollAuthority()
        authority.beginGesture()
        val old = authority.handOffToFling()
        assertTrue(authority.settleGesture(old))
        authority.beginGesture()
        assertFalse(authority.returnToCurrent(old))
        val current = authority.handOffToFling()
        assertTrue(authority.settleGesture(current))
        assertTrue(authority.returnToCurrent(current))
        authority.beginGesture()
        authority.followSettled()
        assertEquals(LyricsScrollMode.BROWSING_DRAG, authority.mode)
    }

    @Test fun explicitSeekImmediatelyCancelsGestureFlingAndHoldWithoutResumingOldTimer() {
        for (before in 0..2) {
            val authority = LyricsScrollAuthority()
            authority.beginGesture()
            val old = authority.handOffToFling()
            if (before == 2) assertTrue(authority.settleGesture(old))
            authority.resetToFollowing()
            assertEquals(LyricsScrollMode.FOLLOWING, authority.mode)
            assertFalse(authority.settleGesture(old))
            assertFalse(authority.returnToCurrent(old))
            assertTrue(authority.mayFollow)
        }
    }
}
