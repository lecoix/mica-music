package com.mica.music.ui.screens.home

import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsSession
import com.mica.music.data.toLyricsDocumentCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeStatsBarTest {

    private val session = LyricsSession(
        listOf(LyricLine(timeMs = 1_000, text = "hello\n你好")).toLyricsDocumentCompat(),
    )

    @Test
    fun infoRowLyricsUseCurrentLineWhenEnabledAndPlaying() {
        assertEquals(
            "hello 你好",
            infoRowLyricText(
                enabled = true,
                isPlaying = true,
                lyricsSession = session,
                positionMs = 1_100,
                lyricSplitEnabled = true,
                lyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
            ),
        )
    }

    @Test
    fun infoRowLyricsFallBackWhenDisabledOrPaused() {
        assertNull(
            infoRowLyricText(
                enabled = false,
                isPlaying = true,
                lyricsSession = session,
                positionMs = 1_100,
                lyricSplitEnabled = true,
                lyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
            ),
        )
        assertNull(
            infoRowLyricText(
                enabled = true,
                isPlaying = false,
                lyricsSession = session,
                positionMs = 1_100,
                lyricSplitEnabled = true,
                lyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
            ),
        )
    }
}
