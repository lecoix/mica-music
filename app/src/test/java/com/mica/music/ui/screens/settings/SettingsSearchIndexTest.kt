package com.mica.music.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {
    @Test
    fun entriesHaveUniqueStableIds() {
        val ids = SettingsSearchIndex.entries.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun settingsEntriesCoverEveryStableCategory() {
        SettingsCategory.entries.forEach { category ->
            assertTrue(
                "Missing settings index category: ${category.name}",
                SettingsSearchIndex.entries.any { it.target.category == category },
            )
        }
    }

    @Test
    fun searchMatchesChineseAndEnglishKeywords() {
        assertTrue(SettingsSearchIndex.search("ReplayGain").any { it.id == "audio.replaygain" })
        assertTrue(SettingsSearchIndex.search("迷你 播放").any { it.id == "appearance.mini-player-style" })
        assertTrue(SettingsSearchIndex.search("睡眠", SettingsIndexSurface.PLAYER_MENU)
            .any { it.id == "player-menu.sleep-timer" })
    }

    @Test
    fun conditionalAndExperimentalMetadataIsPreserved() {
        val classic = SettingsSearchIndex.entries.first { it.id == "lyrics.classic-alignment" }
        val carBluetooth = SettingsSearchIndex.entries.first { it.id == "lyrics.car-bluetooth" }

        assertNotNull(classic.availability)
        assertTrue(classic.availability!!.contains("经典列表"))
        assertTrue(carBluetooth.isExperimental)
    }
}
