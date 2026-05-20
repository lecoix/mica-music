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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.PlayerContentColors

/** 覆盖式进度条：底层半透明轨道，已播放段用不透明色条从左向右覆盖。 */
@Composable
fun HiFiSeekBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    colors: PlayerContentColors,
    modifier: Modifier = Modifier,
    trackHeight: androidx.compose.ui.unit.Dp = 3.dp,
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
            .height(32.dp)
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
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .background(colors.tertiary.copy(alpha = 0.3f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(trackHeight)
                .background(colors.primary),
        )
    }
}
