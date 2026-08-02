package com.mica.music.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun canSettingsSubpageBackFalseAtRoot() {
        assertFalse(canSettingsSubpageBack(null, playerOverlayOpen = false))
    }

    @Test
    fun canSettingsSubpageBackFalseWhenPlayerOverlayOpen() {
        assertFalse(
            canSettingsSubpageBack(
                SettingsCategory.APPEARANCE,
                playerOverlayOpen = true,
            ),
        )
    }

    @Test
    fun canSettingsSubpageBackTrueOnSubpage() {
        assertTrue(
            canSettingsSubpageBack(
                SettingsCategory.LIBRARY,
                playerOverlayOpen = false,
            ),
        )
    }

    @Test
    fun consumeSettingsBackClearsSelection() {
        assertNull(consumeSettingsBack(SettingsCategory.PLAYBACK))
    }

    @Test
    fun resolveSettingsTopBarBackActionAtRootExitsSettings() {
        assertEquals(
            SettingsTopBarBackAction.ExitSettings,
            resolveSettingsTopBarBackAction(null),
        )
    }

    @Test
    fun resolveSettingsTopBarBackActionOnSubpagePopsCategory() {
        assertEquals(
            SettingsTopBarBackAction.PopCategory,
            resolveSettingsTopBarBackAction(SettingsCategory.DIAGNOSTICS),
        )
    }

    @Test
    fun settingsScreenTitleShowsCategoryOrDefault() {
        assertEquals("设置", settingsScreenTitle(null))
        assertEquals("播放页", settingsScreenTitle(SettingsCategory.PLAYBACK))
    }
}
