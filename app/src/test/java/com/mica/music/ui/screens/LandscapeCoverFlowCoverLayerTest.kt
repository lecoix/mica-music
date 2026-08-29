package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
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
@Config(qualifiers = "w1000dp-h400dp-land-mdpi")
class LandscapeCoverFlowCoverLayerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lyricsLaneMapsTheVisibleArtworkIntoTheMeasuredSlot() {
        val target = Rect(left = 100f, top = 100f, right = 184f, bottom = 184f)
        var artworkBounds: Rect? = null

        composeRule.setContent {
            Box(Modifier.size(width = 1000.dp, height = 400.dp)) {
                LandscapeCoverFlowCoverLayer(
                    progress = 1f,
                    edgePadding = 16.dp,
                    coverHeight = 280.dp,
                    coverBlockHeight = 330.dp,
                    coverTopPadding = 20.dp,
                    contentPadding = PaddingValues(0.dp),
                    lyricsCoverSize = 84.dp,
                    lyricsCoverBoundsInRoot = target,
                    coverLaneWidth = 180.dp,
                    horizontalPadding = 16.dp,
                    topPadding = 0.dp,
                    coverContent = { modifier, _ ->
                        Box(modifier.requiredHeight(330.dp)) {
                            Box(
                                Modifier
                                    .offset(x = 310.dp, y = 20.dp)
                                    .size(280.dp)
                                    .onGloballyPositioned {
                                        artworkBounds = it.boundsInRoot()
                                    },
                            )
                        }
                    },
                    modifier = Modifier
                        .offset(x = 40.dp, y = 30.dp)
                        .size(width = 900.dp, height = 350.dp),
                )
            }
        }

        composeRule.runOnIdle {
            val actual = checkNotNull(artworkBounds)
            assertEquals(target.left, actual.left, 0.01f)
            assertEquals(target.top, actual.top, 0.01f)
            assertEquals(target.right, actual.right, 0.01f)
            assertEquals(target.bottom, actual.bottom, 0.01f)
        }
    }
}
