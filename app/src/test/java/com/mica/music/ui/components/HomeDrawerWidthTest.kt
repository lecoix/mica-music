package com.mica.music.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDrawerWidthTest {
    @Test
    fun `phone portrait keeps existing half-width behavior`() {
        assertEquals(180.dp, homeDrawerWidthFor(360.dp))
    }

    @Test
    fun `wide landscape drawer is capped`() {
        assertEquals(HomeDrawerMaxWidth, homeDrawerWidthFor(1_200.dp))
    }
}
