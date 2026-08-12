package com.mica.music.media

import com.mica.music.data.ExternalLyricsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLyricsSessionCommandsTest {

    @Test
    fun desktopToggleTurnsDesktopOffAndOtherModesIntoDesktop() {
        assertEquals(
            ExternalLyricsMode.OFF,
            ExternalLyricsSessionCommands.nextModeAfterDesktopToggle(ExternalLyricsMode.DESKTOP),
        )
        assertEquals(
            ExternalLyricsMode.DESKTOP,
            ExternalLyricsSessionCommands.nextModeAfterDesktopToggle(ExternalLyricsMode.OFF),
        )
        assertEquals(
            ExternalLyricsMode.DESKTOP,
            ExternalLyricsSessionCommands.nextModeAfterDesktopToggle(ExternalLyricsMode.STATUS_BAR),
        )
    }

    @Test
    fun desktopToggleButtonTracksMode() {
        assertEquals(
            "开启桌面歌词",
            ExternalLyricsSessionCommands.desktopToggleButtonLabel(ExternalLyricsMode.OFF),
        )
        assertEquals(
            "开启桌面歌词",
            ExternalLyricsSessionCommands.desktopToggleButtonLabel(ExternalLyricsMode.STATUS_BAR),
        )
        assertEquals(
            "关闭桌面歌词",
            ExternalLyricsSessionCommands.desktopToggleButtonLabel(ExternalLyricsMode.DESKTOP),
        )
        assertEquals(
            com.mica.music.R.drawable.ic_desktop_lyrics_off,
            ExternalLyricsSessionCommands.desktopToggleButtonIcon(ExternalLyricsMode.OFF),
        )
        assertEquals(
            com.mica.music.R.drawable.ic_desktop_lyrics_on,
            ExternalLyricsSessionCommands.desktopToggleButtonIcon(ExternalLyricsMode.DESKTOP),
        )
    }

    @Test
    fun lockButtonOnlyExposesForDesktopLyrics() {
        assertTrue(
            ExternalLyricsSessionCommands.shouldExposeDesktopLock(ExternalLyricsMode.DESKTOP),
        )
        assertFalse(
            ExternalLyricsSessionCommands.shouldExposeDesktopLock(ExternalLyricsMode.STATUS_BAR),
        )
        assertFalse(
            ExternalLyricsSessionCommands.shouldExposeDesktopLock(ExternalLyricsMode.OFF),
        )
    }

    @Test
    fun lockButtonLabelTracksLockState() {
        assertEquals("锁定桌面歌词", ExternalLyricsSessionCommands.desktopLockButtonLabel(false))
        assertEquals("解锁桌面歌词", ExternalLyricsSessionCommands.desktopLockButtonLabel(true))
        assertEquals(
            com.mica.music.R.drawable.ic_desktop_lock,
            ExternalLyricsSessionCommands.desktopLockButtonIcon(false),
        )
        assertEquals(
            com.mica.music.R.drawable.ic_desktop_unlock,
            ExternalLyricsSessionCommands.desktopLockButtonIcon(true),
        )
    }
}
