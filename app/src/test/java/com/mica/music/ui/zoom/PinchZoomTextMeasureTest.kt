package com.mica.music.ui.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchZoomTextMeasureTest {
    @Test
    fun `compact browse title measures wider before being scaled into the tile`() {
        assertEquals(
            400,
            compensatedTextMeasureWidth(
                visibleWidthPx = 300f,
                scale = 0.75f,
            ),
        )
    }

    @Test
    fun `song grid title rounds compensation up so it cannot leave a scaled gap`() {
        assertEquals(
            343,
            compensatedTextMeasureWidth(
                visibleWidthPx = 300f,
                scale = 0.875f,
            ),
        )
    }

    @Test
    fun `all browse and song scene scales cover the requested visible width`() {
        val visibleWidth = 317f
        listOf(0.625f, 0.675f, 0.70f, 0.75f, 0.86f, 0.875f, 0.90f, 1f).forEach { scale ->
            val measuredWidth = compensatedTextMeasureWidth(visibleWidth, scale)
            assertTrue(
                "scale=$scale measured=$measuredWidth",
                measuredWidth * scale >= visibleWidth,
            )
        }
    }
}
