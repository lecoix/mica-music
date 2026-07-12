package com.mica.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.lerp as lerpTextUnit
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsSync
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalLyricLineFillEnabled
import com.mica.music.ui.theme.LocalLyricSplitEnabled
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors

private const val LYRIC_LINE_PLACEHOLDER = "\u00A0"
private const val LyricFillFallbackDurationMs = 2_500

@Composable
fun rememberLyricLineColorSpec() =
    MicaMotion.tweenColor(rememberMicaMotionEnabled(), MicaMotion.DurationLongMs)

/** 当前句高亮样式与次要样式字号/行高的中间值，全行统一字号，仅颜色区分当前句。 */
fun lyricUniformTextStyle(highlight: TextStyle, normal: TextStyle): TextStyle =
    normal.copy(
        fontSize = lerpTextUnit(normal.fontSize, highlight.fontSize, 0.5f),
        lineHeight = lerpTextUnit(normal.lineHeight, highlight.lineHeight, 0.5f),
        fontWeight = FontWeight.Normal,
    )

@Composable
fun rememberLyricUniformStyle(): TextStyle {
    val typography = MicaTheme.typography
    return remember(typography) {
        lyricUniformTextStyle(typography.lyricCurrent, typography.lyricOther)
    }
}

// DstIn 只读 alpha；用白/透明梯度，避免未离屏合成时黑色 RGB 被看见。
private val LyricFadeMaskOpaque = Color.White
private val LyricFadeMaskClear = Color.White.copy(alpha = 0f)

/** 跑马灯标题左右缘渐隐：对内容做 alpha 遮罩，横向边缘柔和淡出。 */
fun Modifier.marqueeHorizontalEdgeFade(fadeWidth: Dp = 28.dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadePx = fadeWidth.toPx().coerceAtMost(size.width / 2f)
            if (fadePx > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(LyricFadeMaskClear, LyricFadeMaskOpaque),
                        startX = 0f,
                        endX = fadePx,
                    ),
                    size = Size(fadePx, size.height),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(LyricFadeMaskOpaque, LyricFadeMaskClear),
                        startX = size.width - fadePx,
                        endX = size.width,
                    ),
                    topLeft = Offset(size.width - fadePx, 0f),
                    size = Size(fadePx, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

/** 歌词区域上下缘渐隐：对内容做 alpha 遮罩，边缘淡出为透明，不依赖背景色。 */
fun Modifier.lyricsVerticalEdgeFade(fadeHeight: Dp = 28.dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadePx = fadeHeight.toPx().coerceAtMost(size.height / 2f)
            if (fadePx > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(LyricFadeMaskClear, LyricFadeMaskOpaque),
                        startY = 0f,
                        endY = fadePx,
                    ),
                    size = Size(size.width, fadePx),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(LyricFadeMaskOpaque, LyricFadeMaskClear),
                        startY = size.height - fadePx,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - fadePx),
                    size = Size(size.width, fadePx),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

@Composable
fun LyricsAreaEdgeFade(
    modifier: Modifier = Modifier,
    fadeHeight: Dp = 28.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .lyricsVerticalEdgeFade(fadeHeight),
    ) {
        content()
    }
}

/** 播放页切句位移略小于一行高，避免三行区视觉上移过多。 */
private const val PlayerLyricRollStepScale = 0.88f

/** 播放页切句上滚步长：统一字号下单行高 + 行间距（×[PlayerLyricRollStepScale]）。 */
@Composable
fun rememberPlayerLyricLineStepPx(textStyle: TextStyle): Float {
    val density = LocalDensity.current
    return remember(textStyle, density) {
        with(density) {
            val line = textStyle.lineHeight.toPx() + HifiSpacing.playerLyricLineGap.toPx()
            line * PlayerLyricRollStepScale
        }
    }
}

/**
 * 播放页歌词切句：先换句，再从偏移位置滑入到 0，避免「上移结束后 snap 归零」导致下沉一跳。
 */
@Composable
fun PlayerLyricsIndexRoll(
    targetIndex: Int,
    lineStepPx: Float,
    modifier: Modifier = Modifier,
    content: @Composable (displayIndex: Int) -> Unit,
) {
    val motionEnabled = rememberMicaMotionEnabled()
    var displayIndex by remember { mutableIntStateOf(targetIndex) }
    val offsetY = remember { Animatable(0f) }
    val rollSpec = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationLongMs)

    LaunchedEffect(targetIndex, lineStepPx, motionEnabled) {
        if (!motionEnabled || lineStepPx <= 0f) {
            displayIndex = targetIndex
            offsetY.snapTo(0f)
            return@LaunchedEffect
        }
        if (targetIndex == displayIndex) {
            if (offsetY.value != 0f) {
                offsetY.animateTo(0f, rollSpec)
            }
            return@LaunchedEffect
        }
        val steps = (targetIndex - displayIndex).coerceIn(-1, 1)
        displayIndex = targetIndex
        offsetY.snapTo(steps * lineStepPx)
        offsetY.animateTo(0f, rollSpec)
    }

    Column(
        modifier = modifier.graphicsLayer { translationY = offsetY.value },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.playerLyricLineGap),
    ) {
        content(displayIndex)
    }
}

