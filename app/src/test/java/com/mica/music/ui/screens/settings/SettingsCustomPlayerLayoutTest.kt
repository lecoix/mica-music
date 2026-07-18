package com.mica.music.ui.screens.settings

import com.mica.music.data.PlayerLowerComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCustomPlayerLayoutTest {
    @Test
    fun boundaryPadding_snapsToEvenDpAndClampsToRange() {
        assertEquals(0, snapBoundaryPadding(-5f))
        assertEquals(16, snapBoundaryPadding(15f))
        assertEquals(120, snapBoundaryPadding(125f))
    }

    @Test
    fun reorderPreview_movesWholeItemByLibraryIndices() {
        val items = PlayerLowerComponent.entries.toMutableList()
        val moved = items.first()

        assertTrue(moveCustomPlayerComponent(items, fromIndex = 0, toIndex = 3))
        assertEquals(moved, items[3])
        assertEquals(PlayerLowerComponent.entries.toSet(), items.toSet())
        assertFalse(moveCustomPlayerComponent(items, fromIndex = -1, toIndex = 2))
        assertFalse(moveCustomPlayerComponent(items, fromIndex = 2, toIndex = 2))
    }
}
