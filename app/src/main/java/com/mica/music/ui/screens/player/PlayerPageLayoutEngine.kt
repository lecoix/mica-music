package com.mica.music.ui.screens.player

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.mica.music.data.CompactLyricsLineMode
import com.mica.music.ui.components.PlayerCoverMaxScreenFraction
import com.mica.music.ui.components.PlayerPanelLyricScale
import com.mica.music.ui.components.measurePlayerCoverFitOriginal
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.HifiTypography
import com.mica.music.ui.theme.playerInfoRowHeight

/**
 * 纯布局计算：给定动画进度与尺寸，原子产出 [PlayerPageFrame]。
 * 不含 freeze / 快照；歌词、沉浸、封面底边进度在同一函数内 lerp。
 */
object PlayerPageLayoutEngine {
    private const val PhotoStackScreenFraction = 0.80f
    internal const val PhotoStackImmersiveScreenFraction = 0.90f
    internal const val PhotoStackArtworkInsetHorizontalFraction = 0.038f
    private const val PhotoStackImmersiveCenterBias = 0.82f
    private const val PhotoStackAspectRatio = 0.78f
    private const val PhotoStackEdgeFraction = 0.10f
    private val PhotoStackImmersiveHorizontalBleed = 40.dp
    private val PhotoStackImmersiveTopBleed = 52.dp
    private val PhotoStackImmersiveBottomBleed = 104.dp

