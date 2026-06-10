package com.mica.music.ui.screens.player

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.mica.music.ui.components.PlayerCoverMaxScreenFraction
import com.mica.music.ui.components.PlayerPanelLyricScale
import com.mica.music.ui.components.measurePlayerCoverFitOriginal
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.HifiTypography

/**
 * 纯布局计算：给定动画进度与尺寸，原子产出 [PlayerPageFrame]。
 * 不含 freeze / 快照；歌词、沉浸、封面底边进度在同一函数内 lerp。
 */
object PlayerPageLayoutEngine {

    fun computeFrame(
        input: PlayerPageLayoutInput,
        density: Density,
        typography: HifiTypography,
    ): PlayerPageFrame {
        val lyricsFocus = input.lyricsProgress.coerceIn(0f, 1f)
        val immersiveProgress = input.immersiveProgress.coerceIn(0f, 1f)
        val lyricsChromeFade = input.lyricsChromeFade.coerceIn(0f, 1f)

        val layoutActive =
            input.lyricsExpanded ||
                lyricsFocus > ImmersiveProgressEpsilon ||
                (input.useCoverEdgeProgress && lyricsChromeFade > ImmersiveProgressEpsilon)
        val coverEdgeOnPlaySurface = input.useCoverEdgeProgress && !layoutActive

        val coverFlowAvailable =
            input.coverFlowModeEnabled &&
                !input.lyricsExpanded &&
                !input.immersiveLower &&
                lyricsFocus < 0.01f
        val coverFlowProgress = if (coverFlowAvailable) {
            input.coverFlowProgress.coerceIn(0f, 1f)
        } else {
            0f
        }
        val coverFlowStageActive = coverFlowProgress > 0.001f

        val cover = computeCoverFrame(
            input = input,
            lyricsFocus = lyricsFocus,
            lyricsChromeFade = lyricsChromeFade,
        )

        val lowerPlan = computeLowerLayoutPlan(
            density = density,
            typography = typography,
            panelHeight = input.panelHeight,
            useCoverEdgeProgressSetting = coverEdgeOnPlaySurface,
            lyricsFocus = lyricsFocus,
        )

        val chromeHeight = lerpDp(
            lowerPlan.chromeHeightAtRest,
            lowerPlan.chromeHeightAtFullImmersive,
            immersiveProgress,
        )
        val controlsBottomPadding =
            lowerPlan.spacing.afterControls * lyricsChromeBottomInsetScale(lyricsFocus)

        val immersiveInTransition =
            input.immersiveLower || immersiveProgress > ImmersiveProgressEpsilon
        val titleSlideDown = computeTitleSlideDown(
            immersiveInTransition = immersiveInTransition,
            panelHeight = input.panelHeight,
            spacing = lowerPlan.spacing,
            chromeHeightAtFullImmersive = lowerPlan.chromeHeightAtFullImmersive,
            density = density,
            typography = typography,
            immersiveProgress = immersiveProgress,
        )

        val showChromeProgressInTransition =
            input.useCoverEdgeProgress &&
                !coverEdgeOnPlaySurface &&
                (lyricsFocus > ImmersiveProgressEpsilon || lyricsChromeFade > ImmersiveProgressEpsilon)
        val showStandardProgress =
            !coverEdgeOnPlaySurface &&
                (!input.useCoverEdgeProgress || lyricsFocus > 1f - ImmersiveProgressEpsilon)
        val chromeProgressAlpha = when {
            !input.useCoverEdgeProgress -> 1f
            coverEdgeOnPlaySurface -> 0f
            else -> {
                val transitionProgress = if (input.lyricsExpanded) {
                    maxOf(lyricsChromeFade, lyricsFocus)
                } else {
                    minOf(lyricsChromeFade, lyricsFocus)
                }
                (transitionProgress / CoverEdgeChromeProgressFadeEnd).coerceIn(0f, 1f)
            }
        }

        val metaAlpha = 1f - lyricsFocus
        val spectrumOverlayAlpha =
            metaAlpha.coerceIn(0f, 1f) * (1f - immersiveProgress)
        val spectrumEnabled =
            input.spectrumSettingEnabled &&
                !input.spectrumDeferred &&
                !input.coverSwitching &&
                immersiveProgress <= ImmersiveProgressEpsilon

        val gesturesEnabled =
            !input.lyricsExpanded &&
                !input.immersiveLower &&
                lyricsFocus < 0.01f

        val scene = when {
            input.lyricsExpanded || lyricsFocus > ImmersiveProgressEpsilon -> PlayerPageScene.Lyrics
            input.immersiveLower || immersiveProgress > ImmersiveProgressEpsilon -> PlayerPageScene.Immersive
            else -> PlayerPageScene.Normal
        }

        return PlayerPageFrame(
            scene = scene,
            lyricsProgress = lyricsFocus,
            immersiveProgress = immersiveProgress,
            coverFlowProgress = coverFlowProgress,
            coverFlowStageActive = coverFlowStageActive,
            gesturesEnabled = gesturesEnabled,
            spectrumEnabled = spectrumEnabled,
            cover = cover,
            lower = LowerPanelFrame(
                spacing = lowerPlan.spacing,
                chromeHeight = maxOf(0.dp, chromeHeight - lyricsChromeDrop(lyricsFocus)),
                controlsBottomPadding = controlsBottomPadding,
                titleSlideDown = titleSlideDown,
                metaAlpha = metaAlpha,
                lyricsChromeFade = lyricsChromeFade,
                lyricsLayoutFocus = lyricsFocus,
                immersiveProgress = immersiveProgress,
                showStandardProgress = showStandardProgress,
                coverEdgeOnPlaySurface = coverEdgeOnPlaySurface,
                showChromeProgressInTransition = showChromeProgressInTransition,
                chromeProgressAlpha = chromeProgressAlpha,
                spectrumOverlayAlpha = spectrumOverlayAlpha,
                lyricLineSlots = lowerPlan.spacing.lyricLineSlots,
            ),
        )
    }

