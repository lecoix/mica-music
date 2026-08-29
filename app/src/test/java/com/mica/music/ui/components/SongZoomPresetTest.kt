package com.mica.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SongZoomPresetTest {
    @Test
    fun `four column grid is inserted before the existing grid sizes`() {
        assertEquals(
            listOf(
                "dense_list",
                "normal_list",
                "four_column_grid",
                "dense_grid",
                "large_grid",
            ),
            SongZoomOrder.map { it.id },
        )
    }

    @Test
    fun `four column grid follows the existing adaptive landscape rule`() {
        assertEquals(4, SongZoomPreset.FOUR_COLUMN_GRID.columns(landscape = false))
        assertEquals(5, SongZoomPreset.FOUR_COLUMN_GRID.columns(landscape = true))
    }
}
