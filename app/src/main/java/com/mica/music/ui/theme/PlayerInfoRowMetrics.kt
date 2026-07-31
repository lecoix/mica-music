package com.mica.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/** 播放页信息行布局高度：与 [HifiTypography.monoMd] 行高一致；标志可在此槽位外有限溢出绘制。 */
fun playerInfoRowHeight(density: Density, typography: HifiTypography): Dp =
    with(density) { typography.monoMd.lineHeight.toDp() }

/** 标志实际绘制高度：不超过 [HifiSize.hiResBadgeDisplayHeight]，且上下溢出各 ≤ [HifiSize.hiResBadgeMaxOverflowPerSide]。 */
fun hiResBadgeVisualHeight(rowHeight: Dp): Dp =
    minOf(
        HifiSize.hiResBadgeDisplayHeight,
        rowHeight + HifiSize.hiResBadgeMaxOverflowPerSide * 2,
    )

@Composable
fun rememberPlayerInfoRowHeight(): Dp {
    val density = LocalDensity.current
    val typography = MicaTheme.typography
    return remember(density, typography) { playerInfoRowHeight(density, typography) }
}

@Composable
fun rememberHiResBadgeVisualHeight(rowHeight: Dp): Dp =
    remember(rowHeight) { hiResBadgeVisualHeight(rowHeight) }

/** 自定义播放页布局计算器使用的 monoMd 默认行高（dp）。 */
internal const val CustomPlayerInfoRowHeightDp = 16f
