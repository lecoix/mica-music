package com.mica.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

/**
 * Continuous integer slider following DESIGN_SPEC.md section 10.5.1, option B.
 * The visible track is intentionally centered in a 48dp touch area.
 */
@Composable
internal fun SettingsSliderRow(
    title: String,
    value: Int,
    valueRange: IntRange,
    suffix: String,
    onValueChange: (Int) -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    require(!valueRange.isEmpty()) { "valueRange must not be empty" }

    val min = valueRange.first
    val max = valueRange.last
    val clampedValue = value.coerceIn(min, max)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val unselectedTrackColor = MicaTheme.colors.textTertiary.copy(alpha = 0.30f)
    val accentColor = MicaTheme.colors.accent
    var widthPx by remember { mutableFloatStateOf(0f) }

    fun positionToValue(x: Float): Int {
        if (widthPx <= 0f || max == min) return clampedValue
        return (min + (x / widthPx).coerceIn(0f, 1f) * (max - min))
            .roundToInt()
            .coerceIn(min, max)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
    ) {
        Text(
            text = title,
            style = MicaTheme.typography.bodyLg,
            color = MicaTheme.colors.textPrimary,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textTertiary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$clampedValue$suffix",
                style = MicaTheme.typography.monoMd,
                color = MicaTheme.colors.textSecondary,
            )
            Text(
                text = "$min$suffix / $max$suffix",
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textTertiary,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(min, max) {
                    detectTapGestures { offset ->
                        latestOnValueChange(positionToValue(offset.x))
                    }
                }
                .pointerInput(min, max) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        latestOnValueChange(positionToValue(change.position.x))
                    }
                },
        ) {
            val fraction = if (max == min) {
                0f
            } else {
                (clampedValue - min).toFloat() / (max - min).toFloat()
            }
            val centerY = size.height / 2f
            val trackHeight = 3.dp.toPx()
            val thumbSize = 12.dp.toPx()
            val thumbCenterX = size.width * fraction

            drawRect(
                color = unselectedTrackColor,
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width, trackHeight),
            )
            drawRect(
                color = accentColor,
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(thumbCenterX, trackHeight),
            )
            drawRect(
                color = accentColor,
                topLeft = Offset(
                    x = (thumbCenterX - thumbSize / 2f).coerceIn(0f, size.width - thumbSize),
                    y = centerY - thumbSize / 2f,
                ),
                size = Size(thumbSize, thumbSize),
            )
        }
        Spacer(Modifier.height(HifiSpacing.xs))
    }
}
