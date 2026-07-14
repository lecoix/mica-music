package com.mica.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AlphabetFastScrollerTest {

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