    fun computeFrame(
        input: PlayerPageLayoutInput,
        density: Density,
        typography: HifiTypography,
    ): PlayerPageFrame {
        val lyricsFocus = input.lyricsProgress.coerceIn(0f, 1f)
        val queueFocus = input.queueProgress.coerceIn(0f, 1f)
        val headerFocus = playerHeaderFocus(lyricsFocus, queueFocus)
        val immersiveProgress = input.immersiveProgress.coerceIn(0f, 1f)
        val lyricsChromeFade = input.lyricsChromeFade.coerceIn(0f, 1f)
        val useCoverEdgePlayback = input.useCoverEdgeProgress
        val particleHidesProgressChrome =
            ParticleCoverThemePolicy.hidesProgressAndSpectrumForCoverEdge(
                particleCoverMode = input.particleCoverMode,
                useCoverEdgeProgress = useCoverEdgePlayback,
            )

        val chromeProgressAlpha = when {
            !useCoverEdgePlayback -> 1f
            input.lyricsExpanded || input.queueExpanded -> maxOf(lyricsChromeFade, queueFocus)
            else -> headerFocus
        }
        val coverEdgeOnPlaySurface =
            useCoverEdgePlayback &&
                !particleHidesProgressChrome &&
                chromeProgressAlpha < 1f - ImmersiveProgressEpsilon

        val coverFlowStage = resolveCoverFlowStage(input, headerFocus)
        val coverFlowProgress = coverFlowStage.progress
        val coverFlowStageActive = coverFlowStage.active

        val photoStackTitleBlockHeight = computePhotoStackTitleBlockHeight(density, typography)
        val photoStackControlsHeight = HifiSize.touchTarget
        val cover = computeCoverFrame(
            input = input,
            density = density,
            typography = typography,
            headerFocus = headerFocus,
            lyricsChromeFade = lyricsChromeFade,
            photoStackTitleBlockHeight = photoStackTitleBlockHeight,
            photoStackControlsHeight = photoStackControlsHeight,
            particleHidesProgressChrome = particleHidesProgressChrome,
        )
        val particleCover = computeParticleCoverFrame(
            input = input,
            headerFocus = headerFocus,
        )
        val photoStack = computePhotoStackFrame(
            input = input,
            cover = cover,
        )

        val particleProgressGap = if (particleHidesProgressChrome) {
            particleHiddenProgressGap(density, typography)
        } else {
            0.dp
        }
        val particleProgressQuarter = particleProgressGap / 4
        val lowerPlan = computeLowerLayoutPlan(
            density = density,
            typography = typography,
            // 预留两份给歌词上下；算完后再精确加回，避免 meta 盈余把四等分打散。
            panelHeight = (input.panelHeight - particleProgressQuarter * 2).coerceAtLeast(0.dp),
            useCoverEdgeProgressSetting = useCoverEdgePlayback,
            applyCoverEdgeGapCosmetics = useCoverEdgePlayback && !particleHidesProgressChrome,
            lyricsFocus = headerFocus,
            showMetadata = !input.particleCoverMode,
            compactLyricsLineMode = input.compactLyricsLineMode,
        )
        val lowerSpacing = if (particleProgressQuarter > 0.dp) {
            val lyricGapBoost = particleProgressQuarter * (1f - headerFocus)
            lowerPlan.spacing.copy(
                afterCover = lowerPlan.spacing.afterCover + lyricGapBoost,
                beforePlaybackChrome = lowerPlan.spacing.beforePlaybackChrome + lyricGapBoost,
            )
        } else {
            lowerPlan.spacing
        }

        val photoStackLayout = if (input.photoStackMode) {
            computePhotoStackVerticalLayout(
                screenHeight = input.screenHeight,
                photoStackHeight = photoStack.slotHeight,
                titleBlockHeight = photoStackTitleBlockHeight,
                controlsHeight = photoStackControlsHeight,
                immersiveProgress = immersiveProgress,
            )
        } else {
            PhotoStackVerticalLayout(
                edgeGap = 0.dp,
                middleGap = 0.dp,
            )
        }
        val chromeHeight = if (input.photoStackMode) {
            photoStackControlsHeight + photoStackLayout.edgeGap
        } else {
            lerpDp(
                lowerPlan.chromeHeightAtRest,
                lowerPlan.chromeHeightAtFullImmersive,
                immersiveProgress,
            )
        }
        val controlsBottomPadding = if (input.photoStackMode) {
            photoStackLayout.edgeGap
        } else {
            lowerSpacing.afterControls * lyricsChromeBottomInsetScale(headerFocus)
        }

        val immersiveInTransition =
            input.immersiveLower || immersiveProgress > ImmersiveProgressEpsilon
        val titleSlideDown = computeTitleSlideDown(
            immersiveInTransition = immersiveInTransition,
            panelHeight = input.panelHeight,
            spacing = lowerSpacing,
            chromeHeightAtFullImmersive = lowerPlan.chromeHeightAtFullImmersive,
            density = density,
            typography = typography,
            immersiveProgress = immersiveProgress,
        )

        val showChromeProgressInTransition =
            useCoverEdgePlayback &&
                chromeProgressAlpha > ImmersiveProgressEpsilon
        val showStandardProgress =
            !input.photoStackMode && (!useCoverEdgePlayback || showChromeProgressInTransition)

        val metaAlpha = 1f - headerFocus
        val compactContentAlpha = if (input.particleCoverMode && headerFocus > ImmersiveProgressEpsilon) {
            ParticleCoverPageLayout.compactContentAlpha(headerFocus, metaAlpha)
        } else {
            metaAlpha
        }
        val spectrumOverlayAlpha =
            metaAlpha.coerceIn(0f, 1f) * (1f - immersiveProgress)
        val stablePlaybackScene =
            !input.lyricsExpanded &&
                !input.queueExpanded &&
                headerFocus <= ImmersiveProgressEpsilon &&
                (!input.immersiveLower || input.photoStackMode) &&
                (immersiveProgress <= ImmersiveProgressEpsilon || input.photoStackMode)
        val liveSpectrumRequested =
            input.spectrumSettingEnabled ||
                input.photoStackMode
        val spectrumEnabled =
            liveSpectrumRequested &&
                !particleHidesProgressChrome &&
                !input.spectrumDeferred &&
                !input.coverSwitching &&
                stablePlaybackScene

        val gesturesEnabled =
            !input.lyricsExpanded &&
                !input.queueExpanded &&
                (!input.immersiveLower || input.photoStackMode) &&
                headerFocus < 0.01f

        val scene = when {
            input.queueExpanded || queueFocus > ImmersiveProgressEpsilon -> PlayerPageScene.Queue
            input.lyricsExpanded || lyricsFocus > ImmersiveProgressEpsilon -> PlayerPageScene.Lyrics
            input.immersiveLower || immersiveProgress > ImmersiveProgressEpsilon -> PlayerPageScene.Immersive
            else -> PlayerPageScene.Normal
        }

        return PlayerPageFrame(
            scene = scene,
            lyricsProgress = lyricsFocus,
            queueProgress = queueFocus,
            immersiveProgress = immersiveProgress,
            coverFlowProgress = coverFlowProgress,
            coverFlowStageActive = coverFlowStageActive,
            gesturesEnabled = gesturesEnabled,
            spectrumEnabled = spectrumEnabled,
            cover = cover,
            particleCover = particleCover,
            photoStack = photoStack,
            lower = LowerPanelFrame(
                spacing = lowerSpacing,
                chromeHeight = lerpDp(
                    maxOf(0.dp, chromeHeight - lyricsChromeDrop(lyricsFocus)),
                    0.dp,
                    queueFocus,
                ),
                controlsBottomPadding = controlsBottomPadding,
                photoStackTitleBlockHeight = photoStackTitleBlockHeight,
                photoStackTitleToControlsGap = photoStackLayout.middleGap,
                titleSlideDown = titleSlideDown,
                showMetadata = lowerPlan.showMetadata,
                metaAlpha = metaAlpha,
                compactContentAlpha = compactContentAlpha,
                lyricsChromeFade = lyricsChromeFade,
                lyricsLayoutFocus = lyricsFocus,
                queueLayoutFocus = queueFocus,
                immersiveProgress = immersiveProgress,
                showStandardProgress = showStandardProgress,
                coverEdgeOnPlaySurface = coverEdgeOnPlaySurface,
                showChromeProgressInTransition = showChromeProgressInTransition,
                chromeProgressAlpha = chromeProgressAlpha,
                spectrumOverlayAlpha = spectrumOverlayAlpha,
                lyricLineSlots = lowerSpacing.lyricLineSlots,
                hideInfoAndLyrics = input.photoStackMode,
            ),
        )
    }

