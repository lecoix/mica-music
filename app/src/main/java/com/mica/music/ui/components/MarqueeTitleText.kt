package com.mica.music.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

/** 单行标题；过长时横向滚动。[textAlign] 决定不滚动时文字停靠的位置。 */
@Composable
fun MarqueeTitleText(
    text: String,
    style: TextStyle,
    color: Color,
    lineHeight: Dp,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
) {
    var containerWidthPx by remember { mutableIntStateOf(0) }
    var textWidthPx by remember { mutableIntStateOf(0) }
    // 滚动时文字会跨越两侧边界，两边都要渐隐；静止时只渐隐文字不会停靠的那一侧，
    // 否则靠左/靠右的标题首尾会被 28dp 的渐隐吃掉。
    val scrolling = containerWidthPx > 0 && textWidthPx > containerWidthPx
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(lineHeight)
            .onSizeChanged { containerWidthPx = it.width }
            .marqueeHorizontalEdgeFade(
                fadeLeft = scrolling || textAlign != TextAlign.Start,
                fadeRight = scrolling || textAlign != TextAlign.End,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = style.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = true),
            ),
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            textAlign = textAlign,
            onTextLayout = { textWidthPx = it.size.width },
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 1_200,
                    repeatDelayMillis = 1_200,
                ),
        )
    }
}

@Composable
fun textLineHeightDp(style: TextStyle): Dp {
    val density = LocalDensity.current
    return with(density) { style.lineHeight.toDp() }
}
