package com.mica.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterLyricsLatinRotationTest {
    @Test
    fun `rotated latin text is centered on its column`() {
        val topLeft = letterRotatedLatinTopLeft(
            columnCenterX = 100f,
            verticalTopPx = 80f,
            layoutHeightPx = 20,
        )

        assertEquals(100f, topLeft.x, 0.0001f)
        assertEquals(70f, topLeft.y, 0.0001f)
    }
}