@Composable
fun LyricLineBlock(
    text: String?,
    isCurrent: Boolean,
    colors: PlayerContentColors,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    colorSpec: androidx.compose.animation.core.AnimationSpec<Color> = rememberLyricLineColorSpec(),
    maxLines: Int = 1,
    lyricLine: LyricLine? = null,
    nextLineTimeMs: Int? = null,
    positionMs: Int = 0,
    isPlaying: Boolean = false,
    textAlign: TextAlign = TextAlign.Center,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    bilingualDisplayMode: LyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
    translationTextStyle: TextStyle = textStyle,
    karaokeSyllableLift: Boolean = false,
    karaokeWordFadeWidthEm: Float = 0f,
) {
    val lyricSplitEnabled = LocalLyricSplitEnabled.current
    val lyricLineFillEnabled = LocalLyricLineFillEnabled.current
    val rows = LyricDisplayRows.rowsForBilingualDisplayMode(
        text = text.orEmpty(),
        enabled = lyricSplitEnabled,
        mode = bilingualDisplayMode,
    )
    val bilingualGap = if (rows.size > 1) HifiSpacing.lyricBilingualGap else 0.dp
    val cueRanges = remember(lyricLine) { lyricLine?.let(::lyricCueRanges).orEmpty() }
    val canFillLineTimed = lyricLineFillEnabled &&
        lyricLine != null &&
        (lyricLine.timeMs > 0 || nextLineTimeMs != null)
    val shouldRunFillClock = isCurrent && lyricLine != null && (cueRanges.isNotEmpty() || canFillLineTimed)
    val fillPositionMs = rememberLyricFrameClockPositionMs(
        anchorPositionMs = positionMs,
        isPlaying = isPlaying && shouldRunFillClock,
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(bilingualGap),
    ) {
        rows.forEach { row ->
            val rowHasCueRanges = cueRanges.any { it.overlaps(row) }
            val rowTextStyle = if (row.splitIndex > 0) translationTextStyle else textStyle
            if (cueRanges.isNotEmpty() && rowHasCueRanges) {
                val wordSync = if (isCurrent && lyricLine != null) {
                    wordSyncedFill(
                        line = lyricLine,
                        row = row,
                        cueRanges = cueRanges,
                        positionMs = fillPositionMs,
                        nextLineTimeMs = nextLineTimeMs,
                        discreteActiveCue = karaokeSyllableLift,
                    )
                } else {
                    WordSyncedFill.Empty
                }
                KaraokeLyricLineText(
                    text = row.text,
                    isCurrent = isCurrent,
                    colors = colors,
                    textStyle = rowTextStyle,
                    fillFraction = wordSync.fillFraction,
                    liftedCharacterRange = wordSync.activeRange,
                    syllableProgress = wordSync.activeProgress,
                    syllableLiftEnabled = karaokeSyllableLift,
                    wordFadeWidthEm = if (karaokeSyllableLift) 0f else karaokeWordFadeWidthEm,
                    maxLines = maxLines,
                    textAlign = textAlign,
                )
            } else if (
                isCurrent &&
                canFillLineTimed
            ) {
                KaraokeLyricLineText(
                    text = row.text,
                    isCurrent = true,
                    colors = colors,
                    textStyle = rowTextStyle,
                    fillFraction = lineTimedFillFraction(
                        line = lyricLine,
                        row = row,
                        positionMs = fillPositionMs,
                        nextLineTimeMs = nextLineTimeMs,
                        syncDisplayRowFill = rows.size > 1,
                    ),
                    maxLines = maxLines,
                    textAlign = textAlign,
                )
            } else {
                AnimatedLyricLineText(
                    text = row.text,
                    isCurrent = isCurrent,
                    colors = colors,
                    textStyle = rowTextStyle,
                    colorSpec = colorSpec,
                    maxLines = maxLines,
                    textAlign = textAlign,
                )
            }
        }
    }
}

private data class LyricCueRange(val cueIndex: Int, val start: Int, val endExclusive: Int)

private fun LyricCueRange.overlaps(row: LyricDisplayRows.DisplayRow): Boolean =
    endExclusive > row.start && start < row.endExclusive

private fun lyricCueRanges(line: LyricLine): List<LyricCueRange> {
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
            add(LyricCueRange(index, start, end))
            searchFrom = end
        }
    }
}

