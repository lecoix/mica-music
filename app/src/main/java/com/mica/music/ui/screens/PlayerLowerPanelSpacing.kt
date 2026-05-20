package com.mica.music.ui.screens



import androidx.compose.runtime.Composable

import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.unit.Dp

import androidx.compose.ui.unit.dp

import com.mica.music.ui.theme.HifiSize

import com.mica.music.ui.theme.HifiSpacing

import com.mica.music.ui.theme.MicaTheme



internal enum class PlayerLowerLayoutMode {

    /** 常规进度条 + 时间 + 控制区 */

    STANDARD,

    /** 封面底边进度：无下方进度条/时间，歌词与控制区单独排布 */

    COVER_EDGE_PROGRESS,

}



internal data class PlayerLowerPanelSpacing(

    val afterCover: Dp,

    val afterInfo: Dp,

    val afterSubtitle: Dp,

    /** 歌词区与下方区块之间（常规模式下为进度条前，封面底边模式下为控制按钮前） */

    val beforePlaybackChrome: Dp,

    /** 仅常规模式：进度条与控制区之间 */

    val afterProgress: Dp,

    val afterControls: Dp,

)



/**

 * 按理想间距布局；不足时按比例压缩。封面底边进度模式会去掉进度条/时间占位，

 * 并把腾出的高度补给歌词区与「歌词 ↔ 控制按钮」间距。

 */

@Composable

internal fun rememberPlayerLowerPanelSpacing(

    availableHeight: Dp,

    layoutMode: PlayerLowerLayoutMode = PlayerLowerLayoutMode.STANDARD,

): PlayerLowerPanelSpacing {

    val coverEdge = layoutMode == PlayerLowerLayoutMode.COVER_EDGE_PROGRESS

    val density = LocalDensity.current

    val typography = MicaTheme.typography



    val infoLine = with(density) { typography.monoMd.lineHeight.toDp() }

    val titleLine = with(density) { typography.titleLg.lineHeight.toDp() }

    val subtitleLine = with(density) { typography.bodyMd.lineHeight.toDp() }

    val lyricLine = with(density) { typography.bodySm.lineHeight.toDp() }



    val iconGap = HifiSize.iconLg

    val controlHalfLine = HifiSize.touchTarget / 2

    val minGap = HifiSpacing.xs



    val seekBarBlock = if (coverEdge) 0.dp else 32.dp

    val timeRowHeight = if (coverEdge) 0.dp else with(density) { typography.monoMd.lineHeight.toDp() }



    // 封面底边与 Hi‑Fi 信息行之间（默认约为原一行间距的一半）
    val idealAfterCover = infoLine / 2

    val idealAfterInfo = titleLine

    val idealAfterSubtitle = if (coverEdge) subtitleLine + HifiSpacing.sm else subtitleLine

    val idealBeforePlaybackChrome = if (coverEdge) {

        iconGap + HifiSpacing.md

    } else {

        iconGap

    }

    val idealAfterProgress = if (coverEdge) 0.dp else iconGap / 2

    val idealAfterControls = if (coverEdge) {

        iconGap + controlHalfLine + HifiSpacing.sm

    } else {

        iconGap + controlHalfLine

    }



    val idealGaps = idealAfterCover + idealAfterInfo + idealAfterSubtitle +

        idealBeforePlaybackChrome + idealAfterProgress + idealAfterControls

    val gapCount = if (coverEdge) 5 else 6



    val fixedBlocks = infoLine +

        titleLine +

        subtitleLine +

        HifiSpacing.xs +

        seekBarBlock +

        timeRowHeight +

        HifiSize.touchTarget



    val minLyrics = lyricLine * 3 + HifiSpacing.xs * 2

    val idealTotal = fixedBlocks + idealGaps + minLyrics



    if (availableHeight >= idealTotal) {

        val gapExtra = (availableHeight - idealTotal) / 2

        val bonus = gapExtra / gapCount

        return PlayerLowerPanelSpacing(

            afterCover = idealAfterCover + bonus,

            afterInfo = idealAfterInfo + bonus,

            afterSubtitle = idealAfterSubtitle + bonus,

            beforePlaybackChrome = idealBeforePlaybackChrome + bonus,

            afterProgress = idealAfterProgress + bonus,

            afterControls = idealAfterControls + bonus,

        )

    }



    if (idealGaps <= minGap * gapCount) {

        return PlayerLowerPanelSpacing(

            afterCover = minGap,

            afterInfo = minGap,

            afterSubtitle = minGap,

            beforePlaybackChrome = minGap,

            afterProgress = if (coverEdge) 0.dp else minGap,

            afterControls = minGap,

        )

    }



    val deficit = idealTotal - availableHeight

    val shrinkable = (idealGaps - minGap * gapCount).coerceAtLeast(0.dp)

    val gapShrink = deficit.coerceAtMost(shrinkable)

    val ratio = if (idealGaps > 0.dp) {

        ((idealGaps - gapShrink).value / idealGaps.value).coerceIn(0f, 1f)

    } else {

        1f

    }



    fun scale(ideal: Dp): Dp = maxOf(minGap, ideal * ratio)



    return PlayerLowerPanelSpacing(

        afterCover = scale(idealAfterCover),

        afterInfo = scale(idealAfterInfo),

        afterSubtitle = scale(idealAfterSubtitle),

        beforePlaybackChrome = scale(idealBeforePlaybackChrome),

        afterProgress = if (coverEdge) 0.dp else scale(idealAfterProgress),

        afterControls = scale(idealAfterControls),

    )

}


