package com.mica.music.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeDrawerSwipeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reportsOnlyEnabledHorizontalDrags() {
        val enabled = mutableStateOf(false)
        val progress = mutableFloatStateOf(0f)
        var settledOpen: Boolean? = null
        composeRule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("drawer-swipe")
                    .homeDrawerSwipe(
                        enabled = enabled.value,
                        drawerWidth = 320.dp,
                        onProgressChange = { progress.floatValue = it },
                        onDragStarted = { progress.floatValue },
                        onDragStopped = { settledProgress, velocity ->
                            settledOpen = homeDrawerTargetOpen(
                                progress = settledProgress,
                                velocityPxPerSecond = velocity,
                                velocityThresholdPxPerSecond = 400f,
                            )
                        },
                    ),
            )
        }

        val swipeArea = composeRule.onNodeWithTag("drawer-swipe")
        swipeArea.performTouchInput { swipeRight() }
        composeRule.runOnIdle { assertEquals(null, settledOpen) }

        composeRule.runOnIdle { enabled.value = true }
        swipeArea.performTouchInput { swipeLeft() }
        composeRule.runOnIdle { assertEquals(false, settledOpen) }

        swipeArea.performTouchInput { swipeRight() }
        composeRule.runOnIdle {
            assertEquals(true, settledOpen)
            assertTrue(progress.floatValue > 0.9f)
        }
    }

    @Test
    fun targetUsesVelocityBeforePosition() {
        assertTrue(homeDrawerTargetOpen(0.1f, 401f, 400f))
        assertFalse(homeDrawerTargetOpen(0.9f, -401f, 400f))
        assertTrue(homeDrawerTargetOpen(0.5f, 0f, 400f))
        assertFalse(homeDrawerTargetOpen(0.49f, 0f, 400f))
    }
}
