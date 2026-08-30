package com.mica.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.media.EqualizerBand
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import kotlin.math.roundToInt

/** dB 刻度栏宽度；绘图区从这里开始，输入测试据此换算频段列坐标。 */
internal val DbScaleWidth = 32.dp
private val ThumbMaxWidth = 24.dp
private val ThumbHeight = 3.dp
private val CurveStrokeWidth = 2.dp

/**
 * 频响曲线编辑器：曲线本身就是推子。
 *
 * 每个频段占据等宽的一列。按下瞬间锁定手指所在的列，之后只跟随 y，横向偏移不会牵动相邻频段。
 * dB 刻度沿用 [Arrangement.SpaceBetween] 排布，绘图区上下留出半个刻度文字的高度，
 * 使 ±满程线与首尾刻度文字的中心对齐。
 */
@Composable
fun EqualizerCurveEditor(
    bands: List<EqualizerBand>,
    minMillibels: Short,
    maxMillibels: Short,
    enabled: Boolean,
    selectedBandIndex: Int?,
    onBandTouched: (bandIndex: Int, levelMillibels: Short) -> Unit,
    modifier: Modifier = Modifier,
    graphHeight: Dp = 208.dp,
) {
    if (bands.isEmpty()) return

    val colors = MicaTheme.colors
    val curveColor = if (enabled) colors.accent else colors.textTertiary
    val gridColor = colors.divider
    val haptic = LocalHapticFeedback.current
    val latestOnBandTouched by rememberUpdatedState(onBandTouched)

    val bandCount = bands.size
    val rangeMillibels = (maxMillibels - minMillibels).toFloat().coerceAtLeast(1f)
    val zeroMillibels = (minMillibels.toInt() + maxMillibels.toInt()) / 2f

    var plotInsetPx by remember { mutableFloatStateOf(0f) }
    var graphWidthPx by remember { mutableFloatStateOf(0f) }
    var graphHeightPx by remember { mutableFloatStateOf(0f) }
    var lastHapticKey by remember { mutableIntStateOf(Int.MIN_VALUE) }

    fun levelAt(y: Float): Short {
        val top = plotInsetPx
        val bottom = (graphHeightPx - plotInsetPx).coerceAtLeast(top + 1f)
        val fraction = 1f - ((y - top) / (bottom - top)).coerceIn(0f, 1f)
        return (minMillibels + fraction * rangeMillibels)
            .roundToInt()
            .toShort()
            .coerceIn(minMillibels, maxMillibels)
    }

    fun bandAt(x: Float): Int {
        if (graphWidthPx <= 0f) return 0
        return ((x / graphWidthPx) * bandCount).toInt().coerceIn(0, bandCount - 1)
    }

    fun applyLevel(bandIndex: Int, y: Float) {
        val level = levelAt(y)
        val hapticKey = bandIndex * 1_000 + level / 100
        if (hapticKey != lastHapticKey) {
            lastHapticKey = hapticKey
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        latestOnBandTouched(bandIndex, level)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .width(DbScaleWidth)
                    .height(graphHeight)
                    .padding(end = HifiSpacing.xs),
            ) {
                DbScaleLabel(
                    text = formatSignedDb(maxMillibels / 100),
                    modifier = Modifier.onSizeChanged { plotInsetPx = it.height / 2f },
                )
                DbScaleLabel(text = "0")
                DbScaleLabel(text = formatSignedDb(minMillibels / 100))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(graphHeight)
                    .onSizeChanged {
                        graphWidthPx = it.width.toFloat()
                        graphHeightPx = it.height.toFloat()
                    }
                    .pointerInput(enabled, bandCount, minMillibels, maxMillibels) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            // 不等触摸 slop：按下瞬间就锁定手指所在的那一列。若改用
                            // detectDragGestures，起始列会在 slop 越过之后才确定，斜向起手会错列。
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val bandIndex = bandAt(down.position.x)
                            applyLevel(bandIndex, down.position.y)

                            while (true) {
                                val change = awaitPointerEvent().changes
                                    .firstOrNull { it.id == down.id }
                                    ?: break
                                if (change.changedToUpIgnoreConsumed()) break
                                if (change.positionChanged()) {
                                    // 消费位移，否则外层 verticalScroll 会在越过 slop 后抢走手势。
                                    change.consume()
                                    applyLevel(bandIndex, change.position.y)
                                }
                            }
                        }
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val top = plotInsetPx
                    val bottom = (size.height - plotInsetPx).coerceAtLeast(top + 1f)
                    val plotHeight = bottom - top
                    val columnWidth = size.width / bandCount
                    val hairline = HifiSize.dividerHairline.toPx()

                    fun yOf(millibels: Float): Float =
                        top + plotHeight * (1f - (millibels - minMillibels) / rangeMillibels)

                    fun xOf(bandIndex: Int): Float = columnWidth * (bandIndex + 0.5f)

                    val zeroY = yOf(zeroMillibels)

                    drawLine(
                        color = gridColor.copy(alpha = 0.5f),
                        start = Offset(0f, top),
                        end = Offset(size.width, top),
                        strokeWidth = hairline,
                    )
                    drawLine(
                        color = gridColor.copy(alpha = 0.5f),
                        start = Offset(0f, bottom),
                        end = Offset(size.width, bottom),
                        strokeWidth = hairline,
                    )
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, zeroY),
                        end = Offset(size.width, zeroY),
                        strokeWidth = hairline,
                    )

                    repeat(bandCount) { bandIndex ->
                        val selected = bandIndex == selectedBandIndex && enabled
                        drawLine(
                            color = if (selected) curveColor.copy(alpha = 0.35f) else gridColor.copy(alpha = 0.45f),
                            start = Offset(xOf(bandIndex), top),
                            end = Offset(xOf(bandIndex), bottom),
                            strokeWidth = if (selected) HifiSize.accentBarWidth.toPx() else hairline,
                        )
                    }

                    val points = bands.mapIndexed { bandIndex, band ->
                        Offset(xOf(bandIndex), yOf(band.levelMillibels.toFloat()))
                    }

                    if (enabled) {
                        val envelope = Path().apply {
                            moveTo(0f, points.first().y)
                            points.forEach { point -> lineTo(point.x, point.y) }
                            lineTo(size.width, points.last().y)
                            lineTo(size.width, zeroY)
                            lineTo(0f, zeroY)
                            close()
                        }
                        drawPath(envelope, curveColor.copy(alpha = 0.10f))
                    }

                    val stroke = CurveStrokeWidth.toPx()
                    drawLine(curveColor, Offset(0f, points.first().y), points.first(), strokeWidth = stroke)
                    for (index in 0 until points.lastIndex) {
                        drawLine(curveColor, points[index], points[index + 1], strokeWidth = stroke)
                    }
                    drawLine(
                        curveColor,
                        points.last(),
                        Offset(size.width, points.last().y),
                        strokeWidth = stroke,
                    )

                    val thumbWidth = (columnWidth * 0.52f).coerceAtMost(ThumbMaxWidth.toPx())
                    val thumbHeight = ThumbHeight.toPx()
                    points.forEach { point ->
                        drawRect(
                            color = curveColor,
                            topLeft = Offset(point.x - thumbWidth / 2f, point.y - thumbHeight / 2f),
                            size = Size(thumbWidth, thumbHeight),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DbScaleWidth, top = HifiSpacing.xs),
        ) {
            bands.forEachIndexed { bandIndex, band ->
                Text(
                    text = formatEqBandLabel(band.centerHz),
                    style = MicaTheme.typography.monoSm,
                    color = if (bandIndex == selectedBandIndex && enabled) {
                        colors.accent
                    } else {
                        colors.textTertiary
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DbScaleLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MicaTheme.typography.monoSm,
        color = MicaTheme.colors.textTertiary,
        maxLines = 1,
        modifier = modifier,
    )
}

private fun formatSignedDb(db: Int): String = if (db > 0) "+$db" else "$db"

/** 读数行频率：`125 Hz` / `16 kHz`。 */
internal fun formatEqFrequencyLabel(centerHz: Int): String =
    if (centerHz >= 1_000) "${centerHz / 1_000} kHz" else "$centerHz Hz"

/** 读数行增益：`+4.5 dB` / `0 dB`。 */
internal fun formatEqLevelLabel(levelMillibels: Short): String {
    val rounded = (levelMillibels / 10f).roundToInt() / 10f
    val text = if (rounded == rounded.toInt().toFloat()) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
    return "${if (rounded > 0f) "+" else ""}$text dB"
}
