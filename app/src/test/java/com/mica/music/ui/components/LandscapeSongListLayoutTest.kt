package com.mica.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class LandscapeSongListLayoutTest {

    @Test
    fun `song lists use two row-major columns only in landscape windows`() {
        assertEquals(2, songListColumnsFor(widthDp = 800, heightDp = 400))
        assertEquals(1, songListColumnsFor(widthDp = 400, heightDp = 800))
        assertEquals(1, songListColumnsFor(widthDp = 600, heightDp = 600))
    }
}