    private data class CoverFlowStagePlan(
        val progress: Float,
        val active: Boolean,
    )

    private fun resolveCoverFlowStage(
        input: PlayerPageLayoutInput,
        headerFocus: Float,
    ): CoverFlowStagePlan {
        val inHeaderTransition =
            input.coverFlowModeEnabled &&
                !input.immersiveLower &&
                (
                    input.lyricsExpanded ||
                        input.queueExpanded ||
                        headerFocus > ImmersiveProgressEpsilon
                    )
        val playbackAvailable =
            input.coverFlowModeEnabled &&
                !input.lyricsExpanded &&
                !input.queueExpanded &&
                !input.immersiveLower &&
                headerFocus < 0.01f
        val progress = when {
            inHeaderTransition -> 1f - headerFocus
            playbackAvailable -> input.coverFlowProgress
            else -> 0f
        }.coerceIn(0f, 1f)
        val active = when {
            inHeaderTransition -> headerFocus < 1f - ImmersiveProgressEpsilon
            else -> progress > 0.001f
        }
        return CoverFlowStagePlan(progress = progress, active = active)
    }

    private fun computeCoverFrame(
        input: PlayerPageLayoutInput,
        density: Density,
        typography: HifiTypography,
        headerFocus: Float,
        lyricsChromeFade: Float,
        photoStackTitleBlockHeight: Dp,
        photoStackControlsHeight: Dp,
        particleHidesProgressChrome: Boolean,
    ): CoverFrame {
        if (input.particleCoverMode) {
            // 隐藏进度条后的高度四等分：标题上 / 标题-封面 / 歌词上 / 歌词下。
            // 这里只注入前两份；后两份由 computeLowerLayoutPlan 的 quarter 承担。
            val titleToCoverExtraGap = if (particleHidesProgressChrome) {
                particleHiddenProgressGap(density, typography) / 2
            } else {
                0.dp
            }
            return ParticleCoverPageLayout.computeCoverFrame(
                input = input,
                headerFocus = headerFocus,
                titleToCoverExtraGap = titleToCoverExtraGap,
            )
        }
        val (expandedCoverWidth, expandedCoverHeight) = when {
            input.photoStackMode -> {
                val immersiveFraction = input.immersiveProgress.coerceIn(0f, 1f)
                val screenFraction = PhotoStackScreenFraction +
                    (PhotoStackImmersiveScreenFraction - PhotoStackScreenFraction) * immersiveFraction
                val cardWidth = input.screenWidth * screenFraction
                cardWidth to cardWidth / PhotoStackAspectRatio
            }
            input.fitOriginal -> measurePlayerCoverFitOriginal(
                input.coverAspectRatio,
                input.screenWidth,
                input.screenHeight,
            )
            else -> input.screenWidth to input.screenWidth
        }
        val useParticleLyricsLayout =
            input.particleCoverMode &&
                headerFocus > ImmersiveProgressEpsilon &&
                input.queueProgress <= ImmersiveProgressEpsilon &&
                !input.queueExpanded
        // 拍立得进队列原位淡出，卡片几何保持休息态；blockHeight 仍 lerp 给列表 overlay。
        val pinPhotoStackGeometry = input.photoStackMode
        val coverWidth = if (useParticleLyricsLayout || pinPhotoStackGeometry) {
            expandedCoverWidth
        } else {
            lerpDp(expandedCoverWidth, LyricsFocusMiniCoverSize, headerFocus)
        }
        val coverHeight = if (useParticleLyricsLayout || pinPhotoStackGeometry) {
            expandedCoverHeight
        } else {
            lerpDp(expandedCoverHeight, LyricsFocusMiniCoverSize, headerFocus)
        }
        val photoStackViewport = if (input.photoStackMode) {
            computePhotoStackViewport(
                screenWidth = input.screenWidth,
                cardWidth = expandedCoverWidth,
                cardHeight = expandedCoverHeight,
                immersiveProgress = input.immersiveProgress,
            )
        } else {
            null
        }
        val photoStackLayout = if (input.photoStackMode) {
            computePhotoStackVerticalLayout(
                screenHeight = input.screenHeight,
                photoStackHeight = photoStackViewport!!.slotHeight,
                titleBlockHeight = photoStackTitleBlockHeight,
                controlsHeight = photoStackControlsHeight,
                immersiveProgress = input.immersiveProgress,
            )
        } else {
            PhotoStackVerticalLayout(edgeGap = 0.dp, middleGap = 0.dp)
        }
        val coverTopPadding = when {
            pinPhotoStackGeometry -> photoStackLayout.edgeGap
            else -> lerpDp(0.dp, input.statusBarTop, headerFocus)
        }
        val expandedCoverStartPadding = if (input.fitOriginal || input.photoStackMode) {
            Dp(((input.screenWidth - expandedCoverWidth).value / 2f).coerceAtLeast(0f))
        } else {
            0.dp
        }
        val coverStartPadding = if (useParticleLyricsLayout || pinPhotoStackGeometry) {
            expandedCoverStartPadding
        } else {
            lerpDp(
                expandedCoverStartPadding,
                LyricsFocusCoverStartPadding,
                headerFocus,
            )
        }
        val particleCoverBottomPadding = when {
            input.photoStackMode -> photoStackLayout.middleGap
            else -> 0.dp
        }
        val coverBlockHeight = when {
            input.photoStackMode -> lerpDp(
                photoStackViewport!!.slotHeight + coverTopPadding + particleCoverBottomPadding,
                input.statusBarTop + LyricsFocusMiniCoverSize + HifiSpacing.sm,
                headerFocus,
            )
            else -> lerpDp(
                coverHeight + coverTopPadding + particleCoverBottomPadding,
                input.statusBarTop + LyricsFocusMiniCoverSize + HifiSpacing.sm,
                headerFocus,
            )
        }
        val zoneStop = (coverBlockHeight.value / input.screenHeight.value)
            .coerceIn(0.12f, PlayerCoverMaxScreenFraction)

        val settledOnHeader =
            (input.lyricsExpanded || input.queueExpanded) &&
                lyricsChromeFade >= 1f - ImmersiveProgressEpsilon
        val letterboxAlpha = if (input.fitOriginal) {
            if (settledOnHeader) 1f else 0f
        } else {
            0f
        }

        return CoverFrame(
            width = coverWidth,
            height = coverHeight,
            startPadding = coverStartPadding,
            topPadding = coverTopPadding,
            blockHeight = coverBlockHeight,
            particleInfoTopPadding = input.statusBarTop + HifiSpacing.lg,
            letterboxAlpha = letterboxAlpha,
            zoneStop = zoneStop,
        )
    }

