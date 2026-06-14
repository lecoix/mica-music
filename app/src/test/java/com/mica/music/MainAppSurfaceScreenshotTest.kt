package com.mica.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainAppSurfaceScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun compactPortraitCoversGestureNavigationEdge() {
        captureSurface("main-surface-360x800.png")
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun largePortraitCoversGestureNavigationEdge() {
        captureSurface("main-surface-412x915.png")
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun landscapeCoversAllEdges() {
        captureSurface("main-surface-800x360.png")
    }

    private fun captureSurface(fileName: String) {
        composeRule.setContent {
            MaterialTheme {
                MainAppSurface(
                    snackbarHost = {},
                    background = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFF27364D), Color(0xFF101822)),
                                    ),
                                ),
                        )
                    },
                ) {
                    Text(
                        text = "Mica",
                        color = Color.White,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }

        composeRule.onRoot().captureRoboImage(fileName)
    }
}
