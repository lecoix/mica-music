package com.mica.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import kotlin.math.roundToInt

/** 直角竖条频段滑块：轨道 + 矩形游标，从 0 dB 中线向上下延伸。 */
@Composable
fun EqualizerBandBar(
    freqLabel: String,
    levelMillibels: Short,
    minMillibels: Short,
    maxMillibels: Short,
    enabled: Boolean,
    onLevelChange: (Short) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 176.dp,
    barWidth: Dp = 48.dp,
    trackWidth: Dp = 3.dp,
) {
    val trackColor = MicaTheme.colors.divider
    val fillColor = if (enabled) MicaTheme.colors.accent else MicaTheme.colors.textTertiary
    val thumbColor = if (enabled) MicaTheme.colors.accent else MicaTheme.colors.textTertiary
    val haptic = LocalHapticFeedback.current
    val rangeMb = (maxMillibels - minMillibels).toFloat().coerceAtLeast(1f)
    val zeroMb = (minMillibels.toInt() + maxMillibels.toInt()) / 2f
    val levelDb = levelMillibels / 100f
    val tickMillibels = listOf(
        maxMillibels,
        (maxMillibels.toInt() / 2).toShort(),
        zeroMb.toInt().toShort(),
        (minMillibels.toInt() / 2).toShort(),
        minMillibels,
    )
        .distinct()

    var heightPx by remember { mutableFloatStateOf(0f) }
    var lastHapticBucket by remember { mutableIntStateOf(Int.MIN_VALUE) }

    fun yToLevel(y: Float): Short {
        if (heightPx <= 0f) return levelMillibels
        val t = (1f - (y / heightPx).coerceIn(0f, 1f))
        val mb = minMillibels + t * rangeMb
        return mb.roundToInt().toShort().coerceIn(minMillibels, maxMillibels)
    }

    fun performTick(level: Short) {
        val bucket = level.toInt() / 100
        if (bucket == lastHapticBucket) return
        lastHapticBucket = bucket
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.widthIn(min = barWidth),
    ) {
        Text(
            text = formatDbLabel(levelDb),
            style = MicaTheme.typography.monoSm,
            color = MicaTheme.colors.textSecondary,
        )
        Box(
            modifier = Modifier
                .height(barHeight)
                .fillMaxWidth()
                .onSizeChanged { heightPx = it.height.toFloat() }
                .pointerInput(enabled, minMillibels, maxMillibels) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val level = yToLevel(offset.y)
                        performTick(level)
                        onLevelChange(level)
                    }
                }
                .pointerInput(enabled, minMillibels, maxMillibels) {
                    if (!enabled) return@pointerInput
                    detectDragGestures { change, _ ->
                        change.consume()
                        val level = yToLevel(change.position.y)
                        performTick(level)
                        onLevelChange(level)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackW = trackWidth.toPx()
                val cx = size.width / 2f
                val zeroY = size.height * (1f - (zeroMb - minMillibels) / rangeMb)
                val levelY = size.height * (1f - (levelMillibels - minMillibels) / rangeMb)
                val tickStart = size.width * 0.24f
                val tickEnd = size.width * 0.76f

                drawRect(
                    color = trackColor,
                    topLeft = Offset(cx - trackW / 2f, 0f),
                    size = Size(trackW, size.height),
                )
                tickMillibels.forEach { tick ->
                    val tickY = size.height * (1f - (tick - minMillibels) / rangeMb)
                    val isZero = tick.toFloat() == zeroMb
                    drawLine(
                        color = trackColor.copy(alpha = if (isZero) 0.85f else 0.55f),
                        start = Offset(if (isZero) tickStart * 0.75f else tickStart, tickY),
                        end = Offset(if (isZero) size.width - tickStart * 0.75f else tickEnd, tickY),
                        strokeWidth = if (isZero) 2f else 1f,
                    )
                }

                val top = minOf(levelY, zeroY)
                val bottom = maxOf(levelY, zeroY)
                if (bottom - top > 0.5f) {
                    drawRect(
                        color = fillColor,
                        topLeft = Offset(cx - trackW / 2f, top),
                        size = Size(trackW, bottom - top),
                    )
                }

                val thumbW = 22f
                val thumbH = 4f
                drawRect(
                    color = thumbColor,
                    topLeft = Offset(cx - thumbW / 2f, levelY - thumbH / 2f),
                    size = Size(thumbW, thumbH),
                )
            }
        }
        Text(
            text = freqLabel,
            style = MicaTheme.typography.monoSm,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.padding(top = HifiSpacing.xxs),
        )
    }
}

