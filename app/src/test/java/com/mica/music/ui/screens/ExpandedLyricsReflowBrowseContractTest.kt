package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * D4 Behavior Contract: a reflow while browsing keeps the reading line and
 * the already-running 4-second idle timer, rather than jumping back or
 * restarting the timer from the font/anchor change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpandedLyricsReflowBrowseContractTest {
    @get:Rule val rule = createComposeRule()

    private val lyrics = (0..18).map { index ->
        val start = index * 5_000 + 1_000
        LyricLine(start, "测试重排第${index}行歌词，在不同字号时显示稳定。", endTimeMs = start + 3_900)
    }

    private fun yInPanel(trace: ExpandedLyricsMotionTrace, index: Int): Float? =
        trace.items.firstOrNull { it.key.startsWith("line-$index-") }
            ?.let { it.visualRootYPx - trace.listRootYPx }

    @Test fun resizeFontAndMoveCurrentLineAnchorPreserveBrowsingAndRemainingIdleTime() {
        val frames = mutableListOf<ExpandedLyricsMotionTrace>()
        var seenFont = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            var fontSize by remember { mutableIntStateOf(34) }
            var anchorPercent by remember { mutableIntStateOf(60) }
            seenFont = fontSize
            MicaTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize()) {
                    Button(
                        onClick = {
                            fontSize = 48
                            anchorPercent = 25
                        },
                        modifier = Modifier.testTag("reflow-preferences"),
                    ) { Text("Resize") }
                    Box(Modifier.fillMaxWidth().height(100.dp))
                    ExpandedLyricsPanel(
                        renderState = lyrics.renderStateAt(41_000),
                        isPlaying = false,
                        colors = lightPlayerContentColors(),
                        onLineClick = {},
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("lyrics-scroll"),
                        lyricsFontSizeSp = fontSize,
                        lyricsLineSpacingDp = 18,
                        lyricsCurrentLinePositionPercent = anchorPercent,
                        debugMotionTrace = { frames.add(it) },
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_200)
        rule.waitForIdle()
        // B-01: first non-local snap must land on the actual on-screen
        // 60% anchor before revealing lyrics, even with Lazy's content padding.
        val opening = rule.runOnIdle { frames.last() }
        println("B3_INITIAL_TRACE " + frames.takeLast(12).joinToString(" | ") { frame ->
            "t=${frame.frameNanos} first=${frame.firstVisibleIndex}:${frame.firstVisibleScrollOffset} viewport=${frame.viewportPhysicalHeightPx} limits=${frame.viewportStartOffsetPx}..${frame.viewportEndOffsetPx} padding=${frame.beforeContentPaddingPx}/${frame.afterContentPaddingPx} follow=${frame.followConsumedPx} mode=${frame.followMode} target=${frame.followTargetIndex} delta=${frame.followDeltaPx} items=" +
                frame.items.joinToString(",") { "${it.index}:${(it.visualRootYPx - frame.listRootYPx).toInt()}(${it.measuredHeightPx})" }
        })
        val initialCurrent = opening.items.firstOrNull { it.key.startsWith("line-8-") }
            ?: error("Initial playback line must be measured")
        val initialCenter = initialCurrent.visualRootYPx - opening.listRootYPx +
            initialCurrent.measuredHeightPx / 2f
        val initialAnchor = opening.viewportPhysicalHeightPx * 0.60f
        println("B3_D4_INITIAL center=$initialCenter anchor=$initialAnchor")
        assertTrue("Initial snap must align with 60% physical viewport",
            abs(initialCenter - initialAnchor) < 48f)
        val initial = rule.runOnIdle {
            frames.lastOrNull { yInPanel(it, 8) != null }?.let { yInPanel(it, 8)!! }
                ?: error("current line not visible at initial snap")
        }
        rule.onNodeWithTag("lyrics-scroll").performTouchInput {
            swipe(
                start = Offset(centerX, height * 0.72f),
                end = Offset(centerX, height * 0.52f),
                durationMillis = 900,
            )
        }
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
        val browsing = rule.runOnIdle {
            frames.lastOrNull { yInPanel(it, 8) != null }?.let { yInPanel(it, 8)!! }
                ?: error("browsed line not visible")
        }
        assertTrue("Swipe should move line away from current anchor", abs(browsing - initial) > 70f)
        rule.mainClock.advanceTimeBy(2_600)
        rule.waitForIdle()
        // Use an actual *visible reading line* instead of the playback line,
        // which may legitimately have moved above the visible viewport.
        val (readingIndex, readingY) = rule.runOnIdle {
            val frame = frames.last()
            val readable = frame.items
                .filter { it.key.startsWith("line-") }
                .map { it.index to (it.visualRootYPx - frame.listRootYPx) }
                .filter { (_, y) -> y in 100f..1600f }
            println("B3_D4_VISIBLE " + frame.items.joinToString { "${it.index}:${(it.visualRootYPx - frame.listRootYPx).toInt()}" })
            readable.minByOrNull { (_, y) -> abs(y - 750f) }
                ?: error("No visible reading line to anchor reflow")
        }
        rule.onNodeWithTag("reflow-preferences").performClick()
        rule.mainClock.advanceTimeBy(180)
        rule.waitForIdle()
        assertTrue("Changed font must be applied to real Compose UI", seenFont == 48)
        val reflowed = rule.runOnIdle {
            frames.lastOrNull { yInPanel(it, readingIndex) != null }
                ?.let { yInPanel(it, readingIndex)!! }
                ?: error("reading line $readingIndex lost after reflow")
        }
        println("B3_D4_REFLOW currentInitial=$initial currentBrowse=$browsing readingIndex=$readingIndex readBefore=$readingY reflow=$reflowed")
        assertTrue("Reading line should stay near its previous viewport position",
            abs(reflowed - readingY) < 140f)

        rule.mainClock.advanceTimeBy(250)
        rule.waitForIdle()
        val held = rule.runOnIdle {
            frames.lastOrNull { yInPanel(it, readingIndex) != null }
                ?.let { yInPanel(it, readingIndex)!! }
                ?: error("reading line disappeared during remaining browsing time")
        }
        assertTrue("D4 must preserve the rest of the 4s timer", abs(held - reflowed) < 12f)
        rule.mainClock.advanceTimeBy(2_700)
        rule.waitForIdle()
        val recovered = rule.runOnIdle {
            frames.lastOrNull { yInPanel(it, 8) != null }?.let { yInPanel(it, 8)!! }
                ?: error("following current line not visible after deadline")
        }
        val last = rule.runOnIdle { frames.last() }
        val currentRow = last.items.firstOrNull { it.key.startsWith("line-8-") }
            ?: error("Playback line must be measured after return to FOLLOWING")
        val visualCenter = currentRow.visualRootYPx - last.listRootYPx + currentRow.measuredHeightPx / 2f
        // Debug visualRootYPx now includes Lazy's before-content padding,
        // so compare to a physical viewport anchor, not Lazy item offsets.
        val target = last.viewportPhysicalHeightPx * 0.25f
        println("B3_D4_RECOVER held=$held currentTop=$recovered center=$visualCenter anchor=$target")
        println("B3_D4_FINAL " + frames.takeLast(5).joinToString(" | ") { f ->
            "mode=${f.scrollMode} scrolling=${f.scrollInProgress} idx=${f.firstVisibleIndex} follow=${f.followConsumedPx} decision=${f.followMode}/target${f.followTargetIndex}/delta${f.followDeltaPx} cur8=${yInPanel(f,8)} read=${yInPanel(f,readingIndex)}"
        })
        assertTrue("Recovery must finish in FOLLOWING", last.scrollMode == "FOLLOWING")
        // The lyric anchor is the LINE CENTER, not the top (a long wrapped
        // lyric may correctly start above the viewport).
        assertTrue("Current lyric visual center should land on the latest 25% anchor",
            abs(visualCenter - target) < 48f)
    }

    @Test fun viewportHeightChangeKeepsReadingLineDuringFourSecondHold() {
        val frames = mutableListOf<ExpandedLyricsMotionTrace>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            var panelHeightDp by remember { mutableIntStateOf(650) }
            MicaTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize()) {
                    Button(
                        onClick = { panelHeightDp = 500 },
                        modifier = Modifier.testTag("resize-viewport"),
                    ) { Text("Resize viewport") }
                    ExpandedLyricsPanel(
                        renderState = lyrics.renderStateAt(41_000),
                        isPlaying = false,
                        colors = lightPlayerContentColors(),
                        onLineClick = {},
                        modifier = Modifier.fillMaxWidth().height(panelHeightDp.dp)
                            .testTag("resize-scroll"),
                        lyricsFontSizeSp = 34,
                        lyricsLineSpacingDp = 18,
                        lyricsCurrentLinePositionPercent = 60,
                        debugMotionTrace = { frames.add(it) },
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_200)
        rule.waitForIdle()
        rule.onNodeWithTag("resize-scroll").performTouchInput {
            swipe(
                start = Offset(centerX, height * 0.72f),
                end = Offset(centerX, height * 0.52f),
                durationMillis = 900,
            )
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val (readingKey, beforeY, gestureMode) = rule.runOnIdle {
            val frame = frames.last()
            val reading = frame.items
                .filter { it.key.startsWith("line-") }
                .map { it.key to (it.visualRootYPx - frame.listRootYPx) }
                .filter { (_, y) -> y in 100f..1150f }
                .minByOrNull { (_, y) -> abs(y - 650f) }
                ?: error("No visible reading line before viewport resize")
            Triple(reading.first, reading.second, frame.scrollMode)
        }
        assertTrue("Viewport reflow test requires browsing hold", gestureMode == "BROWSING_HOLD")
        rule.onNodeWithTag("resize-viewport").performClick()
        rule.mainClock.advanceTimeBy(230)
        rule.waitForIdle()
        val after = rule.runOnIdle { frames.last() }
        val afterY = after.items.firstOrNull { it.key == readingKey }
            ?.let { it.visualRootYPx - after.listRootYPx }
            ?: error("Reading row not visible after viewport resize: $readingKey")
        println("B3_D4_VIEWPORT key=$readingKey before=$beforeY after=$afterY mode=${after.scrollMode}")
        assertTrue("Resizing the viewport must not relocate the reading line",
            abs(afterY - beforeY) < 48f)
        assertTrue("Viewport resize must not reset the browse timer",
            after.scrollMode == "BROWSING_HOLD")
        rule.mainClock.advanceTimeBy(3_700)
        rule.waitForIdle()
        val recovered = rule.runOnIdle { frames.last() }
        assertTrue("Remaining browse timer must return to FOLLOWING",
            recovered.scrollMode == "FOLLOWING")
        val currentRow = recovered.items.firstOrNull { it.key.startsWith("line-8-") }
            ?: error("Playback lyric must be visible after viewport resize recovery")
        val center = currentRow.visualRootYPx - recovered.listRootYPx +
            currentRow.measuredHeightPx / 2f
        assertTrue("Playback line must use new physical viewport height",
            abs(center - recovered.viewportPhysicalHeightPx * 0.60f) < 48f)
    }
}
