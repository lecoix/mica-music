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
        photoStackMode: Boolean = false,
        coverFlowModeEnabled: Boolean = false,
        coverSwitching: Boolean = false,
        spectrumSettingEnabled: Boolean = true,
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
        photoStackMode = photoStackMode,
        fitOriginal = false,
        coverAspectRatio = 1f,
        spectrumSettingEnabled = spectrumSettingEnabled,
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
    fun coverFlowLyricsTransition_keepsStageMountedUntilLyricsSettles() {
        val opening = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                lyricsProgress = 0.5f,
                lyricsExpanded = true,
                coverFlowModeEnabled = true,
                useCoverEdgeProgress = true,
            ),
            density = density,
            typography = typography,
        )
        val settledLyrics = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                lyricsProgress = 1f,
                lyricsExpanded = true,
                coverFlowModeEnabled = true,
                useCoverEdgeProgress = true,
            ),
            density = density,
            typography = typography,
        )

        assertTrue(opening.coverFlowStageActive)
        assertEquals(0.5f, opening.coverFlowProgress, 0.001f)
        assertEquals(false, settledLyrics.coverFlowStageActive)
        assertEquals(0f, settledLyrics.coverFlowProgress, 0.001f)
    }

    @Test
    fun coverFlowLyricsTransition_ignoresPlaybackFoldAnimation() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                lyricsProgress = 0.75f,
                lyricsExpanded = false,
                coverFlowModeEnabled = true,
                useCoverEdgeProgress = true,
            ).copy(coverFlowProgress = 1f),
            density = density,
            typography = typography,
        )

        assertTrue(frame.coverFlowStageActive)
        assertEquals(0.25f, frame.coverFlowProgress, 0.001f)
    }

    @Test
    fun coverFlowLyricsTransition_handsOffToWarmPlaybackProgress() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                lyricsProgress = 0f,
                lyricsExpanded = false,
                coverFlowModeEnabled = true,
                useCoverEdgeProgress = true,
            ).copy(coverFlowProgress = 1f),
            density = density,
            typography = typography,
        )

        assertTrue(frame.coverFlowStageActive)
        assertEquals(1f, frame.coverFlowProgress, 0.001f)
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

    @Test
    fun particleCoverLayout_keepsTitleFixedAndLyricsRoomAfterCoverDrop() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                panelHeight = 400.dp,
                particleCoverMode = true,
            ),
            density = density,
            typography = typography,
        )

        assertEquals(false, frame.lower.showMetadata)
        assertTrue(frame.cover.topPadding > frame.cover.particleInfoTopPadding)
        assertTrue(frame.lower.spacing.lyricLineSlots >= 3)
    }

    @Test
    fun particleCoverLyricsLayout_usesBackgroundLayerInsteadOfMiniCoverSlot() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                panelHeight = 400.dp,
                lyricsProgress = 1f,
                lyricsExpanded = true,
                particleCoverMode = true,
            ),
            density = density,
            typography = typography,
        )

        assertTrue(frame.particleCover.enabled)
        assertEquals(false, frame.particleCover.normalLayerVisible)
        assertTrue(frame.particleCover.lyricsBackgroundVisible)
        assertEquals(400.dp, frame.particleCover.hostBaseSize)
        assertEquals(24.dp, frame.cover.blockHeight)
        assertTrue(frame.cover.width > LyricsFocusMiniCoverSize)
    }

    @Test
    fun photoStackMode_outputsPhotoStackFrameAndPaperProgressLayout() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                photoStackMode = true,
                useCoverEdgeProgress = true,
            ),
            density = density,
            typography = typography,
        )

        assertTrue(frame.photoStack.enabled)
        assertTrue(frame.photoStack.normalLayerVisible)
        assertEquals(false, frame.lower.showStandardProgress)
        assertTrue(frame.photoStack.cardWidth < 400.dp)
        assertTrue(frame.photoStack.cardHeight > frame.photoStack.cardWidth)
        assertTrue(frame.spectrumEnabled)
    }

    @Test
    fun photoStackMode_placesPhotoAndControlsWithSymmetricGaps() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                photoStackMode = true,
                useCoverEdgeProgress = true,
            ),
            density = density,
            typography = typography,
        )

        val titleBlock = frame.lower.photoStackTitleBlockHeight
        val controls = 48.dp
        val edgeGap = frame.cover.topPadding
        val middleGap = frame.lower.photoStackTitleToControlsGap
        val fixed = frame.photoStack.cardHeight + titleBlock + controls
        val availableGap = 800.dp - fixed
        val expectedEdgeGap = minOf(80.dp, availableGap / 2)
        val expectedMiddleGap = (availableGap - expectedEdgeGap * 2) / 2

        assertEquals(expectedEdgeGap, edgeGap)
        assertEquals(expectedMiddleGap, middleGap)
        assertEquals(expectedEdgeGap, frame.lower.controlsBottomPadding)
        assertEquals(frame.photoStack.cardHeight + edgeGap + middleGap, frame.cover.blockHeight)
    }

    @Test
    fun photoStackSpectrumUsesExistingGuards() {
        val switching = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                photoStackMode = true,
                useCoverEdgeProgress = true,
                coverSwitching = true,
            ),
            density = density,
            typography = typography,
        )
        val lyrics = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                photoStackMode = true,
                useCoverEdgeProgress = true,
                lyricsProgress = 0.01f,
                lyricsExpanded = true,
            ),
            density = density,
            typography = typography,
        )

        assertEquals(false, switching.spectrumEnabled)
        assertEquals(false, lyrics.spectrumEnabled)
    }

    @Test
    fun photoStackSpectrumDoesNotRequireStandardSpectrumSetting() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                photoStackMode = true,
                useCoverEdgeProgress = true,
                spectrumSettingEnabled = false,
            ),
            density = density,
            typography = typography,
        )
        val normal = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                spectrumSettingEnabled = false,
            ),
            density = density,
            typography = typography,
        )

        assertTrue(frame.spectrumEnabled)
        assertEquals(false, normal.spectrumEnabled)
    }
}
