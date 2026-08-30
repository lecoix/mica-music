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
        assertTrue(SettingsSearchIndex.search("offload").any { it.id == "diagnostics.audio-offload" })
        assertTrue(SettingsSearchIndex.search("迷你 播放").any { it.id == "appearance.mini-player-style" })
        assertTrue(SettingsSearchIndex.search("睡眠", SettingsIndexSurface.PLAYER_MENU)
            .any { it.id == "player-menu.sleep-timer" })
        assertTrue(SettingsSearchIndex.search("布局编辑").any { it.id == "playback.custom-layout" })
        assertTrue(SettingsSearchIndex.search("USB DoP").any { it.id == "audio.usb-exclusive" })
        assertTrue(SettingsSearchIndex.search("SMB").any { it.id == "library.remote" })
        assertTrue(SettingsSearchIndex.search("WebDAV").any { it.id == "library.remote" })
        assertTrue(SettingsSearchIndex.search("音效实验室").any { it.id == "audio.sound-fx" })
        assertTrue(SettingsSearchIndex.search("混响").any { it.id == "audio.sound-fx" })
        assertTrue(SettingsSearchIndex.search("360").any { it.id == "audio.sound-fx" })
    }

    @Test
    fun conditionalMetadataIncludesMergedCarBluetoothOutput() {
        val classic = SettingsSearchIndex.entries.first { it.id == "lyrics.classic-alignment" }
        val notification = SettingsSearchIndex.entries.first { it.id == "lyrics.notification" }

        assertNotNull(classic.availability)
        assertTrue(classic.availability!!.contains("经典列表"))
        assertTrue(notification.keywords.contains("车载蓝牙"))
        assertNotNull(notification.availability)
        assertTrue(notification.availability!!.contains("车载蓝牙输出与通知栏歌词共用开关"))
    }
}
