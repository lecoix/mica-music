package com.mica.music.ui.components

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.MicaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharpPlayPauseButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pausedButtonHasAccessibleNameClickActionAndMinimumTouchTarget() {
        var clicks = 0
        composeRule.setContent {
            MicaTheme {
                SharpPlayPauseButton(
                    isPlaying = false,
                    onToggle = { clicks++ },
                    size = 28.dp,
                )
            }
        }

        val node = composeRule.onNodeWithContentDescription("播放")
            .assertContentDescriptionEquals("播放")
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        node.performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun playingButtonIsAnnouncedAsPause() {
        composeRule.setContent {
            MicaTheme {
                SharpPlayPauseButton(
                    isPlaying = true,
                    onToggle = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("暂停")
            .assertContentDescriptionEquals("暂停")
            .assertHasClickAction()
    }
}