@Composable
private fun rememberLyricFrameClockPositionMs(
    anchorPositionMs: Int,
    isPlaying: Boolean,
): Int {
    var framePositionMs by remember { mutableIntStateOf(anchorPositionMs) }
    LaunchedEffect(anchorPositionMs, isPlaying) {
        framePositionMs = anchorPositionMs
        if (!isPlaying) return@LaunchedEffect
        val startFrameNanos = withFrameNanos { it }
        while (true) {
            val frameNanos = withFrameNanos { it }
            val elapsedMs = ((frameNanos - startFrameNanos) / 1_000_000L).toInt()
            framePositionMs = anchorPositionMs + elapsedMs
        }
    }
    return framePositionMs
}

private fun lineTimedFillFraction(
    line: LyricLine,
    row: LyricDisplayRows.DisplayRow,
    positionMs: Int,
    nextLineTimeMs: Int?,
    syncDisplayRowFill: Boolean,
): Float {
    val lineDuration = lyricFillEndTime(line.timeMs, nextLineTimeMs) - line.timeMs
    if (lineDuration <= 0) return 1f
    val lineProgress = ((positionMs + LyricsSync.LEAD_MS - line.timeMs).toFloat() / lineDuration)
        .coerceIn(0f, 1f)
    if (syncDisplayRowFill) return lineProgress
    return rowFillFraction(row, lineProgress, line.text.length)
}

private data class WordSyncedFill(
    val fillFraction: Float,
    val activeRange: IntRange? = null,
    val activeProgress: Float = 0f,
) {
    companion object {
        val Empty = WordSyncedFill(0f)
    }
}

private fun wordSyncedFill(
    line: LyricLine,
    row: LyricDisplayRows.DisplayRow,
    cueRanges: List<LyricCueRange>,
    positionMs: Int,
    nextLineTimeMs: Int?,
    discreteActiveCue: Boolean,
): WordSyncedFill {
    val cueCount = line.cues.size
    if (cueCount == 0) return WordSyncedFill.Empty
    val t = positionMs + LyricsSync.LEAD_MS
    if (t < line.cues.first().timeMs) return WordSyncedFill.Empty

    val activeCueIndex = LyricsSync.cueIndexForPosition(line, positionMs)
    if (activeCueIndex < 0) return WordSyncedFill.Empty
    val activeRange = cueRanges.firstOrNull { it.cueIndex == activeCueIndex }
    if (activeRange == null) {
        val completedTextFraction = (activeCueIndex + 1).toFloat() / cueCount
        return WordSyncedFill(rowFillFraction(row, completedTextFraction, line.text.length))
    }

    val cueStart = line.cues[activeCueIndex].timeMs
    val cueEnd = line.cues.getOrNull(activeCueIndex + 1)?.timeMs
        ?: lyricFillEndTime(cueStart, nextLineTimeMs)
    val cueProgress = if (cueEnd <= cueStart) {
        1f
    } else {
        ((t - cueStart).toFloat() / (cueEnd - cueStart)).coerceIn(0f, 1f)
    }
    val filledChars = if (discreteActiveCue) {
        activeRange.endExclusive.toFloat()
    } else {
        activeRange.start + (activeRange.endExclusive - activeRange.start) * cueProgress
    }
    val localStart = (activeRange.start - row.start).coerceAtLeast(0)
    val localEnd = (activeRange.endExclusive - row.start).coerceAtMost(row.text.length)
    return WordSyncedFill(
        fillFraction = rowFillFraction(row, filledChars / line.text.length.coerceAtLeast(1), line.text.length),
        activeRange = if (localEnd > localStart) localStart until localEnd else null,
        activeProgress = cueProgress,
    )
}

