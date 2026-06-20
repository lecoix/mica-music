package com.mica.music.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
