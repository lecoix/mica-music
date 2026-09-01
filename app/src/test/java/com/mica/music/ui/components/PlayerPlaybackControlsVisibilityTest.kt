package com.mica.music.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.mica.music.data.PlayerControlButton
import com.mica.music.playback.PlaybackSurfaceState
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 自定义标准主题可以逐个隐藏播放控制五键。隐藏后剩余按钮必须留在原来的槽位，
 * 尤其是播放键要始终停在正中，因此这里断言的是几何而不只是节点存在性。
 */
@RunWith(RobolectricTestRunner::class)
class PlayerPlaybackControlsVisibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val allDescriptions = listOf("顺序播放", "上一首", "播放", "下一首", "播放列表")

    private fun setControls(
        initialHidden: Set<PlayerControlButton> = emptySet(),
    ): MutableState<Set<PlayerControlButton>> {
        val hidden = mutableStateOf(initialHidden)
        composeRule.setContent {
            MicaTheme {
                val colors = MicaTheme.colors
                val hiddenButtons by hidden
                PlayerPlaybackControlsSection(
                    surfaceState = PlaybackSurfaceState(),
                    colors = PlayerContentColors(
                        colors.textPrimary,
                        colors.textSecondary,
                        colors.textTertiary,
                    ),
                    onCyclePlaybackQueueMode = {},
                    onPrevious = {},
                    onTogglePlay = {},
                    onNext = {},
                    onOpenQueue = {},
                    hiddenButtons = hiddenButtons,
                    modifier = Modifier.width(320.dp),
                )
            }
        }
        return hidden
    }

    private fun playButtonCenterX(): Float = composeRule
        .onNodeWithContentDescription("播放")
        .getUnclippedBoundsInRoot()
        .let { ((it.left + it.right) / 2).value }

    @Test
    fun defaultShowsAllFiveButtons() {
        setControls()

        allDescriptions.forEach { description ->
            composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
        }
    }

    @Test
    fun hidingButtonsRemovesOnlyThoseButtons() {
        setControls(setOf(PlayerControlButton.QUEUE_MODE, PlayerControlButton.QUEUE))

        composeRule.onNodeWithContentDescription("顺序播放").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("播放列表").assertDoesNotExist()
        listOf("上一首", "播放", "下一首").forEach { description ->
            composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
        }
    }

    @Test
    fun hidingOuterButtonsKeepsPlayButtonCentered() {
        val hidden = setControls()
        val baselineCenterX = playButtonCenterX()

        composeRule.runOnIdle {
            hidden.value = setOf(PlayerControlButton.QUEUE_MODE, PlayerControlButton.QUEUE)
        }
        composeRule.waitForIdle()

        assertEquals(baselineCenterX, playButtonCenterX(), 0.5f)
    }

    @Test
    fun hidingOneSideStillKeepsPlayButtonCentered() {
        val hidden = setControls()
        val baselineCenterX = playButtonCenterX()

        composeRule.runOnIdle { hidden.value = setOf(PlayerControlButton.PREVIOUS) }
        composeRule.waitForIdle()

        assertEquals(baselineCenterX, playButtonCenterX(), 0.5f)
    }
}
