package com.mica.music.ui.screens.player

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.HifiTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPageLayoutEngineTest {

    private val density = Density(1f)
    private val typography = HifiTypography()

    private fun baseInput(
        panelHeight: Dp = 400.dp,
        lyricsProgress: Float = 0f,
        lyricsChromeFade: Float = lyricsProgress,
        lyricsExpanded: Boolean = lyricsProgress > 0.5f,
        useCoverEdgeProgress: Boolean = false,
        particleCoverMode: Boolean = false,
        coverFlowModeEnabled: Boolean = false,
        coverSwitching: Boolean = false,
    ) = PlayerPageLayoutInput(
        panelHeight = panelHeight,
        screenHeight = 800.dp,
        screenWidth = 400.dp,
        statusBarTop = 24.dp,
        lyricsExpanded = lyricsExpanded,
        lyricsProgress = lyricsProgress,
        lyricsChromeFade = lyricsChromeFade,
        immersiveLower = false,
        immersiveProgress = 0f,
        coverFlowProgress = 0f,
        coverFlowModeEnabled = coverFlowModeEnabled,
        useCoverEdgeProgress = useCoverEdgeProgress,
        particleCoverMode = particleCoverMode,
        fitOriginal = false,
        coverAspectRatio = 1f,
        spectrumSettingEnabled = true,
        spectrumDeferred = false,
        coverSwitching = coverSwitching,
    )

    @Test
    fun normalScene_producesThreeLyricSlotsOnTallPanel() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(panelHeight = 500.dp),
            density = density,
            typography = typography,
        )
        assertEquals(PlayerPageScene.Normal, frame.scene)
        assertTrue(frame.lower.spacing.lyricLineSlots >= 1)
    }

    @Test
    fun lyricsProgress_fadesMeta() {
        val normal = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(lyricsProgress = 0f),
            density = density,
            typography = typography,
        )
        val lyrics = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(lyricsProgress = 1f),
            density = density,
            typography = typography,
        )
        assertTrue(normal.lower.metaAlpha > lyrics.lower.metaAlpha)
        assertEquals(PlayerPageScene.Lyrics, lyrics.scene)
    }

    @Test
    fun coverEdgeOnPlaySurface_hidesStandardProgress() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(useCoverEdgeProgress = true),
            density = density,
            typography = typography,
        )
        assertTrue(frame.lower.coverEdgeOnPlaySurface)
        assertEquals(false, frame.lower.showStandardProgress)
    }

    @Test
    fun specialCoverTheme_usesCoverEdgePlaybackWhenSettingEnabled() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                useCoverEdgeProgress = true,
                coverFlowModeEnabled = true,
            ),
            density = density,
            typography = typography,
        )

        assertTrue(frame.lower.coverEdgeOnPlaySurface)
        assertEquals(false, frame.lower.showStandardProgress)
        assertTrue(frame.spectrumEnabled)
    }

    @Test
    fun specialCoverTheme_keepsStandardPlaybackChromeWhenSettingDisabled() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                useCoverEdgeProgress = false,
                coverFlowModeEnabled = true,
            ),
            density = density,
            typography = typography,
        )

        assertEquals(false, frame.lower.coverEdgeOnPlaySurface)
        assertTrue(frame.lower.showStandardProgress)
        assertTrue(frame.spectrumEnabled)
    }

    @Test
    fun coverEdgeProgress_keepsStandardProgressMountedDuringLyricsTransition() {
        val opening = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                lyricsProgress = 0.2f,
                lyricsChromeFade = 0.3f,
                lyricsExpanded = true,
                useCoverEdgeProgress = true,
            ),
            density = density,
            typography = typography,
        )
        val closing = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                lyricsProgress = 0.5f,
                lyricsChromeFade = 0.1f,
                lyricsExpanded = false,
                useCoverEdgeProgress = true,
            ),
            density = density,
            typography = typography,
        )

        assertTrue(opening.lower.coverEdgeOnPlaySurface)
        assertTrue(opening.lower.showChromeProgressInTransition)
        assertEquals(0.3f, opening.lower.chromeProgressAlpha, 0.001f)
        assertTrue(closing.lower.showChromeProgressInTransition)
        assertEquals(0.5f, closing.lower.chromeProgressAlpha, 0.001f)
    }

    @Test
    fun spectrum_isDisabledForEntireLyricsOpeningTransition() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                lyricsProgress = 0.01f,
                lyricsChromeFade = 0.02f,
                lyricsExpanded = true,
                useCoverEdgeProgress = true,
                coverFlowModeEnabled = true,
            ),
            density = density,
            typography = typography,
        )

        assertTrue(frame.lower.showStandardProgress)
        assertEquals(false, frame.spectrumEnabled)
    }

    @Test
    fun spectrum_staysDisabledUntilLyricsClosingTransitionFinishes() {
        val closing = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                lyricsProgress = 0.01f,
                lyricsChromeFade = 0f,
                lyricsExpanded = false,
                useCoverEdgeProgress = true,
                coverFlowModeEnabled = true,
            ),
            density = density,
            typography = typography,
        )
        val finished = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                lyricsProgress = 0f,
                lyricsChromeFade = 0f,
                lyricsExpanded = false,
                useCoverEdgeProgress = true,
                coverFlowModeEnabled = true,
            ),
            density = density,
            typography = typography,
        )

        assertEquals(false, closing.spectrumEnabled)
        assertTrue(finished.spectrumEnabled)
    }

    @Test
    fun spectrum_isDisabledDuringAnyCoverFlowMotion() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                useCoverEdgeProgress = true,
                coverFlowModeEnabled = true,
                coverSwitching = true,
            ),
            density = density,
            typography = typography,
        )

        assertEquals(false, frame.spectrumEnabled)
    }

    @Test
    fun coverEdgeLayout_usesTheWholeLyricsTransition() {
        val play = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                lyricsProgress = 0f,
                useCoverEdgeProgress = true,
            ),
            density = density,
            typography = typography,
        )
        val middle = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                lyricsProgress = 0.5f,
                lyricsExpanded = true,
                useCoverEdgeProgress = true,
            ),
            density = density,
            typography = typography,
        )
        val lyrics = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                lyricsProgress = 1f,
                lyricsExpanded = true,
                useCoverEdgeProgress = true,
            ),
            density = density,
            typography = typography,
        )

        assertTrue(
            middle.lower.chromeHeight > play.lower.chromeHeight,
        )
        assertTrue(
            middle.lower.chromeHeight < lyrics.lower.chromeHeight,
        )
    }

    @Test
    fun coverFrame_zoneStopWithinBounds() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(),
            density = density,
            typography = typography,
        )
        assertTrue(frame.cover.zoneStop in 0.12f..0.65f)
    }
}
