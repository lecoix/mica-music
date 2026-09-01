package com.mica.music.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.rememberPlayerContentColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackProgressBarInputTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun standardProgressUsesUpdatedDurationForTouchMapping() {
        var valueRange by mutableStateOf(0f..100f)
        var target = -1f
        composeRule.setContent {
            MicaTheme {
                HiFiSeekBar(
                    value = 0f,
                    onValueChange = { target = it },
                    onValueChangeFinished = {},
                    valueRange = valueRange,
                    colors = rememberPlayerContentColors(),
                    modifier = Modifier.testTag("standard-progress"),
                )
            }
        }

        composeRule.onNodeWithTag("standard-progress").performTouchInput {
            click(Offset(width * 0.5f, center.y))
        }
        composeRule.runOnIdle { assertEquals(50f, target, 1f) }

        composeRule.runOnIdle { valueRange = 0f..200f }
        composeRule.onNodeWithTag("standard-progress").performTouchInput {
            click(Offset(width * 0.75f, center.y))
        }

        composeRule.runOnIdle { assertEquals(150f, target, 1f) }
    }

    @Test
    fun scaledProgressStillMapsTouchByWidth() {
        var target = -1f
        composeRule.setContent {
            MicaTheme {
                HiFiSeekBar(
                    value = 0f,
                    onValueChange = { target = it },
                    onValueChangeFinished = {},
                    valueRange = 0f..100f,
                    colors = rememberPlayerContentColors(),
                    visualScale = 2f,
                    modifier = Modifier.testTag("scaled-progress"),
                )
            }
        }

        composeRule.onNodeWithTag("scaled-progress").performTouchInput {
            click(Offset(width * 0.5f, center.y))
        }
        composeRule.runOnIdle { assertEquals(50f, target, 1f) }
    }

    @Test
    fun scaledDownProgressSectionIsHalfParentWidth() {
        composeRule.setContent {
            MicaTheme {
                Box(Modifier.width(200.dp)) {
                    PlayerProgressBarSection(
                        seekState = idleSeekState(),
                        colors = rememberPlayerContentColors(),
                        visualScale = 0.5f,
                        modifier = Modifier.testTag("narrow-progress"),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("narrow-progress").assertWidthIsEqualTo(100.dp)
    }

    @Test
    fun scaledUpProgressSectionDoesNotExceedParentWidth() {
        composeRule.setContent {
            MicaTheme {
                Box(Modifier.width(200.dp)) {
                    PlayerProgressBarSection(
                        seekState = idleSeekState(),
                        colors = rememberPlayerContentColors(),
                        visualScale = 2f,
                        modifier = Modifier.testTag("capped-progress"),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("capped-progress").assertWidthIsEqualTo(200.dp)
    }

    @Test
    fun coverEdgeProgressUsesUpdatedDurationForTouchMapping() {
        var valueRange by mutableStateOf(0f..100f)
        var target = -1f
        composeRule.setContent {
            CoverEdgeProgressBar(
                value = 0f,
                onValueChange = { target = it },
                onValueChangeFinished = {},
                valueRange = valueRange,
                progressColor = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.testTag("cover-edge-progress"),
            )
        }

        composeRule.onNodeWithTag("cover-edge-progress").performTouchInput {
            click(Offset(width * 0.5f, center.y))
        }
        composeRule.runOnIdle { assertEquals(50f, target, 1f) }

        composeRule.runOnIdle { valueRange = 0f..200f }
        composeRule.onNodeWithTag("cover-edge-progress").performTouchInput {
            click(Offset(width * 0.75f, center.y))
        }

        composeRule.runOnIdle { assertEquals(150f, target, 1f) }
    }
}

private fun idleSeekState() = PlaybackSeekState(
    sliderValue = 0f,
    displaySec = 0,
    totalSec = 100,
    valueRange = 0f..100f,
    onValueChange = {},
    onValueChangeFinished = {},
)
