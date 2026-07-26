package com.mica.music.ui.screens

import androidx.compose.ui.unit.sp
import com.mica.music.data.LyricCue
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.renderStateAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsCloudLayoutTest {
    @Test
    fun explicitLongGapBecomesCloudInterludeForItsWholeDuration() {
        val lyrics = listOf(
            LyricLine(timeMs = 1_000, text = "first", endTimeMs = 2_000),
            LyricLine(timeMs = 15_000, text = "second"),
        )

        val interlude = lyricsCloudInterlude(lyrics.renderStateAt(14_000))

        assertEquals(0, interlude?.previousIndex)
        assertEquals(1, interlude?.nextIndex)
        assertEquals(12f / 13f, interlude?.progress ?: 0f, 0.0001f)
    }

    @Test
    fun inferredOrShortGapDoesNotBecomeCloudInterlude() {
        val inferred = listOf(
            LyricLine(timeMs = 1_000, text = "first"),
            LyricLine(timeMs = 15_000, text = "second"),
        )
        val short = listOf(
            LyricLine(timeMs = 1_000, text = "first", endTimeMs = 2_000),
            LyricLine(timeMs = 8_000, text = "second"),
        )

        assertEquals(null, lyricsCloudInterlude(inferred.renderStateAt(5_000)))
        assertEquals(null, lyricsCloudInterlude(short.renderStateAt(5_000)))
    }

    @Test
    fun layoutIsStableAndDoesNotOverlap() {
        val sizes = List(80) { index ->
            LyricsCloudSize(width = 0.4f + index % 7 * 0.13f, height = 0.22f + index % 3 * 0.05f)
        }

        val first = buildLyricsCloudLayout(sizes, seed = 938)
        val second = buildLyricsCloudLayout(sizes, seed = 938)

        assertEquals(first, second)
        assertEquals(0f, first.last().x)
        assertEquals(0f, first.last().y)
        first.forEachIndexed { index, node ->
            first.drop(index + 1).forEach { other ->
                assertFalse(node.overlaps(other, gap = 0.14f))
            }
        }
    }

    @Test
    fun cueIsSplitIntoCharacterProgress() {
        val line = LyricLine(
            timeMs = 0,
            text = "你好世界",
            cues = listOf(
                LyricCue(timeMs = 0, text = "你好"),
                LyricCue(timeMs = 1_000, text = "世界"),
            ),
        )

        assertEquals(
            CloudCharacterState(activeIndex = 0, progress = 0.5f),
            cloudCharacterState(line, positionMs = 100, nextLineTimeMs = 2_000),
        )
        assertEquals(
            CloudCharacterState(activeIndex = 1, progress = 0f),
            cloudCharacterState(line, positionMs = 350, nextLineTimeMs = 2_000),
        )
    }

    @Test
    fun longLinePanUsesEaseOutAndExtraTravel() {
        val start = lyricsCloudPanOffset(0, 1_000, 0, 1_000f, 500f, 100f)
        val middle = lyricsCloudPanOffset(0, 1_000, 500, 1_000f, 500f, 100f)
        val end = lyricsCloudPanOffset(0, 1_000, 1_000, 1_000f, 500f, 100f)

        assertEquals(-3.456f, start, 0.0001f)
        assertEquals(2.592f, middle, 0.0001f)
        assertEquals(3.456f, end, 0.0001f)
    }

    @Test
    fun revealStartsAtCurrentLineAndSpreadsByDistance() {
        val current = lyricsCloudRevealProgress(0.2f, distanceFromCurrent = 0f, isCurrent = true)
        val nearby = lyricsCloudRevealProgress(0.2f, distanceFromCurrent = 1f, isCurrent = false)
        val distant = lyricsCloudRevealProgress(0.2f, distanceFromCurrent = 6f, isCurrent = false)

        assertEquals(0.2f, current, 0.0001f)
        assertTrue(current > nearby)
        assertTrue(nearby > distant)
    }

    @Test
    fun measureOrderPrefersCurrentThenNearby() {
        assertEquals(listOf(3, 2, 4, 1, 5, 0, 6), lyricsCloudMeasureOrder(7, currentIndex = 3))
        assertEquals(listOf(0, 1, 2), lyricsCloudMeasureOrder(3, currentIndex = 0))
        assertEquals(emptyList<Int>(), lyricsCloudMeasureOrder(0, currentIndex = 0))
    }

    @Test
    fun approximateLayoutSizesMatchPxOverUnitSpace() {
        val rows = listOf(
            listOf(LyricDisplayRows.DisplayRow("你好", 0, 2, 0)),
            listOf(LyricDisplayRows.DisplayRow("世界啊", 0, 3, 0)),
        )
        val fontSizes = listOf(18, 22)
        val density = androidx.compose.ui.unit.Density(density = 2.75f)
        val unit = 400f
        val sizes = approximateLyricsCloudLayoutSizes(rows, fontSizes, unit, density)
        // requiredWidth uses node.width * unit ≈ charCount * fontPx
        val font0 = with(density) { 18.sp.toPx() }
        val expectedWidth0 = (2 * font0 * 0.95f + 4f) / unit
        assertEquals(expectedWidth0, sizes[0].width, 0.0001f)
        val nodesFirst = buildLyricsCloudLayout(sizes, seed = 42)
        val nodesSecond = buildLyricsCloudLayout(sizes, seed = 42)
        assertEquals(nodesFirst, nodesSecond)
    }

    @Test
    fun bilingualApproximateHeightUsesSmallerTranslationFont() {
        val rows = listOf(
            listOf(
                LyricDisplayRows.DisplayRow("你好", 0, 2, 0),
                LyricDisplayRows.DisplayRow("hello", 0, 5, 1),
            ),
        )
        val density = androidx.compose.ui.unit.Density(density = 2f)
        val unit = 400f
        val sizes = approximateLyricsCloudLayoutSizes(rows, fontSizes = listOf(20), unit, density)
        val mainPx = with(density) { 20.sp.toPx() }
        val translationPx = with(density) { 17.sp.toPx() }
        val expectedHeight = (mainPx * 1.45f + translationPx * 1.45f) / unit
        assertEquals(expectedHeight, sizes[0].height, 0.0001f)
    }

    @Test
    fun sizesFromMeasuredRowsDivideByUnit() {
        val rowsPx = listOf(
            listOf(LyricsCloudMeasuredRow(width = 200, height = 40)),
        )
        val sizes = lyricsCloudSizesFromMeasuredRows(rowsPx, unit = 400f)
        assertEquals(0.5f, sizes[0].width, 0.0001f)
        assertEquals(0.1f, sizes[0].height, 0.0001f)
    }

    @Test
    fun verticalSplitIsLimitedToStandardAndCoverFlowThemes() {
        assertTrue(lyricsCloudUsesVerticalSplit(PlayerCoverFlowMode.STANDARD))
        assertTrue(lyricsCloudUsesVerticalSplit(PlayerCoverFlowMode.PAUSE_FOLD))
        assertTrue(lyricsCloudUsesVerticalSplit(PlayerCoverFlowMode.RETRO_3D))
        assertFalse(lyricsCloudUsesVerticalSplit(PlayerCoverFlowMode.CUSTOM_STANDARD))
        assertFalse(lyricsCloudUsesVerticalSplit(PlayerCoverFlowMode.PARTICLE_COVER))
        assertFalse(lyricsCloudUsesVerticalSplit(PlayerCoverFlowMode.PHOTO_STACK))
    }

    @Test
    fun customStandardUsesHorizontalClassicPageOnlyWhenCloudIsUnavailable() {
        assertTrue(usesHorizontalClassicLyricsPage(PlayerCoverFlowMode.CUSTOM_STANDARD, false))
        assertFalse(usesHorizontalClassicLyricsPage(PlayerCoverFlowMode.CUSTOM_STANDARD, true))
        assertFalse(usesHorizontalClassicLyricsPage(PlayerCoverFlowMode.STANDARD, false))
    }
}
