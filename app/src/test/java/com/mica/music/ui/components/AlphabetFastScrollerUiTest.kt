package com.mica.music.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.MicaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land-mdpi")
class AlphabetFastScrollerUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun landscapeOverlayCoversTheRootWindowAndStillReachesLastSection() {
        var targetIndex = -1
        val labels = ('A'..'Z').map(Char::toString) + "#"
        composeRule.setContent {
            MicaTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(120.dp))
                    AlphabetFastScroller(
                        labels = labels,
                        scrollToIndex = { targetIndex = it },
                        fullHeightOverlay = true,
                        modifier = Modifier.weight(1f),
                    ) {}
                }
            }
        }

        val touchStrip = composeRule.onNodeWithTag(AlphabetFastScrollerTouchStripTag)
        val bounds = touchStrip.fetchSemanticsNode().layoutInfo.coordinates.boundsInWindow()
        assertEquals(0f, bounds.top, 1f)
        assertEquals(360f, bounds.height, 1f)

        touchStrip.performTouchInput {
            down(Offset(center.x, height - 1f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(labels.lastIndex, targetIndex)
    }
}
