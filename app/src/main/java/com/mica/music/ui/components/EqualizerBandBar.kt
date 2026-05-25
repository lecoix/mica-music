package com.mica.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
    barHeight: androidx.compose.ui.unit.Dp = 280.dp,
    trackWidth: androidx.compose.ui.unit.Dp = 3.dp,
) {
    val trackColor = MicaTheme.colors.divider
    val fillColor = if (enabled) MicaTheme.colors.accent else MicaTheme.colors.textTertiary
    val thumbColor = if (enabled) MicaTheme.colors.accent else MicaTheme.colors.textTertiary
    val rangeMb = (maxMillibels - minMillibels).toFloat().coerceAtLeast(1f)
    val zeroMb = (minMillibels.toInt() + maxMillibels.toInt()) / 2f
    val levelDb = levelMillibels / 100f

    var heightPx by remember { mutableFloatStateOf(0f) }

    fun yToLevel(y: Float): Short {
        if (heightPx <= 0f) return levelMillibels
        val t = (1f - (y / heightPx).coerceIn(0f, 1f))
        val mb = minMillibels + t * rangeMb
        return mb.roundToInt().toShort().coerceIn(minMillibels, maxMillibels)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(30.dp),
    ) {
        Text(
            text = formatDbLabel(levelDb),
            style = MicaTheme.typography.monoSm,
            color = MicaTheme.colors.textSecondary,
        )
        Box(
            modifier = Modifier
                .height(barHeight)
                .width(22.dp)
                .onSizeChanged { heightPx = it.height.toFloat() }
                .pointerInput(enabled, minMillibels, maxMillibels) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        onLevelChange(yToLevel(offset.y))
                    }
                }
                .pointerInput(enabled, minMillibels, maxMillibels) {
                    if (!enabled) return@pointerInput
                    detectDragGestures { change, _ ->
                        change.consume()
                        onLevelChange(yToLevel(change.position.y))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackW = trackWidth.toPx()
                val cx = size.width / 2f
                val zeroY = size.height * (1f - (zeroMb - minMillibels) / rangeMb)
                val levelY = size.height * (1f - (levelMillibels - minMillibels) / rangeMb)

                drawRect(
                    color = trackColor,
                    topLeft = Offset(cx - trackW / 2f, 0f),
                    size = Size(trackW, size.height),
                )

                val top = minOf(levelY, zeroY)
                val bottom = maxOf(levelY, zeroY)
                if (bottom - top > 0.5f) {
                    drawRect(
                        color = fillColor,
                        topLeft = Offset(cx - trackW / 2f, top),
                        size = Size(trackW, bottom - top),
                    )
                }

                val thumbW = 4f
                val thumbH = 14f
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
