package com.mica.music.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsSync
import com.mica.music.data.LyricsWordAnimationPreset
import kotlin.math.roundToInt

private const val SoftFillFallbackDurationMs = 2_500

/**
 * 窄条（信息行 / MiniPlayer）柔边逐字填充：只渲染原文，超宽时按行进度平移（不用跑马灯）。
 */
@Composable
fun NarrowBarSoftKaraokeLyric(
    line: LyricLine,
    positionMs: Int,
    positionRevision: Long = 0L,
    isPlaying: Boolean,
    nextLineTimeMs: Int?,
    filledColor: Color,
    unfilledColor: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val originalRow = remember(line.text) {
        LyricDisplayRows.rowsForBilingualDisplayMode(
            text = line.text,
            enabled = true,
            mode = LyricsBilingualDisplayMode.ORIGINAL,
        ).firstOrNull()
    } ?: return
    val displayText = originalRow.text.takeIf { it.isNotBlank() } ?: return
    val cueRanges = remember(line) { narrowBarCueRanges(line) }
    val framePositionMs = positionMs
    val fillFraction = if (cueRanges.isNotEmpty()) {
        narrowBarSoftFillFraction(
            line = line,
            row = originalRow,
            cueRanges = cueRanges,
            positionMs = framePositionMs,
            nextLineTimeMs = nextLineTimeMs,
        )
    } else {
        0f
    }
    val fadeEm = LyricsWordAnimationPreset.SOFT_FILL.wordFadeWidthEm
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var contentWidthPx by remember { mutableFloatStateOf(0f) }
    var layout by remember(displayText, textStyle) { mutableStateOf<TextLayoutResult?>(null) }
    val panPx = narrowBarLyricPanOffsetPx(
        lineStartMs = line.timeMs,
        lineEndMs = line.endTimeMs ?: nextLineTimeMs,
        positionMs = framePositionMs,
        contentWidthPx = contentWidthPx,
        viewportWidthPx = viewportWidthPx.toFloat(),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { viewportWidthPx = it.width },
    ) {
        // unbounded：避免父级 maxWidth 把长句量成「刚好视口宽」导致永不平移（浮岛更窄时必现）
        Box(
            modifier = Modifier
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .offset { IntOffset(panPx.roundToInt(), 0) },
        ) {
            Text(
                text = displayText,
                style = textStyle,
                color = unfilledColor,
                maxLines = 1,
                overflow = TextOverflow.Visible,
                softWrap = false,
                onTextLayout = { result ->
                    layout = result
                    contentWidthPx = if (result.lineCount > 0) {
                        (result.getLineRight(0) - result.getLineLeft(0)).coerceAtLeast(0f)
                    } else {
                        result.size.width.toFloat()
                    }
                },
            )
            Text(
                text = displayText,
                style = textStyle,
                color = filledColor,
                maxLines = 1,
                overflow = TextOverflow.Visible,
                softWrap = false,
                modifier = Modifier.drawWithContent {
                    val textLayout = layout
                    if (textLayout == null || fillFraction <= 0f) return@drawWithContent
                    val fraction = fillFraction.coerceIn(0f, 1f)
                    val left = textLayout.getLineLeft(0)
                    val right = textLayout.getLineRight(0)
                    val lineWidth = (right - left).coerceAtLeast(0f)
                    if (lineWidth <= 0f) return@drawWithContent
                    val fillRight = left + lineWidth * fraction
                    val featherPx = textStyle.fontSize.toPx() * fadeEm.coerceAtLeast(0f)
                    val featherRight = (fillRight + featherPx).coerceAtMost(right)
                    clipRect(
                        left = left,
                        top = textLayout.getLineTop(0),
                        right = featherRight,
                        bottom = textLayout.getLineBottom(0),
                    ) {
                        if (featherPx <= 0f || fillRight >= right) {
                            this@drawWithContent.drawContent()
                        } else {
                            val bounds = Rect(
                                left,
                                textLayout.getLineTop(0),
                                featherRight,
                                textLayout.getLineBottom(0),
                            )
                            drawContext.canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
                            this@drawWithContent.drawContent()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0f to Color.White,
                                    1f to Color.Transparent,
                                    startX = fillRight,
                                    endX = featherRight,
                                ),
                                topLeft = bounds.topLeft,
                                size = bounds.size,
                                blendMode = BlendMode.DstIn,
                            )
                            drawContext.canvas.restore()
                        }
                    }
                },
            )
        }
    }
}