    private fun computeParticleCoverFrame(
        input: PlayerPageLayoutInput,
        headerFocus: Float,
    ): ParticleCoverFrame =
        ParticleCoverPageLayout.computeParticleFrame(
            input = input,
            headerFocus = headerFocus,
        )

    private fun computePhotoStackFrame(
        input: PlayerPageLayoutInput,
        cover: CoverFrame,
    ): PhotoStackFrame {
        val enabled = input.photoStackMode
        val normalLayerVisible = enabled && !input.lyricsExpanded
        val cardWidth = cover.width
        val cardHeight = cover.height
        val viewport = computePhotoStackViewport(
            screenWidth = input.screenWidth,
            cardWidth = cardWidth,
            cardHeight = cardHeight,
            immersiveProgress = input.immersiveProgress,
        )
        return PhotoStackFrame(
            enabled = enabled,
            normalLayerVisible = normalLayerVisible,
            immersiveProgress = input.immersiveProgress.coerceIn(0f, 1f),
            slotWidth = viewport.slotWidth,
            slotHeight = viewport.slotHeight,
            cardTopInset = viewport.cardTopInset,
            cardWidth = cardWidth,
            cardHeight = cardHeight,
            artworkInsetTop = cardWidth * 0.055f,
            artworkInsetHorizontal = cardWidth * PhotoStackArtworkInsetHorizontalFraction,
            artworkBottomBand = cardHeight - cardWidth - cardWidth * 0.055f,
            waveformHeight = 24.dp,
        )
    }

