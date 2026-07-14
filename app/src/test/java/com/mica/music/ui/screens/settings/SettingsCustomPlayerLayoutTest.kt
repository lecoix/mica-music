package com.mica.music.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCustomPlayerLayoutTest {
    @Test
    fun boundaryPadding_snapsToEvenDpAndClampsToRange() {
        assertEquals(0, snapBoundaryPadding(-5f))
        assertEquals(16, snapBoundaryPadding(15f))
        assertEquals(120, snapBoundaryPadding(125f))
    }
}