@Composable
private fun KaraokeLyricLineText(
    text: String,
    isCurrent: Boolean,
    colors: PlayerContentColors,
    textStyle: TextStyle,
    fillFraction: Float,
    liftedCharacterRange: IntRange? = null,
    syllableProgress: Float = 0f,
    syllableLiftEnabled: Boolean = false,
    wordFadeWidthEm: Float = 0f,
    maxLines: Int,
    textAlign: TextAlign = TextAlign.Center,
) {
    val lineText = text.takeIf { it.isNotBlank() } ?: LYRIC_LINE_PLACEHOLDER
    val overflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip
    val style = textStyle.copy(fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
    var layout by remember(lineText, style, maxLines) { mutableStateOf<TextLayoutResult?>(null) }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = lineText,
            style = style,
            color = colors.tertiary,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
            modifier = Modifier.fillMaxWidth(),
            onTextLayout = { layout = it },
        )
        Text(
            text = lineText,
            style = style,
            color = colors.primary,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    val textLayout = layout
                    if (textLayout == null || fillFraction <= 0f) return@drawWithContent
                    val fraction = fillFraction.coerceIn(0f, 1f)
                    val lineCount = textLayout.lineCount
                    var remainingFillPx = (0 until lineCount).sumOf { lineIndex ->
                        (textLayout.getLineRight(lineIndex) - textLayout.getLineLeft(lineIndex)).toDouble()
                    }.toFloat() * fraction
                    for (lineIndex in 0 until lineCount) {
                        val left = textLayout.getLineLeft(lineIndex)
                        val right = textLayout.getLineRight(lineIndex)
                        val lineWidth = (right - left).coerceAtLeast(0f)
                        if (lineWidth <= 0f || remainingFillPx <= 0f) continue
                        val fillRight = (left + remainingFillPx.coerceAtMost(lineWidth)).coerceIn(left, right)
                        val featherPx = style.fontSize.toPx() * wordFadeWidthEm.coerceAtLeast(0f)
                        val featherRight = (fillRight + featherPx).coerceAtMost(right)
                        clipRect(
                            left = left,
                            top = textLayout.getLineTop(lineIndex),
                            right = featherRight,
                            bottom = textLayout.getLineBottom(lineIndex),
                        ) {
                            if (featherPx <= 0f || fillRight >= right) {
                                this@drawWithContent.drawContent()
                            } else {
                                val bounds = Rect(
                                    left,
                                    textLayout.getLineTop(lineIndex),
                                    featherRight,
                                    textLayout.getLineBottom(lineIndex),
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
                        remainingFillPx -= lineWidth
                    }
                },
        )
        if (syllableLiftEnabled && isCurrent && liftedCharacterRange != null) {
            val progress = syllableProgress.coerceIn(0f, 1f)
            val liftProgress = if (progress < 0.5f) {
                AppleLyricsWordEasing.transform(progress * 2f)
            } else {
                1f - AppleLyricsWordEasing.transform((progress - 0.5f) * 2f)
            }
            val liftPx = with(LocalDensity.current) { 2.dp.toPx() } * liftProgress
            Text(
                text = lineText,
                style = style,
                color = colors.primary,
                textAlign = textAlign,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        val textLayout = layout ?: return@drawWithContent
                        val start = liftedCharacterRange.first.coerceIn(0, lineText.lastIndex)
                        val end = liftedCharacterRange.last.coerceIn(start, lineText.lastIndex)
                        for (offset in start..end) {
                            val bounds = textLayout.getBoundingBox(offset)
                            clipRect(bounds.left, bounds.top - liftPx, bounds.right, bounds.bottom) {
                                translate(top = -liftPx) { this@drawWithContent.drawContent() }
                            }
                        }
                    },
            )
        }
    }
}

private val AppleLyricsWordEasing = CubicBezierEasing(0.39f, 0.575f, 0.565f, 1f)

private fun lyricFillEndTime(startMs: Int, nextTimeMs: Int?): Int =
    nextTimeMs?.takeIf { it > startMs } ?: (startMs + LyricFillFallbackDurationMs)

private fun rowFillFraction(
    row: LyricDisplayRows.DisplayRow,
    lineFillFraction: Float,
    lineLength: Int,
): Float {
    val rowLength = (row.endExclusive - row.start).coerceAtLeast(1)
    val filledChars = lineFillFraction.coerceIn(0f, 1f) * lineLength.coerceAtLeast(1)
    return ((filledChars - row.start) / rowLength).coerceIn(0f, 1f)
}

@Composable
fun AnimatedLyricLineText(
    text: String,
    isCurrent: Boolean,
    colors: PlayerContentColors,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    colorSpec: androidx.compose.animation.core.AnimationSpec<Color> = rememberLyricLineColorSpec(),
    maxLines: Int = 1,
    textAlign: TextAlign = TextAlign.Center,
) {
    val color by animateColorAsState(
        targetValue = if (isCurrent) colors.primary else colors.tertiary,
        animationSpec = colorSpec,
        label = "lyricLineColor",
    )
    Text(
        text = text.takeIf { it.isNotBlank() } ?: LYRIC_LINE_PLACEHOLDER,
        style = textStyle.copy(
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        ),
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 播放页紧凑歌词相对全屏歌词的字号比例（缩小 1/3）。 */
const val PlayerPanelLyricScale = 2f / 3f

private fun TextStyle.scaledForPlayerPanel(): TextStyle = copy(
    fontSize = fontSize * PlayerPanelLyricScale,
    lineHeight = lineHeight * PlayerPanelLyricScale,
)

/** 播放页歌词：统一为放大/未放大字号的平均值（×[PlayerPanelLyricScale]），仅颜色高亮当前句。 */
@Composable
fun rememberPlayerPanelLyricStyle(): TextStyle {
    val typography = MicaTheme.typography
    return remember(typography) {
        lyricUniformTextStyle(
            typography.lyricCurrent.scaledForPlayerPanel(),
            typography.lyricOther.scaledForPlayerPanel(),
        )
    }
}
