package com.mica.music.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerInfoRowMetricsTest {
    @Test
    fun visualHeight_capsAtDisplayHeight() {
        assertEquals(24.dp, hiResBadgeVisualHeight(rowHeight = 16.dp))
    }

    @Test
    fun visualHeight_respectsOverflowBudgetWhenRowIsTaller() {
        assertEquals(24.dp, hiResBadgeVisualHeight(rowHeight = 20.dp))
        assertEquals(24.dp, hiResBadgeVisualHeight(rowHeight = 22.dp))
        assertEquals(18.dp, hiResBadgeVisualHeight(rowHeight = 10.dp))
    }
}
