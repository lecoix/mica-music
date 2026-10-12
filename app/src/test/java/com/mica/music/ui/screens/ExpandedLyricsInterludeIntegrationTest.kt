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
import androidx.compose.ui.test.junit4.createComposeRule
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

/** Uses the *actual* ExpandedLyricsPanel and real LyricLineBlock, not a fake LazyColumn. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpandedLyricsInterludeIntegrationTest {
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
        LyricLine(74_000, "测试尾声", endTimeMs = 82_000),
    )

    private fun line(trace: ExpandedLyricsMotionTrace, index: Int): ExpandedLyricsRowTrace? =
        trace.items.firstOrNull { it.key.startsWith("line-$index-") }

    private fun runProductionWindow(
        startMs: Int,
        endMs: Int,
        nextIndex: Int,
        anchor: Int = 25,
        frameAdvanceMs: Long = 100,
        freezeFollow: Boolean = false,
    ): List<ExpandedLyricsMotionTrace> {
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
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        lyricsFontSizeSp = 34,
                        lyricsLineSpacingDp = 18,
                        lyricsCurrentLinePositionPercent = anchor,
                        debugMotionTrace = { frames.add(it) },
                        debugFreezeFollowAfterSnap = freezeFollow,
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(900)
        rule.waitForIdle()
        // Reproduce the exact QA entry: 10s page -> 13s (or 49s) seek -> PLAY.
        rule.runOnIdle { position = startMs; revision++ }
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
        for (ms in startMs + 100..endMs step 100) {
            rule.runOnIdle { position = ms }
            rule.mainClock.advanceTimeBy(frameAdvanceMs)
            rule.waitForIdle()
        }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val take = frames.filter { it.positionMs >= startMs }
        println("EXPANDED_PANEL_FRAMES start=$startMs end=$endMs count=${take.size}")
        take.filter {
            it.positionMs in (endMs-700)..endMs && it.frameNanos % 32000000L == 0L
        }.takeLast(36).forEach {
            val row = line(it, nextIndex)
            println("PANEL_MEASURE t=${it.positionMs} f=${it.frameNanos} playing=${it.playing} i=${it.interludeKey != null} " +
                "first=${it.firstVisibleIndex}:${it.firstVisibleScrollOffset} " +
                "listRoot=${it.listRootYPx} follow=${it.followConsumedPx} insertion=${it.interludeConsumedPx} " +
                "nextRaw=${row?.measuredViewportYPx} nextY=${row?.visualRootYPx} " +
                "owned=${row?.ownedTranslationPx} lag=${row?.staggerTranslationPx}")
        }
        assertTrue("Actual next lyric never measured", take.any { line(it, nextIndex) != null })
        return take
    }

    @Test fun diagnoseAt17WhileAutoplaying() {
        val frames = runProductionWindow(13_000, 17_400, 4)
        val a = frames.lastOrNull { it.positionMs <= 16_900 && line(it,4) != null }!!
        val b = frames.lastOrNull { it.positionMs >= 17_300 && line(it,4) != null }!!
        val delta = line(b,4)!!.visualRootYPx - line(a,4)!!.visualRootYPx
        println("PANEL_17_DELTA=$delta followDelta=${b.followConsumedPx-a.followConsumedPx} insertionDelta=${b.interludeConsumedPx-a.interludeConsumedPx} beforeY=${line(a,4)?.visualRootYPx} afterY=${line(b,4)?.visualRootYPx}")
        assertTrue("Interlude drift without natural scroll should be < 8px: $delta",
            abs(delta + (b.followConsumedPx - a.followConsumedPx)) < 8f)
        assertTransitionDriftBelowEight(frames, 17_000, 4, "first-opening")
    }

    private fun assertTransitionDriftBelowEight(
        frames: List<ExpandedLyricsMotionTrace>,
        transitionMs: Int,
        nextIndex: Int,
        label: String,
    ) {
        val before = frames.lastOrNull {
            it.positionMs in (transitionMs - 150) until transitionMs && line(it, nextIndex) != null
        } ?: error("Missing pre-transition next-line geometry for $label")
        val after = frames.filter {
            it.positionMs in transitionMs..(transitionMs + 400) && line(it, nextIndex) != null
        }
        assertTrue("No post-transition geometry for $label", after.isNotEmpty())
        val origin = line(before, nextIndex)!!.visualRootYPx
        val drifts = after.map {
            val expectedNaturalScroll = it.followConsumedPx - before.followConsumedPx
            (line(it, nextIndex)!!.visualRootYPx - origin) + expectedNaturalScroll
        }
        val maxDrift = drifts.maxOf { abs(it) }
        println("PANEL_TRANSITION $label maxResidualPx=$maxDrift beforeY=$origin " +
            "afterY=${line(after.last(), nextIndex)?.visualRootYPx} " +
            "naturalFollow=${after.last().followConsumedPx-before.followConsumedPx} " +
            "interludeCorrection=${after.last().interludeConsumedPx-before.interludeConsumedPx}")
        assertTrue("$label additional per-frame next-line drift $maxDrift >=8 device px", maxDrift < 8f)
    }

    @Test fun interludeOpeningAt55PreservesNextLineAgainstNaturalScroll() {
        val frames = runProductionWindow(49_000, 55_400, 8)
        assertTransitionDriftBelowEight(frames, 55_000, 8, "second-opening")
    }

    @Test fun firstGapRemainsStableThroughout17To29() {
        val frames = runProductionWindow(17_500, 28_900, 4)
        // The initial seek into an already-running interlude also settles a
        // pre-existing current-line spring; the 17s opening itself is measured
        // independently above. Cover the steady 19→29s window here.
        assertInterludeGapStable(frames, 4, "17-to-29-gap", settledAfterMs = 19_000)
    }

    @Test fun secondGapRemainsStableThroughout55To68() {
        val frames = runProductionWindow(55_500, 67_900, 8)
        // A seek to 55.5s is allowed to snap instantly to the already-live
        // interlude; measure the gap only after this seek transaction settles.
        // The separate seek-rebase test below guards against a delayed jump.
        assertInterludeGapStable(frames, 8, "55-to-68-gap", settledAfterMs = 56_500)
    }

    @Test fun seekIntoActiveSecondInterludeSettlesBeforeLaterPlaybackAdvances() {
        val frames = runProductionWindow(55_500, 56_700, 8)
        // The seek can snap within the first 700ms at the same playback
        // timestamp, but must not leave another large relocation queued.
        val seekEnd = frames.lastOrNull { it.positionMs == 55_500 && line(it, 8) != null }
            ?: error("Missing actual next-line geometry at end of seek transaction")
        val later = frames.firstOrNull { it.positionMs >= 56_500 && line(it, 8) != null }
            ?: error("Missing actual next-line geometry after seek")
        val unexplainedMotion = abs(
            line(later, 8)!!.visualRootYPx - line(seekEnd, 8)!!.visualRootYPx +
                later.followConsumedPx - seekEnd.followConsumedPx,
        )
        println("PANEL_SEEK_SECOND_GAP residualAfterSeekPx=$unexplainedMotion " +
            "startIndex=${seekEnd.firstVisibleIndex} laterIndex=${later.firstVisibleIndex}")
        assertTrue("Seek must not leave large delayed interlude drift: $unexplainedMotion",
            unexplainedMotion < 16f)
    }

    @Test fun exitAt29RunsNaturalFollowAndDropsExpiredInterlude() {
        val frames = runProductionWindow(28_000, 29_400, 4)
        assertNaturalExit(frames, 29_000, 4, "29-exit")
    }

    @Test fun exitAt68RunsNaturalFollowAndDropsExpiredInterlude() {
        val frames = runProductionWindow(67_000, 68_400, 8)
        assertNaturalExit(frames, 68_000, 8, "68-exit")
    }

    @Test fun firstOpeningAtAnchor60StillKeepsLowerNextLineStable() {
        val frames = runProductionWindow(13_000, 17_400, 4, anchor = 60)
        assertTransitionDriftBelowEight(frames, 17_000, 4, "first-opening-anchor60")
    }

    private fun assertInterludeGapStable(
        frames: List<ExpandedLyricsMotionTrace>,
        nextIndex: Int,
        label: String,
        settledAfterMs: Int = Int.MIN_VALUE,
    ) {
        val visible = frames.filter {
            it.positionMs >= settledAfterMs && it.interludeKey != null &&
                line(it, nextIndex) != null
        }
        assertTrue("No gap frames $label", visible.size > 5)
        val begin = visible.first()
        val baseY = line(begin, nextIndex)!!.visualRootYPx
        val maxResidual = visible.maxOf {
            abs(
                (line(it, nextIndex)!!.visualRootYPx - baseY) +
                    (it.followConsumedPx - begin.followConsumedPx)
            )
        }
        val worst = visible.maxByOrNull {
            abs((line(it, nextIndex)!!.visualRootYPx - baseY) +
                (it.followConsumedPx - begin.followConsumedPx))
        }!!
        val end = visible.last()
        println("PANEL_GAP_DETAIL $label beginT=${begin.positionMs} beginY=$baseY beginFollow=${begin.followConsumedPx} " +
            "worstT=${worst.positionMs} worstY=${line(worst, nextIndex)?.visualRootYPx} worstFollow=${worst.followConsumedPx} " +
            "endT=${end.positionMs} endY=${line(end,nextIndex)?.visualRootYPx} endFollow=${end.followConsumedPx} " +
            "beginScroll=${begin.firstVisibleIndex}:${begin.firstVisibleScrollOffset} " +
            "worstScroll=${worst.firstVisibleIndex}:${worst.firstVisibleScrollOffset}")
        println("PANEL_GAP $label maxAdditionalPx=$maxResidual frames=${visible.size}")
        assertTrue("$label drift >8px during gap: $maxResidual", maxResidual < 8f)
    }

    private fun assertNaturalExit(
        frames: List<ExpandedLyricsMotionTrace>,
        momentMs: Int,
        nextIndex: Int,
        label: String,
    ) {
        val before = frames.last { it.positionMs < momentMs && line(it,nextIndex) != null }
        val after = frames.last { it.positionMs >= momentMs + 300 && line(it,nextIndex) != null }
        val follow = after.followConsumedPx - before.followConsumedPx
        println("PANEL_EXIT $label followPx=$follow nextTopBefore=${line(before,nextIndex)?.visualRootYPx} " +
            "nextTopAfter=${line(after,nextIndex)?.visualRootYPx} " +
            "heightBefore=${line(before,nextIndex)?.measuredHeightPx} " +
            "heightAfter=${line(after,nextIndex)?.measuredHeightPx}")
        assertTrue("$label follow did not run after next line became active", follow > 20f)
        assertTrue("$label interlude still active after the next line start", after.interludeKey == null)
    }

    @Test fun firstOpeningWithFixedScrollBaseline() {
        val frames = runProductionWindow(13_000, 17_400, 4, freezeFollow = true)
        assertTransitionDriftBelowEight(frames, 17_000, 4, "first-opening-fixed-scroll")
    }

    @Test fun overlappingFollowUsesSameNaturalScrollBaseline() {
        val frames = runProductionWindow(
            13_000, 17_400, 4, frameAdvanceMs = 16,
        )
        assertTransitionDriftBelowEight(frames, 17_000, 4, "first-opening-fast-position")
    }
}
