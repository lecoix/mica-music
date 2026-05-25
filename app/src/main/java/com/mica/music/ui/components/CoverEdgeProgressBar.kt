package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 封面底边进度触摸区高度；布局需为封面块额外预留同等高度。 */
val CoverEdgeProgressTouchHeight = 20.dp

/**
 * 封面底边进度：屏宽、细线，仅绘制已播放段（无底层轨道）。
 * 触摸区域略高于可见条，便于拖动 seek。
 */
@Composable
fun CoverEdgeProgressBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    progressColor: Color,
    modifier: Modifier = Modifier,
    barHeight: Dp = 2.dp,
    touchHeight: Dp = CoverEdgeProgressTouchHeight,
) {
    val min = valueRange.start
    val max = valueRange.endInclusive.coerceAtLeast(min + 1f)
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)

    var barWidthPx by remember { mutableFloatStateOf(0f) }

    fun positionToValue(x: Float): Float {
        if (barWidthPx <= 0f) return value
        val f = (x / barWidthPx).coerceIn(0f, 1f)
        return min + f * (max - min)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(touchHeight)
            .onSizeChanged { barWidthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onValueChange(positionToValue(offset.x))
                    onValueChangeFinished()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onValueChangeFinished() },
                    onDragCancel = { onValueChangeFinished() },
                ) { change, _ ->
                    change.consume()
                    onValueChange(positionToValue(change.position.x))
                }
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(barHeight)
                .background(progressColor),
        )
    }
}
