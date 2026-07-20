package com.mica.music.ui.components

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerLyricsPresentationTest {
    @Test
    fun usesSharedLyricTextWhilePlayingAndFallsBackWhenPaused() {
        val song = SongFixtures.song(id = "shared", title = "Song").copy(artist = "Artist")

        val playing = miniPlayerText(song, isPlaying = true, enabled = true, lyricText = "Line")
        val paused = miniPlayerText(song, isPlaying = false, enabled = true, lyricText = "Line")

        assertEquals(MiniPlayerText("Line", "Song - Artist"), playing)
        assertEquals(MiniPlayerText("Song", "Artist"), paused)
    }
}
