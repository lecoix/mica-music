package com.mica.music.ui.screens

import com.mica.music.data.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingLyricsExpandedTest {

    @Test
    fun expandedDisplayItems_keepOriginalLyricIndexAndOrder() {
        val lyrics = listOf(
            LyricLine(1_000, "first"),
            LyricLine(2_000, "second"),
        )

        val items = expandedLyricsDisplayItems(lyrics)

        assertEquals(listOf(0, 1), items.map { (it as ExpandedLyricDisplayItem.Line).lyricIndex })
        assertEquals(lyrics, items.map { (it as ExpandedLyricDisplayItem.Line).line })
    }

    @Test
    fun expandedDisplayItems_addYInterludeWhenNoLineIsActiveAndNextEventIsAtLeastSevenSecondsAway() {
        val lyrics = listOf(
            LyricLine(1_000, "first", endTimeMs = 2_000),
            LyricLine(15_000, "second"),
        )

        val interludes = expandedLyricsDisplayItems(lyrics, playbackPositionMs = 5_000)
            .filterIsInstance<ExpandedLyricDisplayItem.Interlude>()

        assertEquals(listOf(ExpandedLyricDisplayItem.Interlude(nextLyricIndex = 1)), interludes)
    }

    @Test
    fun expandedDisplayItems_doNotAddYInterludeWhileALineIsActive() {
        val lyrics = listOf(
            LyricLine(1_000, "first", endTimeMs = 10_000),
            LyricLine(15_000, "second"),
        )

        assertEquals(
            emptyList<ExpandedLyricDisplayItem.Interlude>(),
            expandedLyricsDisplayItems(lyrics, playbackPositionMs = 5_000)
                .filterIsInstance<ExpandedLyricDisplayItem.Interlude>(),
        )
    }

    @Test
    fun expandedDisplayItems_doNotAddYInterludeWithoutPreviousExplicitEnd() {
        val lyrics = listOf(LyricLine(1_000, "first"), LyricLine(15_000, "second"))

        assertEquals(
            emptyList<ExpandedLyricDisplayItem.Interlude>(),
            expandedLyricsDisplayItems(lyrics, playbackPositionMs = 5_000)
                .filterIsInstance<ExpandedLyricDisplayItem.Interlude>(),
        )
    }

    @Test
    fun expandedDisplayItems_removeYInterludeWithinSevenSecondsOfNextEvent() {
        val lyrics = listOf(
            LyricLine(1_000, "first", endTimeMs = 2_000),
            LyricLine(15_000, "second"),
        )

        assertEquals(
            emptyList<ExpandedLyricDisplayItem.Interlude>(),
            expandedLyricsDisplayItems(lyrics, playbackPositionMs = 8_001)
                .filterIsInstance<ExpandedLyricDisplayItem.Interlude>(),
        )
    }

    @Test
    fun expandedDisplayItems_keepYInterludeExactlySevenSecondsBeforeNextEvent() {
        val lyrics = listOf(
            LyricLine(1_000, "first", endTimeMs = 2_000),
            LyricLine(15_000, "second"),
        )

        assertEquals(
            listOf(ExpandedLyricDisplayItem.Interlude(nextLyricIndex = 1)),
            expandedLyricsDisplayItems(lyrics, playbackPositionMs = 8_000)
                .filterIsInstance<ExpandedLyricDisplayItem.Interlude>(),
        )
    }

    @Test
    fun expandedLyricsScrollOffset_defaultsToViewportCenter() {
        assertEquals(
            -450,
            expandedLyricsScrollOffset(
                viewportHeightPx = 1000,
                itemHeightPx = 100,
                currentLineAnchorYPx = null,
            ),
        )
    }

    @Test
    fun expandedLyricsScrollOffset_alignsCurrentLineToProvidedAnchor() {
        assertEquals(
            -250,
            expandedLyricsScrollOffset(
                viewportHeightPx = 1000,
                itemHeightPx = 100,
                currentLineAnchorYPx = 300f,
            ),
        )
    }

    @Test
    fun expandedLyricsScrollOffset_doesNotScrollBeforeTop() {
        assertEquals(
            0,
            expandedLyricsScrollOffset(
                viewportHeightPx = 1000,
                itemHeightPx = 100,
                currentLineAnchorYPx = 40f,
            ),
        )
    }
}
