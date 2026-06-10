package com.mica.music.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color

/** 单行居中标题；过长时横向滚动。 */
@Composable
fun MarqueeTitleText(
    text: String,
    style: TextStyle,
    color: Color,
    lineHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(lineHeight)
            .marqueeHorizontalEdgeFade(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            textAlign = TextAlign.Center,
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
