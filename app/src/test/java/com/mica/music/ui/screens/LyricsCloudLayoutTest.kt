package com.mica.music.ui.screens

import com.mica.music.data.LyricCue
import com.mica.music.data.LyricLine
import com.mica.music.data.PlayerCoverFlowMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsCloudLayoutTest {
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
    fun verticalSplitIsLimitedToStandardAndCoverFlowThemes() {
        assertTrue(lyricsCloudUsesVerticalSplit(PlayerCoverFlowMode.STANDARD))
        assertTrue(lyricsCloudUsesVerticalSplit(PlayerCoverFlowMode.PAUSE_FOLD))
        assertTrue(lyricsCloudUsesVerticalSplit(PlayerCoverFlowMode.RETRO_3D))
        assertFalse(lyricsCloudUsesVerticalSplit(PlayerCoverFlowMode.PARTICLE_COVER))
        assertFalse(lyricsCloudUsesVerticalSplit(PlayerCoverFlowMode.PHOTO_STACK))
    }
}