    private data class PhotoStackVerticalLayout(
        val edgeGap: Dp,
        val middleGap: Dp,
    )

    private data class PhotoStackViewport(
        val slotWidth: Dp,
        val slotHeight: Dp,
        val cardTopInset: Dp,
    )

    private fun computePhotoStackViewport(
        screenWidth: Dp,
        cardWidth: Dp,
        cardHeight: Dp,
        immersiveProgress: Float,
    ): PhotoStackViewport {
        val progress = immersiveProgress.coerceIn(0f, 1f)
        val availableHorizontalBleed = (screenWidth - cardWidth).coerceAtLeast(0.dp)
        val horizontalBleed = minOf(
            PhotoStackImmersiveHorizontalBleed * progress,
            availableHorizontalBleed,
        )
        val topBleed = PhotoStackImmersiveTopBleed * progress
        val bottomBleed = PhotoStackImmersiveBottomBleed * progress
        return PhotoStackViewport(
            slotWidth = cardWidth + horizontalBleed,
            slotHeight = cardHeight + topBleed + bottomBleed,
            cardTopInset = topBleed,
        )
    }

    private fun computePhotoStackTitleBlockHeight(
        density: Density,
        typography: HifiTypography,
    ): Dp {
        val titleLine = with(density) { typography.titleLg.lineHeight.toDp() }
        val subtitleLine = with(density) { typography.bodyMd.lineHeight.toDp() }
        return titleLine + HifiSpacing.sm + subtitleLine * 2
    }

    private fun computePhotoStackVerticalLayout(
        screenHeight: Dp,
        photoStackHeight: Dp,
        titleBlockHeight: Dp,
        controlsHeight: Dp,
        immersiveProgress: Float,
    ): PhotoStackVerticalLayout {
        val fixedHeight = photoStackHeight + titleBlockHeight + controlsHeight
        val availableGap = (screenHeight - fixedHeight).coerceAtLeast(0.dp)
        val desiredEdgeGap = screenHeight * PhotoStackEdgeFraction
        val normalEdgeGap = minOf(desiredEdgeGap, availableGap / 2)
        val normalMiddleGap = ((availableGap - normalEdgeGap * 2) / 2).coerceAtLeast(0.dp)
        val centeredTopGap = ((screenHeight - photoStackHeight).coerceAtLeast(0.dp) / 2) *
            PhotoStackImmersiveCenterBias
        val progress = immersiveProgress.coerceIn(0f, 1f)
        return PhotoStackVerticalLayout(
            edgeGap = lerpDp(normalEdgeGap, centeredTopGap, progress),
            middleGap = lerpDp(normalMiddleGap, 0.dp, progress),
        )
    }

    private fun computeTitleSlideDown(
        immersiveInTransition: Boolean,
        panelHeight: Dp,
        spacing: PlayerLowerPanelSpacing,
        chromeHeightAtFullImmersive: Dp,
        density: Density,
        typography: HifiTypography,
        immersiveProgress: Float,
    ): Dp {
        if (!immersiveInTransition) return 0.dp
        val infoLine = playerInfoRowHeight(density, typography)
        val titleLine = with(density) { typography.titleLg.lineHeight.toDp() }
        val subtitleLine = with(density) { typography.bodyMd.lineHeight.toDp() }
        val titleBlockHeight = titleLine + HifiSpacing.sm + subtitleLine * 2
        val titleOffset = spacing.afterCover + infoLine + spacing.afterInfo
        val metaEnd = panelHeight - chromeHeightAtFullImmersive
        val titleSlideEnd = maxOf(
            0.dp,
            metaEnd / 2 - titleOffset - titleBlockHeight / 2,
        )
        return lerpDp(0.dp, titleSlideEnd, immersiveProgress)
    }

    private data class LowerLayoutPlan(
        val showMetadata: Boolean,
        val spacing: PlayerLowerPanelSpacing,
        val chromeHeightAtRest: Dp,
        val chromeHeightAtFullImmersive: Dp,
    )

