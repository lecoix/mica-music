package com.mica.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import com.mica.music.data.PlaybackTuning
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackTuningSheet(
    tuning: PlaybackTuning,
    onDismiss: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPitchSemitonesChange: (Float) -> Unit,
    onReset: () -> Unit,
    landscape: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MicaTheme.colors.isDark
    val sheetBackground = if (isDark) HifiPalette.MicaFogDarkEnd else HifiPalette.MicaFogStart

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (landscape) Modifier.fillMaxHeight() else Modifier)
                .padding(horizontal = HifiSpacing.lg)
                .padding(bottom = if (landscape) HifiSpacing.lg else HifiSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.lg),
        ) {
            if (landscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "速度 / 音高",
                        style = MicaTheme.typography.titleMd,
                        color = MicaTheme.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭播放调节",
                            tint = MicaTheme.colors.textSecondary,
                        )
                    }
                }
                HorizontalDivider(color = MicaTheme.colors.divider)
            }
            PlaybackTuningSlider(
                label = "速度",
                valueLabel = formatSpeed(tuning.speed),
                value = tuning.speed,
                valueRange = PlaybackTuning.MIN_SPEED..PlaybackTuning.MAX_SPEED,
                step = 0.05f,
                majorStep = 0.5f,
                tickLabel = ::formatSpeedTick,
                onValueChange = onSpeedChange,
            )
            PlaybackTuningSlider(
                label = "音高",
                valueLabel = formatPitchSemitones(tuning.pitchSemitones),
                value = tuning.pitchSemitones,
                valueRange = PlaybackTuning.MIN_PITCH_SEMITONES..PlaybackTuning.MAX_PITCH_SEMITONES,
                step = 1f,
                majorStep = 6f,
                tickLabel = ::formatPitchTick,
                onValueChange = onPitchSemitonesChange,
            )
            Text(
                text = "重置",
                style = MicaTheme.typography.bodyLg,
                color = MicaTheme.colors.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onReset)
                    .padding(vertical = HifiSpacing.sm),
            )
        }
    }

    if (landscape) {
        PlayerSidePanel(
            onDismiss = onDismiss,
            containerColor = sheetBackground,
            scrimColor = Color.Black.copy(alpha = if (isDark) 0.42f else 0.28f),
            paneTitle = "速度和音高",
            content = content,
        )
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = sheetBackground,
            scrimColor = Color.Black.copy(alpha = if (isDark) 0.72f else 0.45f),
        ) {
            content()
        }
    }
}

@Composable
private fun PlaybackTuningSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    majorStep: Float,
    tickLabel: (Float) -> String,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textPrimary,
            )
            Text(
                text = valueLabel,
                style = MicaTheme.typography.monoMd,
                color = MicaTheme.colors.textTertiary,
            )
        }
        PlaybackTuningRuler(
            value = value,
            valueRange = valueRange,
            step = step,
            majorStep = majorStep,
            tickLabel = tickLabel,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun PlaybackTuningRuler(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    majorStep: Float,
    tickLabel: (Float) -> String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val clamped = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val tickColor = MicaTheme.colors.textTertiary.copy(alpha = 0.78f)
    val majorTickColor = MicaTheme.colors.textSecondary.copy(alpha = 0.92f)
    val selectedColor = MicaTheme.colors.accent
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var widthPx by remember { mutableFloatStateOf(0f) }

    fun snap(raw: Float): Float {
        val stepped = ((raw - valueRange.start) / step).roundToInt() * step + valueRange.start
        return stepped.coerceIn(valueRange.start, valueRange.endInclusive)
    }

    fun xToValue(x: Float): Float {
        if (widthPx <= 0f) return clamped
        val fraction = (x / widthPx).coerceIn(0f, 1f)
        return snap(valueRange.start + fraction * range)
    }

    val totalSteps = (range / step).roundToInt().coerceAtLeast(1)
    val majorStepIndex = (majorStep / step).roundToInt().coerceAtLeast(1)
    val selectedFraction = (clamped - valueRange.start) / range

    Column(
        modifier = modifier
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(valueRange, step) {
                detectTapGestures { offset -> currentOnValueChange(xToValue(offset.x)) }
            }
            .pointerInput(valueRange, step) {
                detectDragGestures { change, _ ->
                    change.consume()
                    currentOnValueChange(xToValue(change.position.x))
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerY = size.height / 2f
                val shortHeight = 5.33.dp.toPx()
                val longHeight = 12.dp.toPx()
                val tickWidth = 1.dp.toPx()
                for (i in 0..totalSteps) {
                    val x = size.width * i / totalSteps
                    val major = i % majorStepIndex == 0 || i == totalSteps
                    val height = if (major) longHeight else shortHeight
                    drawRect(
                        color = if (major) majorTickColor else tickColor,
                        topLeft = Offset(x - tickWidth / 2f, centerY - height / 2f),
                        size = Size(tickWidth, height),
                    )
                }

                val selectedX = size.width * selectedFraction.coerceIn(0f, 1f)
                val selectedWidth = 3.dp.toPx()
                val selectedHeight = 20.dp.toPx()
                drawRect(
                    color = selectedColor,
                    topLeft = Offset(selectedX - selectedWidth / 2f, centerY - selectedHeight / 2f),
                    size = Size(selectedWidth, selectedHeight),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (i in 0..totalSteps step majorStepIndex) {
                val mark = snap(valueRange.start + i * step)
                Text(
                    text = tickLabel(mark),
                    style = MicaTheme.typography.bodySm,
                    color = if (abs(mark - clamped) < step / 2f) {
                        selectedColor
                    } else {
                        MicaTheme.colors.textSecondary
                    },
                )
            }
        }
    }
}

fun formatPlaybackTuningMenuLabel(tuning: PlaybackTuning): String =
    if (tuning.isDefault) {
        "速度 / 音高"
    } else {
        "${formatSpeed(tuning.speed)} · ${formatPitchSemitones(tuning.pitchSemitones)}"
    }

private fun formatSpeed(speed: Float): String =
    String.format(Locale.US, "%.2fx", speed)

private fun formatSpeedTick(speed: Float): String {
    val rounded = speed.roundToInt()
    return if (abs(speed - rounded) < 0.001f) {
        "${rounded}x"
    } else {
        String.format(Locale.US, "%.1fx", speed)
    }
}

private fun formatPitchSemitones(semitones: Float): String {
    val rounded = semitones.roundToInt()
    return when {
        rounded > 0 -> "+$rounded 半音"
        rounded < 0 -> "$rounded 半音"
        else -> "0 半音"
    }
}

private fun formatPitchTick(semitones: Float): String {
    val rounded = semitones.roundToInt()
    return when {
        rounded > 0 -> "+$rounded"
        else -> rounded.toString()
    }
}
