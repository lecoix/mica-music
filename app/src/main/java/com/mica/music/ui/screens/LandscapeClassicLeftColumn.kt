package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.HifiSpacing

/**
 * 横屏经典列表左栏封面尺寸：优先 [maxCoverSize]，在槽位不足时随剩余高度收缩，避免与标题/控件重叠。
 */
internal fun resolveLandscapeClassicCoverSize(
    maxCoverSize: Dp,
    slotWidth: Dp,
    slotHeight: Dp,
): Dp = minOf(maxCoverSize, slotWidth, slotHeight).coerceAtLeast(0.dp)

/**
 * 横屏经典列表歌词页左栏：封面占剩余空间（可压缩），标题与控件按固有/固定高度贴底排列。
 */
@Composable
internal fun LandscapeClassicLeftColumn(
    maxCoverSize: Dp,
    modifier: Modifier = Modifier,
    contentGap: Dp = HifiSpacing.sm,
    onCoverBoundsResolved: (Rect) -> Unit = {},
    coverContent: @Composable (effectiveCoverSize: Dp, coverModifier: Modifier) -> Unit,
    titleContent: @Composable () -> Unit,
    chromeContent: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth(),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val coverSize = resolveLandscapeClassicCoverSize(
                        maxCoverSize = maxCoverSize,
                        slotWidth = maxWidth,
                        slotHeight = maxHeight,
                    )
                    Box(
                        modifier = Modifier
                            .size(coverSize)
                            .align(Alignment.Center)
                            .onGloballyPositioned { coordinates ->
                                onCoverBoundsResolved(coordinates.boundsInRoot())
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        coverContent(coverSize, Modifier.fillMaxSize())
                    }
                }
            }
            Spacer(Modifier.height(contentGap))
            titleContent()
            Spacer(Modifier.height(contentGap))
            chromeContent()
        }
    }
}
