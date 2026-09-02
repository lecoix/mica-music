package com.mica.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMediaSelectionTest {

    @Test
    fun rapidSelectionsRejectStaleCallbacksUntilLatestTargetIsConfirmed() {
        val selection = PendingMediaSelection()

        selection.select("song-b")
        selection.select("song-c")

        assertFalse(selection.shouldAccept("song-a"))
        assertFalse(selection.shouldAccept("song-b"))
        assertTrue(selection.shouldAccept("song-c"))
        assertFalse(selection.shouldAccept("song-d"))

        assertFalse(selection.confirm("song-b"))
        assertTrue(selection.confirm("song-c"))
        assertTrue(selection.shouldAccept("song-d"))
    }

    @Test
    fun clearReleasesSelectionWithoutConfirmation() {
        val selection = PendingMediaSelection()

        selection.select("song-b")
        assertFalse(selection.shouldAccept("song-a"))

        selection.clear()

        assertTrue(selection.shouldAccept("song-a"))
    }
}
