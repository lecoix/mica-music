package com.mica.music.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.mica.music.ui.theme.MicaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackTuningRulerInputTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dragUsesLatestCallbackAfterRecomposition() {
        var callbackVersion by mutableIntStateOf(1)
        val receivedVersions = mutableListOf<Int>()
        composeRule.setContent {
            val version = callbackVersion
            MicaTheme {
                PlaybackTuningRuler(
                    value = 1f,
                    valueRange = 0.5f..2f,
                    step = 0.05f,
                    majorStep = 0.5f,
                    tickLabel = { it.toString() },
                    onValueChange = {
                        receivedVersions += version
                        if (version == 1) callbackVersion = 2
                    },
                    modifier = Modifier.testTag("tuning-ruler"),
                )
            }
        }

        composeRule.onNodeWithTag("tuning-ruler").performTouchInput {
            swipe(
                start = centerLeft,
                end = centerRight,
                durationMillis = 500,
            )
        }

        composeRule.runOnIdle {
            assertEquals(1, receivedVersions.first())
            assertEquals(2, receivedVersions.last())
        }
    }
}
