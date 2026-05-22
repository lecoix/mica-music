package com.mica.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.HifiTypography
import com.mica.music.ui.theme.MicaTheme

internal enum class PlayerLowerLayoutMode {
    /** 常规进度条 + 时间 + 控制区 */
    STANDARD,
    /** 封面底边进度：进度在封面上，底栏仅五按钮 */
    COVER_EDGE_PROGRESS,
}

internal data class PlayerLowerPanelSpacing(
    val afterCover: Dp,
    val afterInfo: Dp,
    val afterSubtitle: Dp,
    val beforePlaybackChrome: Dp,
    val afterProgress: Dp,
    val afterControls: Dp,
    val lyricLineSlots: Int,
)

/** 冻结后的布局快照：间距 + 底栏起止高度（计算在 [computePlayerLowerLayout]，动画只 lerp 高度） */
internal data class PlayerLowerLayoutPlan(
    val spacing: PlayerLowerPanelSpacing,
    val chromeHeightAtRest: Dp,
    val chromeHeightAtFullImmersive: Dp,
)

internal data class PlayerLowerLayoutState(
    val spacing: PlayerLowerPanelSpacing,
    val chromeHeight: Dp,
    val chromeHeightAtFullImmersive: Dp,
)

@Composable
internal fun rememberPlayerLowerLayout(
    panelHeight: Dp,
    layoutMode: PlayerLowerLayoutMode,
    immersiveProgress: Float,
    useCoverEdgeProgressSetting: Boolean = false,
    lyricsFocus: Float = 0f,
    lyricsCoverMorphEndFocus: Float = 0.05f,
    freezeSpacing: Boolean = false,
): PlayerLowerLayoutState {
    val density = LocalDensity.current
    val typography = MicaTheme.typography
    val livePlan = remember(
        panelHeight,
        layoutMode,
        useCoverEdgeProgressSetting,
        lyricsFocus,
        lyricsCoverMorphEndFocus,
        density,
        typography,
    ) {
        computePlayerLowerLayout(
            density = density,
            typography = typography,
            panelHeight = panelHeight,
            layoutMode = layoutMode,
            useCoverEdgeProgressSetting = useCoverEdgeProgressSetting,
            lyricsFocus = lyricsFocus,
            lyricsCoverMorphEndFocus = lyricsCoverMorphEndFocus,
        )
    }

    val activePlan = if (!freezeSpacing) {
        livePlan
    } else {
        remember(panelHeight, layoutMode, useCoverEdgeProgressSetting, freezeSpacing) {
            livePlan
        }
    }

    // 进出沉浸均跟 immersiveProgress；勿用 immersiveLower 开关，否则退出时 progress 仍在动画会瞬跳
    val chromeHeight = lerpDp(
        activePlan.chromeHeightAtRest,
        activePlan.chromeHeightAtFullImmersive,
        immersiveProgress.coerceIn(0f, 1f),
    )

    return PlayerLowerLayoutState(
        spacing = activePlan.spacing,
        chromeHeight = chromeHeight,
        chromeHeightAtFullImmersive = activePlan.chromeHeightAtFullImmersive,
    )
}

/**
 * 非沉浸态全量布局（底栏按满高计算）。
 * 沉浸动画不得在此函数内传入正在变化的 progress 重算间距。
 */
internal fun computePlayerLowerLayout(
    density: Density,
    typography: HifiTypography,
    panelHeight: Dp,
    layoutMode: PlayerLowerLayoutMode,
    useCoverEdgeProgressSetting: Boolean,
    lyricsFocus: Float,
    lyricsCoverMorphEndFocus: Float,
): PlayerLowerLayoutPlan {
    val coverEdge = layoutMode == PlayerLowerLayoutMode.COVER_EDGE_PROGRESS

    val infoLine = with(density) { typography.monoMd.lineHeight.toDp() }
    val titleLine = with(density) { typography.titleLg.lineHeight.toDp() }
    val subtitleLine = with(density) { typography.bodyMd.lineHeight.toDp() }
    val lyricLine = with(density) { typography.bodySm.lineHeight.toDp() }
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
        1f - (lyricsFocus / lyricsCoverMorphEndFocus).coerceIn(0f, 1f)
    } else {
        0f
    }
    val blendedChromeIdeal = lerpDp(standardChromeIdealHeight, edgeChromeIdealHeight, edgeWeight)

    val metaIdealGaps = idealAfterCover + idealAfterInfo + idealAfterSubtitle + idealBeforePlaybackChrome
    val metaGapCount = 4

    val metaShellFixed = infoLine + titleLine + HifiSpacing.sm + subtitleLine * 2
    val lyricCompactLine = maxOf(lyricLine, subtitleLine)
    val lyricsBlock3 = lyricLine * 3 + HifiSpacing.xs * 2

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

    val chromeHeightAtFullImmersive = Dp(blendedChromeIdeal.value * 0f).coerceAtLeast(0.dp)

    return PlayerLowerLayoutPlan(
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
        chromeHeightAtFullImmersive = chromeHeightAtFullImmersive,
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
    if (chromeGaps <= minGap * 2) {
        return minGap to minGap
    }
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
