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
}