/** 超宽窄条：按行进度从左缘滚到右缘（easeOutCubic）。 */
internal fun narrowBarLyricPanOffsetPx(
    lineStartMs: Int,
    lineEndMs: Int?,
    positionMs: Int,
    contentWidthPx: Float,
    viewportWidthPx: Float,
): Float {
    val overflow = contentWidthPx - viewportWidthPx
    if (overflow <= 0f || lineEndMs == null || lineEndMs <= lineStartMs) return 0f
    val progress = ((positionMs - lineStartMs).toFloat() / (lineEndMs - lineStartMs)).coerceIn(0f, 1f)
    val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
    return -eased * overflow
}

internal fun narrowBarSoftFillFraction(
    line: LyricLine,
    row: LyricDisplayRows.DisplayRow,
    cueRanges: List<NarrowBarCueRange>,
    positionMs: Int,
    nextLineTimeMs: Int?,
): Float {
    val cueCount = line.cues.size
    if (cueCount == 0) return 0f
    val t = positionMs
    if (t < line.cues.first().timeMs) return 0f
    val activeCueIndex = LyricsSync.cueIndexForPosition(line, positionMs)
    if (activeCueIndex < 0) return 0f
    val activeRange = cueRanges.firstOrNull { it.cueIndex == activeCueIndex }
    if (activeRange == null) {
        val completedTextFraction = (activeCueIndex + 1).toFloat() / cueCount
        return narrowBarRowFillFraction(row, completedTextFraction, line.text.length)
    }
    val cueStart = line.cues[activeCueIndex].timeMs
    val cueEnd = line.cues.getOrNull(activeCueIndex + 1)?.timeMs
        ?: (nextLineTimeMs?.takeIf { it > cueStart } ?: (cueStart + SoftFillFallbackDurationMs))
    val cueProgress = if (cueEnd <= cueStart) {
        1f
    } else {
        ((t - cueStart).toFloat() / (cueEnd - cueStart)).coerceIn(0f, 1f)
    }
    val filledChars = activeRange.start + (activeRange.endExclusive - activeRange.start) * cueProgress
    return narrowBarRowFillFraction(
        row,
        filledChars / line.text.length.coerceAtLeast(1),
        line.text.length,
    )
}

internal data class NarrowBarCueRange(val cueIndex: Int, val start: Int, val endExclusive: Int)

internal fun narrowBarCueRanges(line: LyricLine): List<NarrowBarCueRange> {
    var searchFrom = 0
    return buildList {
        line.cues.forEachIndexed { index, cue ->
            var visible = cue.text
            var start = line.text.indexOf(visible, startIndex = searchFrom)
            if (start < 0) {
                visible = visible.trim()
                start = line.text.indexOf(visible, startIndex = searchFrom)
            }
            if (start < 0 || visible.isEmpty()) return@forEachIndexed
            val end = (start + visible.length).coerceAtMost(line.text.length)
            add(NarrowBarCueRange(index, start, end))
            searchFrom = end
        }
    }
}

private fun narrowBarRowFillFraction(
    row: LyricDisplayRows.DisplayRow,
    lineFillFraction: Float,
    lineLength: Int,
): Float {
    val rowLength = (row.endExclusive - row.start).coerceAtLeast(1)
    val filledChars = lineFillFraction.coerceIn(0f, 1f) * lineLength.coerceAtLeast(1)
    return ((filledChars - row.start) / rowLength).coerceIn(0f, 1f)
}
