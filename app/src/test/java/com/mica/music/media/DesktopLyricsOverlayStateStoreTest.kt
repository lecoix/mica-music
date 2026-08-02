package com.mica.music.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.mica.music.data.LyricCue

class DesktopLyricsOverlayStateStoreTest {
    @Test
    fun lyricSnapshotFollowsPlaybackVisibilityWithoutDuplicatingPositionPolling() {
        val store = DesktopLyricsOverlayStateStore()

        store.publish("first line", lineIndex = 3)
        assertEquals("first line", store.state.value.text)
        assertFalse(store.state.value.visible)

        store.setPlaying(true)
        assertTrue(store.state.value.visible)
        assertEquals(3, store.state.value.lineIndex)

        store.setPlaying(false)
        assertFalse(store.state.value.visible)
        assertEquals("first line", store.state.value.text)

        store.clear()
        assertEquals(null, store.state.value.text)
        assertFalse(store.state.value.visible)
    }

    @Test
    fun desktopAndStatusBarShareOneBoundedCurrentLineButKeepIndependentEnablement() {
        val store = DesktopLyricsOverlayStateStore()
        val line = ExternalLyricsLine(
            lineIndex = 4,
            startMs = 1_000,
            endMs = 2_000,
            original = ExternalLyricsText("原文", listOf(LyricCue(1_000, "原文"))),
            translation = ExternalLyricsText("translation"),
        )

        store.publish(
            line = line,
            positionMs = 1_200,
            desktopEnabled = true,
            statusBarEnabled = false,
        )
        store.setPlaying(true)

        assertTrue(store.state.value.desktop.visible)
        assertFalse(store.state.value.statusBar.visible)
        assertEquals("translation", store.state.value.desktop.line?.translation?.text)
        assertEquals(1_200, store.state.value.desktop.positionMs)
        assertEquals(1_200, store.state.value.statusBar.positionMs)
    }
}
