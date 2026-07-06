package com.mica.music.ui.screens.settings.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
internal fun HsvColorSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    colors: List<Color>,
    onValueChange: (Float) -> Unit,
) {
    val min = valueRange.start
    val max = valueRange.endInclusive.coerceAtLeast(min + 0.001f)
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
    var widthPx by remember { mutableFloatStateOf(0f) }

    fun positionToValue(x: Float): Float {
        if (widthPx <= 0f) return value
        return min + (x / widthPx).coerceIn(0f, 1f) * (max - min)
    }

    Column(verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textSecondary,
            )
            Text(
                text = valueText,
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textTertiary,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(min, max) {
                    detectTapGestures { offset -> onValueChange(positionToValue(offset.x)) }
                }
                .pointerInput(min, max) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onValueChange(positionToValue(change.position.x))
                    }
                },
        ) {
            val centerY = size.height / 2f
            drawLine(
                brush = Brush.horizontalGradient(colors),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 10.dp.toPx(),
                cap = StrokeCap.Butt,
            )
            val thumbSize = 16.dp.toPx()
            drawRect(
                color = Color.White,
                topLeft = Offset(
                    x = (size.width * fraction - thumbSize / 2f).coerceIn(0f, size.width - thumbSize),
                    y = centerY - thumbSize / 2f,
                ),
                size = Size(thumbSize, thumbSize),
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.28f),
                topLeft = Offset(
                    x = (size.width * fraction - thumbSize / 2f).coerceIn(0f, size.width - thumbSize),
                    y = centerY - thumbSize / 2f,
                ),
                size = Size(thumbSize, thumbSize),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
