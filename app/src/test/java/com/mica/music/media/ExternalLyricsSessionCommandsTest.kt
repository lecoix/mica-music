package com.mica.music.media

import com.mica.music.data.ExternalLyricsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLyricsSessionCommandsTest {

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
