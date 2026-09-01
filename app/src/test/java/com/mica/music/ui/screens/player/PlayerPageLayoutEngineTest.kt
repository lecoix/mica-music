package com.mica.music.ui.screens.player

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.CompactLyricsLineMode
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.data.usesCompactLyricsLinePreference
import com.mica.music.ui.theme.HifiTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPageLayoutEngineTest {

    private val density = Density(1f)
    private val typography = HifiTypography()

    @Test
    fun playbackRequestFrameStaysAtNormalCoverGeometryWhileLyricsCoverShrinks() {
        val song = Song(
            id = "song",
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationSec = 180,
            metadata = TrackMetadata.fallback("audio/flac", 0),
            albumArtUri = "content://cover/song",
            coverColorArgb = 0,
            mediaUri = "content://song",
        )
        val model = PlayerPageUiModel(
            song = song,
            queue = listOf(song),
            currentIndex = 0,
            isPlaying = false,
            layoutInput = baseInput(lyricsProgress = 1f, lyricsExpanded = true),
            density = density,
            typography = typography,
        )

        val visibleLyricsFrame = model.frameFor(400.dp)
        val requestFrame = model.playbackFrameFor(400.dp)

        assertTrue(visibleLyricsFrame.cover.width < requestFrame.cover.width)
        assertEquals(400.dp, requestFrame.cover.width)
        assertEquals(400.dp, requestFrame.cover.height)
    }

    @Test
    fun playbackRequestFrameStaysAtNormalCoverGeometryWhileQueueCoverShrinks() {
        val song = Song(
            id = "song",
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationSec = 180,
            metadata = TrackMetadata.fallback("audio/flac", 0),
            albumArtUri = "content://cover/song",
            coverColorArgb = 0,
            mediaUri = "content://song",
        )
        val model = PlayerPageUiModel(
            song = song,
            queue = listOf(song),
            currentIndex = 0,
            isPlaying = false,
            layoutInput = baseInput(queueProgress = 1f, queueExpanded = true),
            density = density,
            typography = typography,
        )

        val visibleQueueFrame = model.frameFor(400.dp)
        val requestFrame = model.playbackFrameFor(400.dp)

        assertTrue(visibleQueueFrame.cover.width < requestFrame.cover.width)
        assertEquals(400.dp, requestFrame.cover.width)
        assertEquals(400.dp, requestFrame.cover.height)
        assertEquals(PlayerPageScene.Queue, visibleQueueFrame.scene)
    }

    @Test
    fun customStandardNeverUsesCoverEdgeProgress() {
        assertEquals(
            false,
            resolveUseCoverEdgeProgress(
                mode = com.mica.music.data.PlayerCoverFlowMode.CUSTOM_STANDARD,
                coverFlowModeEnabled = false,
                coverEdgeProgressSetting = true,
                standardCoverEdgeProgress = true,
            ),
        )
    }

    @Test
    fun lerpCoverFrame_movesFromCustomSlotToLyricsHeader() {
        val from = CoverFrame(
            width = 220.dp,
            height = 220.dp,
            startPadding = 48.dp,
            topPadding = 160.dp,
            blockHeight = 160.dp + 220.dp,
            particleInfoTopPadding = 0.dp,
            letterboxAlpha = 0f,
            zoneStop = 0.4f,
        )
        val to = CoverFrame(
            width = LyricsFocusMiniCoverSize,
            height = LyricsFocusMiniCoverSize,
            startPadding = LyricsFocusCoverStartPadding,
            topPadding = 24.dp,
            blockHeight = 24.dp + LyricsFocusMiniCoverSize + 8.dp,
            particleInfoTopPadding = 0.dp,
            letterboxAlpha = 0f,
            zoneStop = 0.12f,
        )

        assertEquals(from, lerpCoverFrame(from, to, 0f))
        assertEquals(to, lerpCoverFrame(from, to, 1f))
        val mid = lerpCoverFrame(from, to, 0.5f)
        assertEquals((from.width + to.width) / 2f, mid.width)
        assertEquals(mid.width, mid.height)
        assertEquals((from.startPadding + to.startPadding) / 2f, mid.startPadding)
        assertEquals((from.topPadding + to.topPadding) / 2f, mid.topPadding)
    }

    @Test
    fun customQueueCoverFrameAtRest_blockHeightIncludesTopOffset() {
        val rest = CoverFrame(
            width = 400.dp,
            height = 400.dp,
            startPadding = 0.dp,
            topPadding = 0.dp,
            blockHeight = 400.dp,
            particleInfoTopPadding = 0.dp,
            letterboxAlpha = 0f,
            zoneStop = 0.5f,
        )
        val frame = customQueueCoverFrameAtRest(
            restCover = rest,
            visualScale = 0.5f,
            coverTop = 160.dp,
            extraStartPadding = 80.dp,
            panelHeight = 800.dp,
        )

        assertEquals(200.dp, frame.width)
        assertEquals(200.dp, frame.height)
        assertEquals(160.dp, frame.topPadding)
        assertEquals(360.dp, frame.blockHeight)
        assertTrue(frame.blockHeight >= frame.topPadding + frame.height)
    }

    @Test
    fun customQueueCoverFrameInSlot_keepsArtworkSizeAndDropsPanelTop() {
        val panel = customQueueCoverFrameAtRest(
            restCover = CoverFrame(
                width = 400.dp,
                height = 400.dp,
                startPadding = 0.dp,
                topPadding = 0.dp,
                blockHeight = 400.dp,
                particleInfoTopPadding = 0.dp,
                letterboxAlpha = 0f,
                zoneStop = 0.5f,
            ),
            visualScale = 0.5f,
            coverTop = 160.dp,
            extraStartPadding = 80.dp,
            panelHeight = 800.dp,
        )
        val slot = customQueueCoverFrameInSlot(panel)

        assertEquals(panel.width, slot.width)
        assertEquals(panel.height, slot.height)
        assertEquals(0.dp, slot.topPadding)
        assertEquals(panel.height, slot.blockHeight)
        assertEquals(panel.startPadding, slot.startPadding)
    }

    @Test
    fun coverOrigin_splitsNegativeInsetIntoOffset() {
        assertEquals(24.dp, coverOriginPadding(24.dp))
        assertEquals(0.dp, coverOriginOffset(24.dp))
        assertEquals(0.dp, coverOriginPadding(0.dp))
        assertEquals(0.dp, coverOriginOffset(0.dp))
        assertEquals(0.dp, coverOriginPadding((-80).dp))
        assertEquals((-80).dp, coverOriginOffset((-80).dp))
    }

    @Test
    fun customQueueCoverFrameAtRest_keepsNegativeOriginForOversizedOrShiftedCover() {
        val rest = CoverFrame(
            width = 400.dp,
            height = 400.dp,
            startPadding = 0.dp,
            topPadding = 0.dp,
            blockHeight = 400.dp,
            particleInfoTopPadding = 0.dp,
            letterboxAlpha = 0f,
            zoneStop = 0.5f,
        )
        val fullWidth = 400.dp
        val scale = 2f
        val extraStart = fullWidth * (1f - scale) / 2f + fullWidth * (-200) / 1_000f
        val frame = customQueueCoverFrameAtRest(
            restCover = rest,
            visualScale = scale,
            coverTop = (-40).dp,
            extraStartPadding = extraStart,
            panelHeight = 800.dp,
        )

        assertEquals((-280).dp, extraStart)
        assertEquals((-280).dp, frame.startPadding)
        assertEquals((-40).dp, frame.topPadding)
        assertEquals(0.dp, coverOriginPadding(frame.startPadding))
        assertEquals((-280).dp, coverOriginOffset(frame.startPadding))
        assertEquals(0.dp, coverOriginPadding(frame.topPadding))
        assertEquals((-40).dp, coverOriginOffset(frame.topPadding))
    }

    @Test
    fun particleCover_followsCoverEdgeSettingRegardlessOfBackground() {
        assertEquals(
            true,
            resolveUseCoverEdgeProgress(
                mode = com.mica.music.data.PlayerCoverFlowMode.PARTICLE_COVER,
                coverFlowModeEnabled = false,
                coverEdgeProgressSetting = true,
                standardCoverEdgeProgress = false,
            ),
        )
        assertEquals(
            false,
            resolveUseCoverEdgeProgress(
                mode = com.mica.music.data.PlayerCoverFlowMode.PARTICLE_COVER,
                coverFlowModeEnabled = false,
                coverEdgeProgressSetting = false,
                standardCoverEdgeProgress = true,
            ),
        )
    }

    private fun baseInput(
        panelHeight: Dp = 400.dp,
        lyricsProgress: Float = 0f,
        lyricsChromeFade: Float = lyricsProgress,
        lyricsExpanded: Boolean = lyricsProgress > 0.5f,
        queueProgress: Float = 0f,
        queueExpanded: Boolean = queueProgress > 0.5f,
        useCoverEdgeProgress: Boolean = false,
        particleCoverMode: Boolean = false,
        photoStackMode: Boolean = false,
        immersiveLower: Boolean = false,
        immersiveProgress: Float = if (immersiveLower) 1f else 0f,
        coverFlowModeEnabled: Boolean = false,
        coverSwitching: Boolean = false,
        spectrumSettingEnabled: Boolean = true,
        compactLyricsLineMode: CompactLyricsLineMode = CompactLyricsLineMode.AUTO,
    ) = PlayerPageLayoutInput(
        panelHeight = panelHeight,
        screenHeight = 800.dp,
        screenWidth = 400.dp,
        statusBarTop = 24.dp,
        lyricsExpanded = lyricsExpanded,
        lyricsProgress = lyricsProgress,
        lyricsChromeFade = lyricsChromeFade,
        queueExpanded = queueExpanded,
        queueProgress = queueProgress,
        immersiveLower = immersiveLower,
        immersiveProgress = immersiveProgress,
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
        compactLyricsLineMode = compactLyricsLineMode,
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
    fun panelHeight_onlyChangesLowerPanelGeometry() {
        val preview = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                panelHeight = 360.dp,
                lyricsProgress = 0.35f,
                lyricsChromeFade = 0.2f,
                lyricsExpanded = true,
                useCoverEdgeProgress = true,
                coverFlowModeEnabled = true,
            ),
            density = density,
            typography = typography,
        )
        val actual = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                panelHeight = 500.dp,
                lyricsProgress = 0.35f,
                lyricsChromeFade = 0.2f,
                lyricsExpanded = true,
                useCoverEdgeProgress = true,
                coverFlowModeEnabled = true,
            ),
            density = density,
            typography = typography,
        )

        assertEquals(preview.copy(lower = actual.lower), actual)
        assertEquals(preview.lower.coverEdgeOnPlaySurface, actual.lower.coverEdgeOnPlaySurface)
        assertEquals(preview.lower.chromeProgressAlpha, actual.lower.chromeProgressAlpha)
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
    fun queueProgress_shrinksCoverFadesMetaAndSelectsQueueScene() {
        val normal = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(queueProgress = 0f),
            density = density,
            typography = typography,
        )
        val queue = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(queueProgress = 1f, queueExpanded = true),
            density = density,
            typography = typography,
        )
        assertTrue(queue.cover.width < normal.cover.width)
        assertEquals(LyricsFocusMiniCoverSize, queue.cover.width)
        assertTrue(normal.lower.metaAlpha > queue.lower.metaAlpha)
        assertEquals(0f, queue.lower.metaAlpha, 0.001f)
        assertEquals(0.dp, queue.lower.chromeHeight)
        assertEquals(PlayerPageScene.Queue, queue.scene)
    }

    @Test
    fun headerFocus_keepsMiniCoverWhenLyricsAndQueueAreBothOpen() {
        val lyrics = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(lyricsProgress = 1f, lyricsExpanded = true),
            density = density,
            typography = typography,
        )
        val both = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                lyricsProgress = 1f,
                lyricsExpanded = true,
                queueProgress = 1f,
                queueExpanded = true,
            ),
            density = density,
            typography = typography,
        )
        assertEquals(LyricsFocusMiniCoverSize, lyrics.cover.width)
        assertEquals(lyrics.cover.width, both.cover.width)
        assertEquals(lyrics.cover.startPadding, both.cover.startPadding)
        assertEquals(PlayerPageScene.Queue, both.scene)
        assertEquals(1f, playerHeaderFocus(1f, 1f), 0.001f)
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
    fun particleCover_hidesProgressAndSpectrumWhenCoverEdgeEnabled() {
        val withoutEdge = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                panelHeight = 400.dp,
                particleCoverMode = true,
                useCoverEdgeProgress = false,
            ),
            density = density,
            typography = typography,
        )
        val timeRowHeight = with(density) { typography.monoMd.lineHeight.toDp() }
        val progressGap = 32.dp + timeRowHeight + com.mica.music.ui.theme.HifiSize.iconLg / 2
        // 封面区已吃掉两份（标题上 + 标题-封面），下半屏实测高度同步变矮。
        val withEdge = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                panelHeight = 400.dp - progressGap / 2,
                particleCoverMode = true,
                useCoverEdgeProgress = true,
            ),
            density = density,
            typography = typography,
        )

        assertEquals(false, withEdge.lower.coverEdgeOnPlaySurface)
        assertEquals(false, withEdge.lower.showStandardProgress)
        assertEquals(false, withEdge.spectrumEnabled)
        assertTrue(withEdge.cover.particleInfoTopPadding > withoutEdge.cover.particleInfoTopPadding)
        assertTrue(withEdge.cover.topPadding > withoutEdge.cover.topPadding)
        assertTrue(withEdge.cover.blockHeight > withoutEdge.cover.blockHeight)
        assertTrue(withEdge.lower.spacing.afterCover > withoutEdge.lower.spacing.afterCover)
        assertTrue(
            withEdge.lower.spacing.beforePlaybackChrome >
                withoutEdge.lower.spacing.beforePlaybackChrome,
        )
        val expectedQuarter = progressGap / 4
        val titleLift =
            withEdge.cover.particleInfoTopPadding - withoutEdge.cover.particleInfoTopPadding
        val titleToCoverExtra =
            (withEdge.cover.topPadding - withEdge.cover.particleInfoTopPadding) -
                (withoutEdge.cover.topPadding - withoutEdge.cover.particleInfoTopPadding)
        val lyricsTopExtra =
            withEdge.lower.spacing.afterCover - withoutEdge.lower.spacing.afterCover
        val lyricsBottomExtra =
            withEdge.lower.spacing.beforePlaybackChrome -
                withoutEdge.lower.spacing.beforePlaybackChrome
        assertEquals(expectedQuarter, titleLift)
        assertEquals(expectedQuarter, titleToCoverExtra)
        assertEquals(expectedQuarter, lyricsTopExtra)
        assertEquals(expectedQuarter, lyricsBottomExtra)
    }

    @Test
    fun particleCover_keepsStandardProgressWhenCoverEdgeDisabled() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                particleCoverMode = true,
                useCoverEdgeProgress = false,
            ),
            density = density,
            typography = typography,
        )

        assertEquals(false, frame.lower.coverEdgeOnPlaySurface)
        assertTrue(frame.lower.showStandardProgress)
        assertTrue(frame.spectrumEnabled)
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
    fun particleCoverQueueLayout_usesMiniCoverInsteadOfLyricsBackground() {
        val frame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                panelHeight = 400.dp,
                queueProgress = 1f,
                queueExpanded = true,
                particleCoverMode = true,
            ),
            density = density,
            typography = typography,
        )

        assertTrue(frame.particleCover.enabled)
        assertTrue(frame.particleCover.normalLayerVisible)
        assertEquals(false, frame.particleCover.lyricsBackgroundVisible)
        assertEquals(LyricsFocusMiniCoverSize, frame.cover.width)
        assertEquals(LyricsFocusMiniCoverSize, frame.cover.height)
        assertEquals(PlayerPageScene.Queue, frame.scene)
        assertTrue(frame.cover.blockHeight > 24.dp)
    }

    @Test
    fun particleCoverQueueLayout_keepsGlesSlotMidTransition() {
        val rest = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(particleCoverMode = true),
            density = density,
            typography = typography,
        )
        val mid = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                particleCoverMode = true,
                queueProgress = 0.5f,
                queueExpanded = false,
            ),
            density = density,
            typography = typography,
        )

        assertTrue(mid.particleCover.normalLayerVisible)
        assertEquals(false, mid.particleCover.lyricsBackgroundVisible)
        assertTrue(mid.cover.width < rest.cover.width)
        assertTrue(mid.cover.width > LyricsFocusMiniCoverSize)
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
    fun photoStackImmersive_keepsPhotoInteractiveAndExpandsCard() {
        val normal = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                photoStackMode = true,
                useCoverEdgeProgress = true,
            ),
            density = density,
            typography = typography,
        )
        val immersive = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                photoStackMode = true,
                immersiveLower = true,
                immersiveProgress = 1f,
                useCoverEdgeProgress = true,
            ),
            density = density,
            typography = typography,
        )

        assertEquals(PlayerPageScene.Immersive, immersive.scene)
        assertTrue(immersive.photoStack.normalLayerVisible)
        assertEquals(1f, immersive.photoStack.immersiveProgress)
        assertTrue(immersive.gesturesEnabled)
        assertTrue(immersive.spectrumEnabled)
        assertTrue(immersive.photoStack.cardWidth > normal.photoStack.cardWidth)
        assertEquals(360.dp, immersive.photoStack.cardWidth)
        assertEquals(normal.photoStack.cardWidth, normal.photoStack.slotWidth)
        assertEquals(normal.photoStack.cardHeight, normal.photoStack.slotHeight)
        assertEquals(0.dp, normal.photoStack.cardTopInset)
        assertEquals(400.dp, immersive.photoStack.slotWidth)
        assertEquals(52.dp, immersive.photoStack.cardTopInset)
        assertTrue(immersive.photoStack.slotHeight - immersive.photoStack.cardHeight > 155.dp)
        assertTrue(
            immersive.cover.topPadding + immersive.photoStack.cardTopInset >
                normal.cover.topPadding,
        )
        assertEquals(0.dp, immersive.lower.photoStackTitleToControlsGap)
    }

    @Test
    fun photoStackQueue_keepsPolaroidSlotInsteadOfMiniCover() {
        val rest = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(photoStackMode = true, useCoverEdgeProgress = true),
            density = density,
            typography = typography,
        )
        val queue = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                photoStackMode = true,
                useCoverEdgeProgress = true,
                queueProgress = 1f,
                queueExpanded = true,
            ),
            density = density,
            typography = typography,
        )

        assertTrue(queue.photoStack.normalLayerVisible)
        assertEquals(rest.photoStack.cardWidth, queue.photoStack.cardWidth)
        assertEquals(rest.photoStack.cardHeight, queue.photoStack.cardHeight)
        assertEquals(rest.cover.width, queue.cover.width)
        assertEquals(rest.cover.topPadding, queue.cover.topPadding)
        assertTrue(queue.photoStack.cardWidth > LyricsFocusMiniCoverSize)
        assertTrue(queue.cover.blockHeight < rest.cover.blockHeight)
        assertEquals(0f, queue.lower.metaAlpha, 0.001f)
        assertEquals(0.dp, queue.lower.chromeHeight)
        assertEquals(PlayerPageScene.Queue, queue.scene)
    }

    @Test
    fun photoStackQueue_keepsPolaroidSlotMidTransition() {
        val rest = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(photoStackMode = true, useCoverEdgeProgress = true),
            density = density,
            typography = typography,
        )
        val mid = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                photoStackMode = true,
                useCoverEdgeProgress = true,
                queueProgress = 0.5f,
                queueExpanded = false,
            ),
            density = density,
            typography = typography,
        )

        assertTrue(mid.photoStack.normalLayerVisible)
        assertEquals(rest.photoStack.cardWidth, mid.photoStack.cardWidth)
        assertEquals(rest.cover.height, mid.cover.height)
        assertTrue(mid.cover.blockHeight < rest.cover.blockHeight)
        assertTrue(mid.cover.blockHeight > LyricsFocusMiniCoverSize)
    }

    @Test
    fun standardImmersive_keepsExistingGestureAndSpectrumGuards() {
        val immersive = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                immersiveLower = true,
                immersiveProgress = 1f,
                spectrumSettingEnabled = true,
            ),
            density = density,
            typography = typography,
        )

        assertEquals(PlayerPageScene.Immersive, immersive.scene)
        assertEquals(false, immersive.gesturesEnabled)
        assertEquals(false, immersive.spectrumEnabled)
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
    fun photoStackListLyricsPage_capsFivePercentBottomGapToAvailableSpace() {
        val playbackFrame = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                photoStackMode = true,
                useCoverEdgeProgress = true,
            ),
            density = density,
            typography = typography,
        )

        val lyricsLower = playbackFrame.lower.forPhotoStackListLyricsPage(800.dp)

        assertTrue(playbackFrame.lower.controlsBottomPadding > 0.dp)
        assertTrue(playbackFrame.lower.chromeHeight > 48.dp)
        assertEquals(40.dp, lyricsLower.controlsBottomPadding)
        assertEquals(88.dp, lyricsLower.chromeHeight)

        val heightConstrained = playbackFrame.lower
            .copy(controlsBottomPadding = 24.dp)
            .forPhotoStackListLyricsPage(800.dp)
        assertEquals(24.dp, heightConstrained.controlsBottomPadding)
        assertEquals(72.dp, heightConstrained.chromeHeight)
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

    @Test
    fun compactLyricsLineModeForcesOneEvenWhenHeightAllowsThree() {
        val auto = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                panelHeight = 400.dp,
                particleCoverMode = true,
            ),
            density = density,
            typography = typography,
        )
        val forcedOne = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                panelHeight = 400.dp,
                particleCoverMode = true,
                compactLyricsLineMode = CompactLyricsLineMode.ONE,
            ),
            density = density,
            typography = typography,
        )

        assertTrue(auto.lower.lyricLineSlots >= 3)
        assertEquals(1, forcedOne.lower.lyricLineSlots)
        assertEquals(1, forcedOne.lower.spacing.lyricLineSlots)
    }

    @Test
    fun compactLyricsLineModeForcesThreeEvenWhenHeightPrefersOne() {
        val shortPanel = 220.dp
        val auto = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(panelHeight = shortPanel),
            density = density,
            typography = typography,
        )
        val forcedThree = PlayerPageLayoutEngine.computeFrame(
            input = baseInput(
                panelHeight = shortPanel,
                compactLyricsLineMode = CompactLyricsLineMode.THREE,
            ),
            density = density,
            typography = typography,
        )

        assertEquals(1, auto.lower.lyricLineSlots)
        assertEquals(3, forcedThree.lower.lyricLineSlots)
        assertEquals(3, forcedThree.lower.spacing.lyricLineSlots)
    }

    @Test
    fun compactLyricsLinePreferenceAppliesOnlyToFourThemes() {
        assertTrue(PlayerCoverFlowMode.STANDARD.usesCompactLyricsLinePreference())
        assertTrue(PlayerCoverFlowMode.PARTICLE_COVER.usesCompactLyricsLinePreference())
        assertTrue(PlayerCoverFlowMode.PAUSE_FOLD.usesCompactLyricsLinePreference())
        assertTrue(PlayerCoverFlowMode.RETRO_3D.usesCompactLyricsLinePreference())
        assertFalse(PlayerCoverFlowMode.CUSTOM_STANDARD.usesCompactLyricsLinePreference())
        assertFalse(PlayerCoverFlowMode.PHOTO_STACK.usesCompactLyricsLinePreference())
    }
}