    private fun computeLowerLayoutPlan(
        density: Density,
        typography: HifiTypography,
        panelHeight: Dp,
        useCoverEdgeProgressSetting: Boolean,
        applyCoverEdgeGapCosmetics: Boolean,
        lyricsFocus: Float,
        showMetadata: Boolean,
        compactLyricsLineMode: CompactLyricsLineMode,
    ): LowerLayoutPlan {
        val infoLine = playerInfoRowHeight(density, typography)
        val titleLine = with(density) { typography.titleLg.lineHeight.toDp() }
        val subtitleLine = with(density) { typography.bodyMd.lineHeight.toDp() }
        val lyricLine = with(density) {
            (typography.lyricCurrent.lineHeight * PlayerPanelLyricScale).toDp()
        }
        val timeRowHeight = with(density) { typography.monoMd.lineHeight.toDp() }

        val iconGap = HifiSize.iconLg
        val controlHalfLine = HifiSize.touchTarget / 2
        val minGap = HifiSpacing.xs

        val idealAfterCover = infoLine / 2
        val idealAfterInfo = if (showMetadata) titleLine else 0.dp
        // 收起进度条与「底边进度」间距美化分开：粒子隐藏进度只收 seek，不套用底边 gap 微调。
        val seekCollapseWeight = if (useCoverEdgeProgressSetting) {
            1f - lyricsFocus
        } else {
            0f
        }
        val edgeGapWeight = if (applyCoverEdgeGapCosmetics) {
            seekCollapseWeight
        } else {
            0f
        }
        val idealAfterSubtitle = if (showMetadata) {
            lerpDp(
                subtitleLine,
                subtitleLine + HifiSpacing.sm,
                edgeGapWeight,
            )
        } else {
            0.dp
        }
        val idealBeforePlaybackChrome = lerpDp(
            iconGap,
            iconGap + HifiSpacing.md,
            edgeGapWeight,
        )
        val idealAfterProgress = lerpDp(iconGap / 2, 0.dp, edgeGapWeight)
        val idealAfterControls = lerpDp(
            iconGap + controlHalfLine,
            iconGap + controlHalfLine + HifiSpacing.sm,
            edgeGapWeight,
        )

        val standardSeekBarBlock = 32.dp + timeRowHeight + iconGap / 2
        val seekBarBlock = lerpDp(standardSeekBarBlock, 0.dp, seekCollapseWeight)
        val chromeIdealHeight = seekBarBlock + HifiSize.touchTarget + idealAfterControls

        val collapsedChromeIdealHeight = HifiSize.touchTarget + iconGap + controlHalfLine
        val edgeChromeIdealHeight = collapsedChromeIdealHeight + HifiSpacing.sm
        val standardChromeIdealHeight = standardSeekBarBlock + collapsedChromeIdealHeight
        val coverEdgeChromeIdealHeight =
            if (applyCoverEdgeGapCosmetics) edgeChromeIdealHeight else collapsedChromeIdealHeight
        val blendedChromeIdeal =
            lerpDp(standardChromeIdealHeight, coverEdgeChromeIdealHeight, seekCollapseWeight)

        val metaIdealGaps = idealAfterCover + idealAfterInfo + idealAfterSubtitle + idealBeforePlaybackChrome
        val metaGapCount = 4
        val metaShellFixed = if (showMetadata) {
            infoLine + titleLine + HifiSpacing.sm + subtitleLine * 2
        } else {
            0.dp
        }
        val lyricCompactLine = maxOf(lyricLine, subtitleLine)
        val lyricsBlock3 = lyricLine * 3 + HifiSpacing.playerLyricLineGap * 2
        val idealMeta3 = metaShellFixed + metaIdealGaps + lyricsBlock3
        val idealMeta1 = metaShellFixed + metaIdealGaps + lyricCompactLine

        val preferredChrome = blendedChromeIdeal
        val chromeGapFloor = lerpDp(minGap * 2, minGap, seekCollapseWeight)
        val chromeMinHeight = seekBarBlock + HifiSize.touchTarget + chromeGapFloor

        var chromeTarget = preferredChrome
        var metaAvailableHeight = (panelHeight - chromeTarget).coerceAtLeast(0.dp)
        var metaGaps: MetaGaps
        var lyricSlots: Int
        resolveMetaLayout(
            metaAvailableHeight = metaAvailableHeight,
            idealMeta3 = idealMeta3,
            idealMeta1 = idealMeta1,
            metaIdealGaps = metaIdealGaps,
            metaGapCount = metaGapCount,
            minGap = minGap,
            idealAfterCover = idealAfterCover,
            idealAfterInfo = idealAfterInfo,
            idealAfterSubtitle = idealAfterSubtitle,
            idealBeforePlaybackChrome = idealBeforePlaybackChrome,
            compactLyricsLineMode = compactLyricsLineMode,
        ).let { (gaps, slots) ->
            metaGaps = gaps
            lyricSlots = slots
        }

        var lyricsRequired = lyricsRequiredHeight(lyricSlots, lyricsBlock3, lyricCompactLine)
        var lyricsAvailable = metaAvailableHeight - metaShellHeight(metaGaps, metaShellFixed)
        if (lyricsAvailable < lyricsRequired) {
            val shortage = lyricsRequired - lyricsAvailable
            chromeTarget = maxOf(chromeMinHeight, preferredChrome - shortage)
            metaAvailableHeight = (panelHeight - chromeTarget).coerceAtLeast(0.dp)
            resolveMetaLayout(
                metaAvailableHeight = metaAvailableHeight,
                idealMeta3 = idealMeta3,
                idealMeta1 = idealMeta1,
                metaIdealGaps = metaIdealGaps,
                metaGapCount = metaGapCount,
                minGap = minGap,
                idealAfterCover = idealAfterCover,
                idealAfterInfo = idealAfterInfo,
                idealAfterSubtitle = idealAfterSubtitle,
                idealBeforePlaybackChrome = idealBeforePlaybackChrome,
                compactLyricsLineMode = compactLyricsLineMode,
            ).let { (gaps, slots) ->
                metaGaps = gaps
                lyricSlots = slots
            }
            lyricsRequired = lyricsRequiredHeight(lyricSlots, lyricsBlock3, lyricCompactLine)
            lyricsAvailable = metaAvailableHeight - metaShellHeight(metaGaps, metaShellFixed)
            if (lyricsAvailable < lyricsRequired) {
                val extraShortage = lyricsRequired - lyricsAvailable
                chromeTarget = maxOf(chromeMinHeight, chromeTarget - extraShortage)
            }
        }

        val (afterProgress, afterControls) = resolveChromeGaps(
            chromeHeight = chromeTarget,
            chromeIdealHeight = chromeIdealHeight,
            idealAfterProgress = idealAfterProgress,
            idealAfterControls = idealAfterControls,
            minGap = minGap,
        )

        return LowerLayoutPlan(
            showMetadata = showMetadata,
            spacing = PlayerLowerPanelSpacing(
                afterCover = metaGaps.afterCover,
                afterInfo = metaGaps.afterInfo,
                afterSubtitle = metaGaps.afterSubtitle,
                beforePlaybackChrome = metaGaps.beforePlaybackChrome,
                afterProgress = afterProgress,
                afterControls = afterControls,
                lyricLineSlots = lyricSlots,
            ),
            chromeHeightAtRest = chromeTarget,
            chromeHeightAtFullImmersive = 0.dp,
        )
    }

