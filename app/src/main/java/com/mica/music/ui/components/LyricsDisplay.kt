package com.mica.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.lerp as lerpTextUnit
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.LyricDisplayRows
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalLyricSplitEnabled
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors

private const val LYRIC_LINE_PLACEHOLDER = "\u00A0"

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
) {
    val lyricSplitEnabled = LocalLyricSplitEnabled.current
    val rows = LyricDisplayRows.splitForDisplay(text.orEmpty(), lyricSplitEnabled)
    val bilingualGap = if (rows.size > 1) HifiSpacing.lyricBilingualGap else 0.dp
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(bilingualGap),
    ) {
        rows.forEach { row ->
            AnimatedLyricLineText(
                text = row,
                isCurrent = isCurrent,
                colors = colors,
                textStyle = textStyle,
                colorSpec = colorSpec,
                maxLines = maxLines,
            )
        }
    }
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
        textAlign = TextAlign.Center,
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
