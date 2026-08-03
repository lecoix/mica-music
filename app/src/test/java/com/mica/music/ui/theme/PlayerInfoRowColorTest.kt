package com.mica.music.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerInfoRowColorTest {

    @Test
    fun darkForegroundUsesTheStrongerSecondaryColor() {
        val colors = darkPlayerContentColors()

        assertEquals(colors.secondary, resolvePlayerInfoRowTextColor(colors))
    }

    @Test
    fun lightForegroundKeepsTheTertiaryColor() {
        val colors = lightPlayerContentColors()

        assertEquals(colors.tertiary, resolvePlayerInfoRowTextColor(colors))
    }
}
