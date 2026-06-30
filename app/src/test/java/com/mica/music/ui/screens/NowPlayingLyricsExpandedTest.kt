package com.mica.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingLyricsExpandedTest {
    @Test
    fun expandedLyricsScrollOffset_defaultsToViewportCenter() {
        assertEquals(
            -450,
            expandedLyricsScrollOffset(
                viewportHeightPx = 1000,
                itemHeightPx = 100,
                currentLineAnchorYPx = null,
            ),
        )
    }

    @Test
    fun expandedLyricsScrollOffset_alignsCurrentLineToProvidedAnchor() {
        assertEquals(
            -250,
            expandedLyricsScrollOffset(
                viewportHeightPx = 1000,
                itemHeightPx = 100,
                currentLineAnchorYPx = 300f,
            ),
        )
    }

    @Test
    fun expandedLyricsScrollOffset_doesNotScrollBeforeTop() {
        assertEquals(
            0,
            expandedLyricsScrollOffset(
                viewportHeightPx = 1000,
                itemHeightPx = 100,
                currentLineAnchorYPx = 40f,
            ),
        )
    }
}
