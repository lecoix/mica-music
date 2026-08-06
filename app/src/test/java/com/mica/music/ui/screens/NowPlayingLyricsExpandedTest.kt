package com.mica.music.ui.screens

import com.mica.music.data.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        assertEquals(
            listOf(ExpandedLyricDisplayItem.Interlude(1, 2_000, 15_000)),
            interludes,
        )
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
    fun expandedDisplayItems_keepYInterludeUntilNextEvent() {
        val lyrics = listOf(
            LyricLine(1_000, "first", endTimeMs = 2_000),
            LyricLine(15_000, "second"),
        )

        assertEquals(
            listOf(ExpandedLyricDisplayItem.Interlude(1, 2_000, 15_000)),
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
            listOf(ExpandedLyricDisplayItem.Interlude(1, 2_000, 15_000)),
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

    @Test
    fun expandedLyricsTrailingPadding_keepsTheEndScrollableToItsAnchor() {
        assertEquals(500, expandedLyricsTrailingPaddingPx(1000, currentLineAnchorYPx = null))
        assertEquals(700, expandedLyricsTrailingPaddingPx(1000, currentLineAnchorYPx = 300f))
    }

    @Test
    fun expandedLyricsLeadingPadding_keepsTheStartAtItsAnchor() {
        assertEquals(450, expandedLyricsLeadingPaddingPx(1000, 100, currentLineAnchorYPx = null))
        assertEquals(250, expandedLyricsLeadingPaddingPx(1000, 100, currentLineAnchorYPx = 300f))
    }

    @Test
    fun expandedLyricsIndexedScrollOffset_doesNotCountLeadingPaddingTwice() {
        assertEquals(0, expandedLyricsIndexedScrollOffset(leadingPaddingPx = 450, viewportOffsetPx = -450))
        assertEquals(50, expandedLyricsIndexedScrollOffset(leadingPaddingPx = 450, viewportOffsetPx = -400))
    }

    @Test
    fun expandedLyricsLayoutTransition_freezesScrollDuringOpenAndClose() {
        val closing = expandedLyricsLayoutTransition(lyricsPageOpen = false, lyricsLayoutFocus = 0.5f)
        assertTrue(closing.freezeScroll)
        assertTrue(closing.snapshotViewport)
        assertFalse(closing.hideUntilSettled)

        val opening = expandedLyricsLayoutTransition(lyricsPageOpen = true, lyricsLayoutFocus = 0.5f)
        assertTrue(opening.freezeScroll)
        assertFalse(opening.snapshotViewport)
        assertTrue(opening.hideUntilSettled)

        val settledOpen = expandedLyricsLayoutTransition(lyricsPageOpen = true, lyricsLayoutFocus = 1f)
        assertFalse(settledOpen.freezeScroll)
        assertFalse(settledOpen.hideUntilSettled)

        val settledClosed = expandedLyricsLayoutTransition(lyricsPageOpen = false, lyricsLayoutFocus = 0f)
        assertFalse(settledClosed.freezeScroll)
    }

    @Test
    fun classicLyricsMoveSpring_isStifferForRapidLines() {
        val rapid = classicLyricsMoveStiffness(100)
        val relaxed = classicLyricsMoveStiffness(800)

        assertEquals(true, rapid > relaxed)
        assertEquals(220f, rapid)
        assertEquals(170f, relaxed)
    }

    @Test
    fun classicInterludeVisuals_fadesAtBothEndsAndLightsDotsInOrder() {
        val start = classicInterludeVisuals(2_000, 12_000, 2_000)
        val middle = classicInterludeVisuals(2_000, 12_000, 7_000)
        val end = classicInterludeVisuals(2_000, 12_000, 12_000)

        assertEquals(0f, start.globalAlpha)
        assertEquals(true, middle.dotAlpha[0] >= middle.dotAlpha[1])
        assertEquals(true, middle.dotAlpha[1] >= middle.dotAlpha[2])
        assertEquals(0f, end.globalAlpha)
    }

    @Test
    fun classicLyricsStaggerDelay_usesShrinkingFiftyMillisecondSteps() {
        assertEquals(0L, classicLyricsStaggerDelayMs(0))
        assertEquals(50L, classicLyricsStaggerDelayMs(1))
        assertEquals(true, classicLyricsStaggerDelayMs(3) < 150L)
        assertEquals(true, classicLyricsStaggerDelayMs(3) > classicLyricsStaggerDelayMs(2))
    }

    @Test
    fun classicLyricsLagOffset_compensatesOnlyTheDelayedScrollDistance() {
        assertEquals(0f, classicLyricsLagOffset(actualScrollPx = 0f, delayedScrollPx = 0f))
        assertEquals(7.5f, classicLyricsLagOffset(actualScrollPx = 30f, delayedScrollPx = 0f))
        assertEquals(0f, classicLyricsLagOffset(actualScrollPx = 30f, delayedScrollPx = 30f))
    }

}
