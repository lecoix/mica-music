package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.mica.music.data.LyricLine
import com.mica.music.data.renderStateAt
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.lightPlayerContentColors
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** D1 Behavior Contract: no song-follow stealing during a 4s browsing hold. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpandedLyricsBrowseRecoveryTest {
    @get:Rule val rule = createComposeRule()

    private val lyrics = listOf(
        LyricLine(1_000, "第一句，短歌词", endTimeMs = 3_000),
        LyricLine(4_000, "第二句，短歌词", endTimeMs = 7_000),
        LyricLine(8_000, "第三句，短歌词", endTimeMs = 11_000),
        LyricLine(12_000, "第四句，短歌词", endTimeMs = 15_000),
        LyricLine(16_000, "第五句，短歌词", endTimeMs = 20_000),
        LyricLine(21_000, "第六句，短歌词", endTimeMs = 25_000),
        LyricLine(26_000, "第七句，短歌词", endTimeMs = 30_000),
        LyricLine(31_000, "第八句，短歌词", endTimeMs = 35_000),
        LyricLine(36_000, "第九句，短歌词", endTimeMs = 40_000),
        LyricLine(41_000, "第十句，短歌词", endTimeMs = 45_000),
        LyricLine(46_000, "第十一句，短歌词", endTimeMs = 50_000),
    )
    private fun row(trace: ExpandedLyricsMotionTrace, index: Int) =
        trace.items.firstOrNull { it.key.startsWith("line-$index-") }

    @Test fun gestureDoesNotRelinquishAuthorityBeforeFourSecondsAndThenFollowsCurrent() {
        val frames = mutableListOf<ExpandedLyricsMotionTrace>()
        var position by mutableIntStateOf(31_000)
        var revision by mutableLongStateOf(1L)
        var playing by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MicaTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(154.dp))
                    ExpandedLyricsPanel(
                        renderState = lyrics.renderStateAt(position).copy(positionRevision = revision),
                        isPlaying = playing,
                        colors = lightPlayerContentColors(),
                        onLineClick = {},
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("browse-lyrics"),
                        lyricsFontSizeSp = 34,
                        lyricsLineSpacingDp = 18,
                        lyricsCurrentLinePositionPercent = 60,
                        debugMotionTrace = { frames.add(it) },
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(1300)
        rule.waitForIdle()
        val origin = rule.runOnIdle {
            frames.lastOrNull { row(it, 7) != null }?.let { row(it, 7)!!.visualRootYPx }
                ?: error("missing current lyric origin")
        }
        // A real swipe, slow enough to avoid an exaggerated fling.
        rule.onNodeWithTag("browse-lyrics").performTouchInput {
            swipe(
                start = Offset(centerX, height * 0.74f),
                end = Offset(centerX, height * 0.53f),
                durationMillis = 900,
            )
        }
        rule.mainClock.advanceTimeBy(850)
        rule.waitForIdle()
        val browsed = rule.runOnIdle {
            frames.lastOrNull { row(it, 7) != null }?.let { row(it, 7)!!.visualRootYPx }
                ?: error("missing moved current lyric")
        }
        assertTrue("Drag must move actual current lyric $origin -> $browsed",
            abs(browsed - origin) > 60f)

        // Under 4s total idle even if the fling ended immediately after lifting.
        rule.mainClock.advanceTimeBy(2800)
        rule.waitForIdle()
        val held = rule.runOnIdle {
            frames.lastOrNull { row(it, 7) != null }?.let { row(it, 7)!!.visualRootYPx }
                ?: error("browsed lyric vanished before recovery")
        }
        println("B3_D1_HOLD origin=$origin browsed=$browsed held=$held")
        assertTrue("Must keep browsing location before 4s: $browsed -> $held",
            abs(held - browsed) < 12f)

        rule.mainClock.advanceTimeBy(1800)
        rule.waitForIdle()
        val recovered = rule.runOnIdle {
            frames.lastOrNull { row(it, 7) != null }?.let { row(it, 7)!!.visualRootYPx }
                ?: error("current lyric not visible after recovery")
        }
        println("B3_D1_RECOVER origin=$origin recovered=$recovered")
        assertTrue("Must return to current lyric after idle 4s: $origin -> $recovered",
            abs(recovered - origin) < 32f)
    }
}
