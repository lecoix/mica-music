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
        assertEquals(
            "USB 独占输出",
            settingsScreenTitle(SettingsCategory.AUDIO, usbHybridSubpageOpen = true),
        )
        assertEquals(
            "远程曲库",
            settingsScreenTitle(SettingsCategory.LIBRARY, remoteMusicSubpageOpen = true),
        )
        assertEquals(
            "外部歌词",
            settingsScreenTitle(SettingsCategory.LYRICS, externalLyricsSubpageOpen = true),
        )
    }

    @Test
    fun settingsRootSearchOpensTutorialEqualizerOrCategory() {
        var tutorial = false
        var equalizer = false
        var category: SettingsCategory? = null

        SettingsSearchIndex.entries.first { it.id == "help.tutorial" }
            .navigateFromSettingsRoot({ category = it }, { tutorial = true }, { equalizer = true })
        assertTrue(tutorial)
        assertFalse(equalizer)
        assertNull(category)

        tutorial = false
        SettingsSearchIndex.entries.first { it.id == "equalizer" }
            .navigateFromSettingsRoot({ category = it }, { tutorial = true }, { equalizer = true })
        assertFalse(tutorial)
        assertTrue(equalizer)
        assertNull(category)

        equalizer = false
        SettingsSearchIndex.entries.first { it.id == "lyrics.external" }
            .navigateFromSettingsRoot({ category = it }, { tutorial = true }, { equalizer = true })
        assertFalse(tutorial)
        assertFalse(equalizer)
        assertEquals(SettingsCategory.LYRICS, category)
    }
}
