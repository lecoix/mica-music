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
        useCoverEdgeProgress: Boolean = false,
    ) = PlayerPageLayoutInput(
        panelHeight = panelHeight,
        screenHeight = 800.dp,
        screenWidth = 400.dp,
        statusBarTop = 24.dp,
        lyricsExpanded = lyricsProgress > 0.5f,
        lyricsProgress = lyricsProgress,
        lyricsChromeFade = lyricsProgress,
        immersiveLower = false,
        immersiveProgress = 0f,
        coverFlowProgress = 0f,
        coverFlowModeEnabled = false,
        useCoverEdgeProgress = useCoverEdgeProgress,
        fitOriginal = false,
        coverAspectRatio = 1f,
        spectrumSettingEnabled = true,
        spectrumDeferred = false,
        coverSwitching = false,
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
    fun coverFrame_zoneStopWithinBounds() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(),
            density = density,
            typography = typography,
        )
        assertTrue(frame.cover.zoneStop in 0.12f..0.65f)
    }
}
