package com.mica.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AlphabetFastScrollerTest {

    @Test
    fun landscapeIndexUsesAtMostEightyPercentAndCentersInTheWindow() {
        val phoneLayout = landscapeAlphabetIndexLayout(windowHeight = 406f, baseHeight = 384f)
        assertEquals(40.6f, phoneLayout.top, 0.001f)
        assertEquals(324.8f, phoneLayout.height, 0.001f)

        val tallLayout = landscapeAlphabetIndexLayout(windowHeight = 600f, baseHeight = 384f)
        assertEquals(108f, tallLayout.top, 0.001f)
        assertEquals(384f, tallLayout.height, 0.001f)
    }

    @Test
    fun landscapeIndexShrinksTextOnlyWhenAllLabelsWouldNotFit() {
        val scale = landscapeAlphabetTextScale(
            indexHeight = 288f,
            labelCount = 27,
            baseLineHeight = 14f,
        )
        assertEquals(288f / (27f * 14f) * 0.96f, scale, 0.001f)
        assertEquals(
            1f,
            landscapeAlphabetTextScale(
                indexHeight = 384f,
                labelCount = 27,
                baseLineHeight = 14f,
            ),
            0f,
        )
    }

    @Test
    fun windowCenteredIndexCompensatesForThePopupTouchLayerOffset() {
        val windowLayout = AlphabetIndexLayout(top = 11f, height = 384f)
        val localLayout = indexLayoutRelativeToTouchStrip(
            windowLayout = windowLayout,
            touchStripTopInWindow = -35f,
        )

        assertEquals(46f, localLayout.top)
        assertEquals(11f, -35f + localLayout.top)
        assertEquals(384f, localLayout.height)
    }

    @Test
    fun sectionLabelsFollowSortDirection() {
        assertEquals("A", alphabetFastScrollLabels(descending = false).first())
        assertEquals("#", alphabetFastScrollLabels(descending = false).last())

        assertEquals("#", alphabetFastScrollLabels(descending = true).first())
        assertEquals("A", alphabetFastScrollLabels(descending = true).last())
    }

    @Test
    fun indexKeepsItsBottomAndMirrorsTheScreenGapAtTheTop() {
        val layout = alphabetIndexLayout(
            viewport = AlphabetIndexViewport(
                containerTop = 120f,
                containerHeight = 680f,
                rootHeight = 800f,
            ),
            baseHeight = 384f,
        )

        assertEquals(28f, layout.top)
        assertEquals(504f, layout.height)
        assertEquals(148f, 120f + layout.top)
        assertEquals(148f, 800f - (120f + layout.top + layout.height))
    }
}