/** 横向频段推子：左频率、中间轨道、右 dB，适合手机上单手微调。 */
@Composable
fun EqualizerBandSlider(
    freqLabel: String,
    levelMillibels: Short,
    minMillibels: Short,
    maxMillibels: Short,
    enabled: Boolean,
    onLevelChange: (Short) -> Unit,
    modifier: Modifier = Modifier,
    sliderHeight: Dp = 40.dp,
    labelWidth: Dp = 48.dp,
    valueWidth: Dp = 52.dp,
    trackHeight: Dp = 3.dp,
) {
    val trackColor = MicaTheme.colors.divider
    val fillColor = if (enabled) MicaTheme.colors.accent else MicaTheme.colors.textTertiary
    val thumbColor = if (enabled) MicaTheme.colors.accent else MicaTheme.colors.textTertiary
    val haptic = LocalHapticFeedback.current
    val rangeMb = (maxMillibels - minMillibels).toFloat().coerceAtLeast(1f)
    val zeroMb = (minMillibels.toInt() + maxMillibels.toInt()) / 2f
    val levelDb = levelMillibels / 100f

    var widthPx by remember { mutableFloatStateOf(0f) }
    var lastHapticBucket by remember { mutableIntStateOf(Int.MIN_VALUE) }

    fun xToLevel(x: Float): Short {
        if (widthPx <= 0f) return levelMillibels
        val t = (x / widthPx).coerceIn(0f, 1f)
        val mb = minMillibels + t * rangeMb
        return mb.roundToInt().toShort().coerceIn(minMillibels, maxMillibels)
    }

    fun performTick(level: Short) {
        val bucket = level.toInt() / 100
        if (bucket == lastHapticBucket) return
        lastHapticBucket = bucket
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = freqLabel,
            style = MicaTheme.typography.monoMd,
            color = MicaTheme.colors.textSecondary,
            modifier = Modifier.width(labelWidth),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(sliderHeight)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(enabled, minMillibels, maxMillibels) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val level = xToLevel(offset.x)
                        performTick(level)
                        onLevelChange(level)
                    }
                }
                .pointerInput(enabled, minMillibels, maxMillibels) {
                    if (!enabled) return@pointerInput
                    detectDragGestures { change, _ ->
                        change.consume()
                        val level = xToLevel(change.position.x)
                        performTick(level)
                        onLevelChange(level)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackH = trackHeight.toPx()
                val cy = size.height / 2f
                val zeroX = size.width * ((zeroMb - minMillibels) / rangeMb)
                val levelX = size.width * ((levelMillibels - minMillibels) / rangeMb)

                drawRect(
                    color = trackColor,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(size.width, trackH),
                )

                val left = minOf(levelX, zeroX)
                val right = maxOf(levelX, zeroX)
                if (right - left > 0.5f) {
                    drawRect(
                        color = fillColor,
                        topLeft = Offset(left, cy - trackH / 2f),
                        size = Size(right - left, trackH),
                    )
                }

                val thumbW = 14f
                val thumbH = 4f
                drawRect(
                    color = thumbColor,
                    topLeft = Offset(levelX - thumbW / 2f, cy - thumbH / 2f),
                    size = Size(thumbW, thumbH),
                )
            }
        }
        Text(
            text = formatDbLabel(levelDb),
            style = MicaTheme.typography.monoMd,
            color = MicaTheme.colors.textSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(valueWidth),
        )
    }
}

@Composable
fun EqualizerDbScale(
    minMillibels: Short,
    maxMillibels: Short,
    modifier: Modifier = Modifier,
    barHeight: androidx.compose.ui.unit.Dp = 280.dp,
) {
    val minDb = minMillibels / 100
    val maxDb = maxMillibels / 100
    val marks = listOf(maxDb, maxDb / 2, 0, minDb / 2, minDb).distinct().sortedDescending()

    Column(
        modifier = modifier
            .height(barHeight + 18.dp)
            .width(28.dp)
            .padding(top = 18.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        marks.forEach { db ->
            Text(
                text = formatDbLabel(db.toFloat()),
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textTertiary,
            )
        }
    }
}

private fun formatDbLabel(db: Float): String {
    val rounded = (db * 10f).roundToInt() / 10f
    val text = if (rounded == rounded.toInt().toFloat()) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
    return "${if (rounded > 0f) "+" else ""}$text"
}

private fun formatBandLabel(centerHz: Int): String = when {
    centerHz >= 1_000 -> "${centerHz / 1_000}k"
    else -> "$centerHz"
}

internal fun formatEqBandLabel(centerHz: Int): String = formatBandLabel(centerHz)