    private data class MetaGaps(
        val afterCover: Dp,
        val afterInfo: Dp,
        val afterSubtitle: Dp,
        val beforePlaybackChrome: Dp,
    )

    private fun resolveMetaLayout(
        metaAvailableHeight: Dp,
        idealMeta3: Dp,
        idealMeta1: Dp,
        metaIdealGaps: Dp,
        metaGapCount: Int,
        minGap: Dp,
        idealAfterCover: Dp,
        idealAfterInfo: Dp,
        idealAfterSubtitle: Dp,
        idealBeforePlaybackChrome: Dp,
        compactLyricsLineMode: CompactLyricsLineMode,
    ): Pair<MetaGaps, Int> {
        val preferThree = when (compactLyricsLineMode) {
            CompactLyricsLineMode.AUTO -> metaAvailableHeight >= idealMeta3
            CompactLyricsLineMode.THREE -> true
            CompactLyricsLineMode.ONE -> false
        }
        if (preferThree) {
            if (metaAvailableHeight >= idealMeta3) {
                val bonus = (metaAvailableHeight - idealMeta3) / 2 / metaGapCount
                return MetaGaps(
                    afterCover = idealAfterCover + bonus,
                    afterInfo = idealAfterInfo + bonus,
                    afterSubtitle = idealAfterSubtitle + bonus,
                    beforePlaybackChrome = idealBeforePlaybackChrome + bonus,
                ) to 3
            }
            val compressed = compressGaps(
                deficit = idealMeta3 - metaAvailableHeight,
                idealGaps = metaIdealGaps,
                gapCount = metaGapCount,
                minGap = minGap,
                ideals = listOf(idealAfterCover, idealAfterInfo, idealAfterSubtitle, idealBeforePlaybackChrome),
            )
            return MetaGaps(
                afterCover = compressed[0],
                afterInfo = compressed[1],
                afterSubtitle = compressed[2],
                beforePlaybackChrome = compressed[3],
            ) to 3
        }
        if (metaAvailableHeight >= idealMeta1) {
            val bonus = if (compactLyricsLineMode == CompactLyricsLineMode.ONE) {
                (metaAvailableHeight - idealMeta1) / 2 / metaGapCount
            } else {
                0.dp
            }
            return MetaGaps(
                afterCover = idealAfterCover + bonus,
                afterInfo = idealAfterInfo + bonus,
                afterSubtitle = idealAfterSubtitle + bonus,
                beforePlaybackChrome = idealBeforePlaybackChrome + bonus,
            ) to 1
        }
        val compressed = compressGaps(
            deficit = idealMeta1 - metaAvailableHeight,
            idealGaps = metaIdealGaps,
            gapCount = metaGapCount,
            minGap = minGap,
            ideals = listOf(idealAfterCover, idealAfterInfo, idealAfterSubtitle, idealBeforePlaybackChrome),
        )
        return MetaGaps(
            afterCover = compressed[0],
            afterInfo = compressed[1],
            afterSubtitle = compressed[2],
            beforePlaybackChrome = compressed[3],
        ) to 1
    }

