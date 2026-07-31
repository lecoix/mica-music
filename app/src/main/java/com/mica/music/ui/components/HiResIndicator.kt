package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mica.music.data.HiResBadgeAppearance
import com.mica.music.data.HiResBadgeStyle
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.rememberHiResBadgeVisualHeight
import com.mica.music.ui.theme.rememberPlayerInfoRowHeight
import java.io.File

@Composable
fun HiResIndicator(
    modifier: Modifier = Modifier,
    appearance: HiResBadgeAppearance = HiResBadgeAppearance(),
    label: String = "Hi-Res",
    rowHeight: Dp = rememberPlayerInfoRowHeight(),
    visualHeight: Dp = rememberHiResBadgeVisualHeight(rowHeight),
) {
    when (appearance.style) {
        HiResBadgeStyle.CUSTOM_IMAGE -> {
            val imageFile = appearance.customImagePath
                ?.let(::File)
                ?.takeIf { it.isFile }
            if (imageFile != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageFile)
                        .build(),
                    contentDescription = label,
                    contentScale = ContentScale.Fit,
                    modifier = modifier
                        .graphicsLayer { clip = false }
                        .hiResBadgeOverflowLayout(rowHeight, visualHeight)
                        .height(visualHeight),
                )
            } else {
                DefaultHiResBadgeSlot(
                    modifier = modifier,
                    rowHeight = rowHeight,
                    label = label,
                )
            }
        }
        HiResBadgeStyle.GOLD_LABEL -> GoldHiResBadgeSlot(
            modifier = modifier,
            rowHeight = rowHeight,
            label = label,
        )
        HiResBadgeStyle.DEFAULT -> DefaultHiResBadgeSlot(
            modifier = modifier,
            rowHeight = rowHeight,
            label = label,
        )
    }
}

@Composable
private fun DefaultHiResBadgeSlot(
    modifier: Modifier,
    rowHeight: Dp,
    label: String,
) {
    Box(
        modifier = modifier.height(rowHeight),
        contentAlignment = Alignment.Center,
    ) {
        DefaultHiResBadgeContent(label = label)
    }
}

@Composable
private fun GoldHiResBadgeSlot(
    modifier: Modifier,
    rowHeight: Dp,
    label: String,
) {
    val textStyle = MicaTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold)
    val textMeasurer = rememberTextMeasurer()
    val textLayout = remember(label, textStyle) {
        textMeasurer.measure(label, textStyle)
    }
    val horizontalPadding = HifiSpacing.xxs
    val density = LocalDensity.current
    val badgeWidth = with(density) {
        textLayout.size.width.toDp() + horizontalPadding * 2
    }
    Box(
        modifier = modifier
            .height(rowHeight)
            .width(badgeWidth)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawRect(color = HifiPalette.HiResGold, size = size)
                val x = (size.width - textLayout.size.width) / 2f
                val y = (size.height - textLayout.size.height) / 2f
                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(x, y),
                    color = Color.White,
                    blendMode = BlendMode.DstOut,
                )
            },
    )
}

@Composable
private fun DefaultHiResBadgeContent(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
    ) {
        Box(
            Modifier
                .size(HifiSize.hiResDot)
                .background(MicaTheme.colors.hiRes),
        )
        Text(
            text = label,
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.hiRes,
        )
    }
}

/**
 * 向 Row 报告 [rowHeight] 占位，但按 [visualHeight] 测量并居中绘制，使超出部分溢出而不撑高信息行。
 */
private fun Modifier.hiResBadgeOverflowLayout(
    rowHeight: Dp,
    visualHeight: Dp,
): Modifier = layout { measurable, constraints ->
    val rowPx = rowHeight.roundToPx()
    val visualPx = visualHeight.roundToPx()
    val maxWidth = when {
        constraints.maxWidth == Constraints.Infinity -> visualPx * 6
        else -> constraints.maxWidth
    }
    val placeable = measurable.measure(
        Constraints(
            minWidth = 0,
            maxWidth = maxWidth,
            minHeight = visualPx,
            maxHeight = visualPx,
        ),
    )
    layout(
        width = placeable.width.coerceIn(0, maxWidth),
        height = rowPx,
    ) {
        placeable.place(0, (rowPx - visualPx) / 2)
    }
}
