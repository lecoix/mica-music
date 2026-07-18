package com.mica.music.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mica.music.data.TrackSkipDirection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
class DirectionalTrackWipeLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun transitionKeepsIncomingAndOutgoingContentAtFullSize() {
        val target = mutableStateOf("old")
        val measuredWidths = mutableStateMapOf<String, Int>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            Box(Modifier.size(width = 100.dp, height = 60.dp)) {
                DirectionalTrackWipe(
                    targetState = target.value,
                    contentKey = { it },
                    direction = TrackSkipDirection.TO_NEXT,
                    motionEnabled = true,
                    modifier = Modifier.fillMaxSize(),
                ) { state ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onSizeChanged { measuredWidths[state] = it.width },
                    )
                }
            }
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.runOnIdle { target.value = "new" }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(160L)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(100, measuredWidths["old"])
            assertEquals(100, measuredWidths["new"])
        }
    }
}
