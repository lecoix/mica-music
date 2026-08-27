package com.mica.music.data

import com.mica.music.playback.PendingMediaSelection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMediaSelectionTest {

    @Test
    fun rapidSelectionsIgnoreAllCallbacksUntilLatestTargetArrives() {
        val selection = PendingMediaSelection()

        selection.select("song-b")
        selection.select("song-c")

        assertFalse(selection.shouldAccept("song-a"))
        assertFalse(selection.shouldAccept("song-b"))
        assertTrue(selection.shouldAccept("song-c"))
        assertTrue(selection.shouldAccept("song-d"))
    }
}
