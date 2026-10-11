package com.mica.music.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** D3: the real interlude rendering clock pauses in place, then resumes phase. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsInterludePauseContractTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pauseFreezesTheActualDotsClockAndResumeContinuesFromFrozenFrame() {
        var running by mutableStateOf(true)
        var position by mutableIntStateOf(17_000)
        var renderedMs = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            renderedMs = rememberClassicInterludePositionMs(position, running)
        }
        rule.mainClock.advanceTimeBy(850)
        rule.waitForIdle()
        val advanced = renderedMs
        assertTrue("Dot clock must advance while running $advanced", advanced > 17_500)

        rule.runOnIdle { running = false }
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        val frozen = renderedMs
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        assertEquals("Pause must freeze the same dot phase", frozen, renderedMs)

        rule.runOnIdle { running = true }
        rule.mainClock.advanceTimeBy(320)
        rule.waitForIdle()
        println("B3_D3_PHASE advanced=$advanced frozen=$frozen resumed=$renderedMs")
        assertTrue("Resume must advance from frozen state", renderedMs > frozen + 180)
        assertTrue("Resume must not restart at 17s", renderedMs >= frozen)
    }

    @Test fun explicitSeekWhilePausedRebasesTheClockWithoutPlayingOldPhase() {
        var running by mutableStateOf(false)
        var position by mutableIntStateOf(17_000)
        var renderedMs = 0
        rule.mainClock.autoAdvance = false
        rule.setContent { renderedMs = rememberClassicInterludePositionMs(position, running) }
        rule.mainClock.advanceTimeBy(50)
        rule.runOnIdle { position = 22_000 }
        rule.mainClock.advanceTimeBy(50)
        rule.waitForIdle()
        assertEquals(22_000, renderedMs)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(22_000, renderedMs)
    }
}
