package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "mdpi")
class LandscapeClassicLeftColumnTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun coverSizeRespectsMaxLaneAndSlotBounds() {
        assertEquals(200.dp, resolveLandscapeClassicCoverSize(200.dp, 280.dp, 240.dp))
        assertEquals(180.dp, resolveLandscapeClassicCoverSize(200.dp, 180.dp, 240.dp))
        assertEquals(160.dp, resolveLandscapeClassicCoverSize(200.dp, 280.dp, 160.dp))
    }

    @Test
    fun coverSizeNeverExceedsShorterSlotDimension() {
        val cover = resolveLandscapeClassicCoverSize(275.dp, 275.dp, 88.dp)
        assertEquals(88.dp, cover)
    }

    @Test
    fun shortLyricsColumnReportsTheExactCoverSlotBoundsToTheFloatingLayer() {
        var floatingLayerCoverBounds: Rect? = null
        var titleBounds: Rect? = null

        composeRule.setContent {
            LandscapeClassicLeftColumn(
                maxCoverSize = 180.dp,
                contentGap = 8.dp,
                onCoverBoundsResolved = { floatingLayerCoverBounds = it },
                coverContent = { _, coverModifier -> Box(coverModifier) },
                titleContent = {
                    Box(
                        Modifier
                            .size(width = 180.dp, height = 40.dp)
                            .onGloballyPositioned { titleBounds = it.boundsInRoot() },
                    )
                },
                chromeContent = { Box(Modifier.size(width = 180.dp, height = 60.dp)) },
                modifier = Modifier.size(width = 180.dp, height = 200.dp),
            )
        }

        composeRule.runOnIdle {
            val cover = checkNotNull(floatingLayerCoverBounds)
            val title = checkNotNull(titleBounds)
            assertEquals(84f, cover.width, 0.01f)
            assertEquals(84f, cover.height, 0.01f)
            assertEquals(8f, title.top - cover.bottom, 0.01f)
        }
    }
}