    private fun computeCoverFrame(
        input: PlayerPageLayoutInput,
        lyricsFocus: Float,
        lyricsChromeFade: Float,
    ): CoverFrame {
        val coverTopPadding = lerpDp(0.dp, input.statusBarTop, lyricsFocus)
        val (expandedCoverWidth, expandedCoverHeight) = if (input.fitOriginal) {
            measurePlayerCoverFitOriginal(
                input.coverAspectRatio,
                input.screenWidth,
                input.screenHeight,
            )
        } else {
            input.screenWidth to input.screenWidth
        }
        val coverWidth = lerpDp(expandedCoverWidth, LyricsFocusMiniCoverSize, lyricsFocus)
        val coverHeight = lerpDp(expandedCoverHeight, LyricsFocusMiniCoverSize, lyricsFocus)
        val expandedCoverStartPadding = if (input.fitOriginal) {
            Dp(((input.screenWidth - expandedCoverWidth).value / 2f).coerceAtLeast(0f))
        } else {
            0.dp
        }
        val coverStartPadding = lerpDp(
            expandedCoverStartPadding,
            LyricsFocusCoverStartPadding,
            lyricsFocus,
        )
        val coverBlockHeight = lerpDp(
            coverHeight + coverTopPadding,
            input.statusBarTop + LyricsFocusMiniCoverSize + HifiSpacing.sm,
            lyricsFocus,
        )
        val zoneStop = (coverBlockHeight.value / input.screenHeight.value)
            .coerceIn(0.12f, PlayerCoverMaxScreenFraction)

        val settledOnLyrics =
            input.lyricsExpanded && lyricsChromeFade >= 1f - ImmersiveProgressEpsilon
        val letterboxAlpha = if (input.fitOriginal) {
            if (settledOnLyrics) 1f else 0f
        } else {
            0f
        }

        return CoverFrame(
            width = coverWidth,
            height = coverHeight,
            startPadding = coverStartPadding,
            topPadding = coverTopPadding,
            blockHeight = coverBlockHeight,
            letterboxAlpha = letterboxAlpha,
            zoneStop = zoneStop,
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
        val infoLine = with(density) { typography.monoMd.lineHeight.toDp() }
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
        val spacing: PlayerLowerPanelSpacing,
        val chromeHeightAtRest: Dp,
        val chromeHeightAtFullImmersive: Dp,
    )

    private fun computeLowerLayoutPlan(
        density: Density,
        typography: HifiTypography,
        panelHeight: Dp,
        useCoverEdgeProgressSetting: Boolean,
        lyricsFocus: Float,
    ): LowerLayoutPlan {
        val coverEdge = useCoverEdgeProgressSetting && lyricsFocus < 0.01f

        val infoLine = with(density) { typography.monoMd.lineHeight.toDp() }
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
        val idealAfterInfo = titleLine
        val idealAfterSubtitle = if (coverEdge) subtitleLine + HifiSpacing.sm else subtitleLine
        val idealBeforePlaybackChrome = if (coverEdge) iconGap + HifiSpacing.md else iconGap
        val idealAfterProgress = if (coverEdge) 0.dp else iconGap / 2
        val idealAfterControls = if (coverEdge) {
            iconGap + controlHalfLine + HifiSpacing.sm
        } else {
            iconGap + controlHalfLine
        }

        val seekBarBlock = if (coverEdge) 0.dp else 32.dp + timeRowHeight + iconGap / 2
        val chromeIdealHeight = seekBarBlock + HifiSize.touchTarget + idealAfterControls

        val edgeChromeIdealHeight = HifiSize.touchTarget + (iconGap + controlHalfLine + HifiSpacing.sm)
        val standardChromeIdealHeight = (32.dp + timeRowHeight + iconGap / 2) +
            HifiSize.touchTarget + (iconGap + controlHalfLine)
        val edgeWeight = if (useCoverEdgeProgressSetting) {
            1f - (lyricsFocus / LyricsCoverMorphEndFocus).coerceIn(0f, 1f)
        } else {
            0f
        }
        val blendedChromeIdeal = lerpDp(standardChromeIdealHeight, edgeChromeIdealHeight, edgeWeight)

        val metaIdealGaps = idealAfterCover + idealAfterInfo + idealAfterSubtitle + idealBeforePlaybackChrome
        val metaGapCount = 4
        val metaShellFixed = infoLine + titleLine + HifiSpacing.sm + subtitleLine * 2
        val lyricCompactLine = maxOf(lyricLine, subtitleLine)
        val lyricsBlock3 = lyricLine * 3 + HifiSpacing.playerLyricLineGap * 2
        val idealMeta3 = metaShellFixed + metaIdealGaps + lyricsBlock3
        val idealMeta1 = metaShellFixed + metaIdealGaps + lyricCompactLine

        val preferredChrome = blendedChromeIdeal
        val chromeGapFloor = if (coverEdge) minGap else minGap * 2
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
    ): Pair<MetaGaps, Int> {
        if (metaAvailableHeight >= idealMeta3) {
            val bonus = (metaAvailableHeight - idealMeta3) / 2 / metaGapCount
            return MetaGaps(
                afterCover = idealAfterCover + bonus,
                afterInfo = idealAfterInfo + bonus,
                afterSubtitle = idealAfterSubtitle + bonus,
                beforePlaybackChrome = idealBeforePlaybackChrome + bonus,
            ) to 3
        }
        if (metaAvailableHeight >= idealMeta1) {
            return MetaGaps(
                afterCover = idealAfterCover,
                afterInfo = idealAfterInfo,
                afterSubtitle = idealAfterSubtitle,
                beforePlaybackChrome = idealBeforePlaybackChrome,
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
