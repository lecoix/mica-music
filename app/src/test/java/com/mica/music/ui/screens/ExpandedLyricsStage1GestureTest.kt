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

/**
 * P0 Behavior Contract / X01 C03:
 * With a real ExpandedLyricsPanel, a user drag during a paused interlude must move
 * both the preceding and following rows as part of the same LazyColumn.
 *
 * Intentionally does not depend on OwnedLyricsPositionMotion internals, so a later
 * architectural replacement cannot silently invalidate the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpandedLyricsStage1GestureTest {
    @get:Rule val rule = createComposeRule()

    private val lyrics = listOf(
        LyricLine(1_000, "第一句，正常短歌词。", endTimeMs = 3_900),
        LyricLine(5_000, "第二句，逐行滚动。", endTimeMs = 8_800),
        LyricLine(9_000, "第三句，当前行加粗会改变测量高度。", endTimeMs = 12_000),
        LyricLine(13_000, "间奏前最后一句", endTimeMs = 17_000),
        LyricLine(29_000, "间奏之后，下方歌词应保持稳定", endTimeMs = 34_000),
        LyricLine(35_000, "这一句刻意很长很长，用来验证歌词变为当前行时由于加粗和自动换行产生的行高变化，也要保持平滑且不能让离场歌词向下飞出屏幕。", endTimeMs = 42_000),
        LyricLine(43_000, "第七句，继续滚动。", endTimeMs = 48_000),
        LyricLine(49_000, "第二次间奏前一句", endTimeMs = 55_000),
        LyricLine(68_000, "第二次间奏后一句歌词", endTimeMs = 73_000),
    )
    private fun lyric(trace: ExpandedLyricsMotionTrace, index: Int): ExpandedLyricsRowTrace? =
        trace.items.firstOrNull { it.key.startsWith("line-$index-") }

    @Test fun pausedInterludeDrag_movesRowsOnBothSidesOfInterludeTogether() {
        val frames = mutableListOf<ExpandedLyricsMotionTrace>()
        var position by mutableIntStateOf(10_000)
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
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("stage1-lyrics"),
                        lyricsFontSizeSp = 34,
                        lyricsLineSpacingDp = 18,
                        lyricsCurrentLinePositionPercent = 60,
                        debugMotionTrace = { frames.add(it) },
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(900)
        rule.waitForIdle()
        rule.runOnIdle { position = 13_000; revision++ }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        // Do not jump directly to 18s; reproduces insertion while following the song.
        for (ms in 13_100..18_000 step 100) {
            rule.runOnIdle { position = ms }
            rule.mainClock.advanceTimeBy(100)
            rule.waitForIdle()
        }
        rule.runOnIdle { playing = false }
        rule.mainClock.advanceTimeBy(900)
        rule.waitForIdle()
        val before = rule.runOnIdle {
            frames.lastOrNull { it.interludeKey != null && lyric(it, 3) != null && lyric(it, 4) != null }
                ?: error("Need pre-drag geometry for both sides of interlude")
        }
        rule.onNodeWithTag("stage1-lyrics").performTouchInput {
            swipe(
                start = Offset(centerX, height * 0.80f),
                end = Offset(centerX, height * 0.54f),
                durationMillis = 550,
            )
        }
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
        val after = rule.runOnIdle {
            frames.lastOrNull { it.interludeKey != null && lyric(it, 3) != null && lyric(it, 4) != null }
                ?: error("Need post-drag geometry for both sides of interlude")
        }
        val above = lyric(after, 3)!!.visualRootYPx - lyric(before, 3)!!.visualRootYPx
        val below = lyric(after, 4)!!.visualRootYPx - lyric(before, 4)!!.visualRootYPx
        val mismatch = abs(above - below)
        println("B3_STAGE1_GESTURE beforeVisible=${before.firstVisibleIndex}:${before.firstVisibleScrollOffset} afterVisible=${after.firstVisibleIndex}:${after.firstVisibleScrollOffset} above=$above below=$below mismatch=$mismatch")
        assertTrue("Synthetic touch must scroll actual list, not just an empty surface: above=$above", above < -36f)
        assertTrue("Interlude must not immobilize following lyrics during user drag: above=$above below=$below mismatch=$mismatch", mismatch < 12f)
    }
}
