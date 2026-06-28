package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SongTitleDisplayTest {
    @Test
    fun stripParenthetical_removesAsciiAndFullWidthParentheses() {
        assertEquals(
            "Track",
            SongTitleDisplay.stripParenthetical("Track (Live)（Remastered）"),
        )
    }

    @Test
    fun stripParenthetical_removesMultipleSegmentsAndCollapsesSpaces() {
        assertEquals(
            "Track Remix",
            SongTitleDisplay.stripParenthetical("Track  (Live)   Remix (2024)"),
        )
    }

    @Test
    fun stripParenthetical_keepsUnmatchedParentheses() {
        assertEquals(
            "Track (Live",
            SongTitleDisplay.stripParenthetical("Track (Live"),
        )
    }

    @Test
    fun displayTitle_respectsDisabledSetting() {
        assertEquals(
            "Track (Live)",
            SongTitleDisplay.displayTitle("Track (Live)", stripParenthetical = false),
        )
    }
}