    private fun resolveChromeGaps(
        chromeHeight: Dp,
        chromeIdealHeight: Dp,
        idealAfterProgress: Dp,
        idealAfterControls: Dp,
        minGap: Dp,
    ): Pair<Dp, Dp> {
        if (chromeHeight >= chromeIdealHeight) {
            return idealAfterProgress to idealAfterControls
        }
        val chromeGaps = idealAfterProgress + idealAfterControls
        if (chromeGaps <= 0.dp) return idealAfterProgress to idealAfterControls
        if (chromeGaps <= minGap * 2) return minGap to minGap
        val deficit = chromeIdealHeight - chromeHeight
        val shrinkable = (chromeGaps - minGap * 2).coerceAtLeast(0.dp)
        val gapShrink = deficit.coerceAtMost(shrinkable)
        val ratio = ((chromeGaps - gapShrink).value / chromeGaps.value).coerceIn(0f, 1f)
        fun scale(ideal: Dp) = maxOf(minGap, ideal * ratio)
        return scale(idealAfterProgress) to scale(idealAfterControls)
    }

    private fun metaShellHeight(gaps: MetaGaps, metaShellFixed: Dp): Dp =
        gaps.afterCover + gaps.afterInfo + gaps.afterSubtitle + gaps.beforePlaybackChrome + metaShellFixed

    private fun lyricsRequiredHeight(
        lyricSlots: Int,
        lyricsBlock3: Dp,
        lyricCompactLine: Dp,
    ): Dp = if (lyricSlots >= 3) lyricsBlock3 else lyricCompactLine

    /** 粒子封面隐藏进度条后，把原进度区高度挪到标题与封面之间。 */
    private fun particleHiddenProgressGap(
        density: Density,
        typography: HifiTypography,
    ): Dp {
        val timeRowHeight = with(density) { typography.monoMd.lineHeight.toDp() }
        return 32.dp + timeRowHeight + HifiSize.iconLg / 2
    }

    private fun compressGaps(
        deficit: Dp,
        idealGaps: Dp,
        gapCount: Int,
        minGap: Dp,
        ideals: List<Dp>,
    ): List<Dp> {
        if (idealGaps <= minGap * gapCount) {
            return List(gapCount) { minGap }
        }
        val shrinkable = (idealGaps - minGap * gapCount).coerceAtLeast(0.dp)
        val gapShrink = deficit.coerceAtMost(shrinkable)
        val ratio = if (idealGaps > 0.dp) {
            ((idealGaps - gapShrink).value / idealGaps.value).coerceIn(0f, 1f)
        } else {
            1f
        }
        return ideals.map { ideal -> maxOf(minGap, ideal * ratio) }
    }
}
