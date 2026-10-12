package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * D3 Behavior Contract: drive the production clock through real Compose UI input.
 * A direct test-thread state mutation can mask whether frame-clock recomposition
 * was committed, so button interactions verify the user-visible state change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsInterludePauseContractTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pauseFreezesTheActualDotsClockAndResumeContinuesFromFrozenFrame() {
        var displayedMs = 0
        var observedPlaying = false
        rule.mainClock.autoAdvance = false
        rule.setContent {
            var playing by remember { mutableStateOf(true) }
            observedPlaying = playing
            displayedMs = rememberClassicInterludePositionMs(17_000, playing)
            Column {
                Button(onClick = { playing = !playing }, modifier = Modifier.testTag("toggle-clock")) {
                    Text(if (playing) "Playing" else "Paused")
                }
                Text("Phase:$displayedMs")
            }
        }
        rule.mainClock.advanceTimeBy(850)
        rule.waitForIdle()
        val advanced = displayedMs
        assertTrue("clock must move when playing: $advanced", advanced > 17_500)

        rule.onNodeWithTag("toggle-clock").performClick()
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        val frozen = displayedMs
        assertTrue("UI pause toggle must be observed", !observedPlaying)
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        assertEquals("pause should hold phase", frozen, displayedMs)

        rule.onNodeWithTag("toggle-clock").performClick()
        rule.mainClock.advanceTimeBy(320)
        rule.waitForIdle()
        println("D3_VISUAL_PHASE playing=$observedPlaying advanced=$advanced frozen=$frozen resumed=$displayedMs")
        assertTrue("UI resume toggle must be observed", observedPlaying)
        assertTrue("resume should continue after last phase", displayedMs > frozen + 180)
    }

    @Test fun explicitSeekWhilePausedRebasesClockToNewTime() {
        var displayedMs = 0
        var observedPositionMs = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            var position by remember { mutableIntStateOf(17_000) }
            observedPositionMs = position
            displayedMs = rememberClassicInterludePositionMs(position, false)
            Column {
                Button(onClick = { position = 22_000 }, modifier = Modifier.testTag("seek-clock")) {
                    Text("Seek 22s")
                }
                Text("Phase:$displayedMs")
            }
        }
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithTag("seek-clock").performClick()
        rule.mainClock.advanceTimeBy(50)
        rule.waitForIdle()
        println("D3_VISUAL_SEEK state=$observedPositionMs clock=$displayedMs")
        assertEquals("UI seek must be observed", 22_000, observedPositionMs)
        assertEquals("paused seek should rebase clock", 22_000, displayedMs)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("paused clock may not advance", 22_000, displayedMs)
    }
}
