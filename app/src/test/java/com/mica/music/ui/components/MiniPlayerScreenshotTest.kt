package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.testutil.SongFixtures
import com.mica.music.ui.theme.MicaTheme
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiniPlayerScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun requireFullScreenshotMatrix() {
        assumeTrue(System.getProperty("mica.fullScreenshots") == "true")
    }

    @Test
    fun floatingIslandPaused() =
        capture(MiniPlayerStyle.FLOATING_ISLAND, false, "mini-floating-paused.png")

    @Test
    fun floatingIslandPlaying() =
        capture(MiniPlayerStyle.FLOATING_ISLAND, true, "mini-floating-playing.png")

    @Test
    fun audiophilePaused() =
        capture(MiniPlayerStyle.AUDIOPHILE, false, "mini-audiophile-paused.png")

    @Test
    fun audiophilePlaying() =
        capture(MiniPlayerStyle.AUDIOPHILE, true, "mini-audiophile-playing.png")

    @Test
    fun audiophileDarkTheme() =
        capture(
            style = MiniPlayerStyle.AUDIOPHILE,
            playing = true,
            fileName = "mini-audiophile-dark.png",
            darkTheme = true,
        )

    @Test
    fun floatingIslandLargeFontLongMetadata() =
        capture(
            style = MiniPlayerStyle.FLOATING_ISLAND,
            playing = false,
            fileName = "mini-floating-large-font.png",
            fontScale = 1.5f,
            title = "A Very Long Golden Track Title For Accessibility",
        )

    private fun capture(
        style: MiniPlayerStyle,
        playing: Boolean,
        fileName: String,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        title: String = "Golden Track",
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MicaTheme(darkTheme = darkTheme) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(if (darkTheme) Color(0xFF101822) else Color(0xFFE9EDF4)),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        MiniPlayer(
                            style = style,
                            song = SongFixtures.song(
                                id = "golden",
                                title = title,
                            ).copy(albumArtUri = null),
                            isPlaying = playing,
                            onPlayPause = {},
                            onNext = {},
                            onExpand = {},
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage(fileName)
    }
}
