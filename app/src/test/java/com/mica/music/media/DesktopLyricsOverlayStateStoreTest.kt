package com.mica.music.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
