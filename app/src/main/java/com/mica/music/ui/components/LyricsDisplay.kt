package com.mica.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.LyricDisplayRows
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors

private const val LYRIC_LINE_PLACEHOLDER = "\u00A0"

@Composable
fun rememberLyricLineColorSpec() =
    MicaMotion.tweenColor(rememberMicaMotionEnabled(), MicaMotion.DurationLongMs)

// DstIn 只读 alpha；用白/透明梯度，避免未离屏合成时黑色 RGB 被看见。
private val LyricFadeMaskOpaque = Color.White
private val LyricFadeMaskClear = Color.White.copy(alpha = 0f)

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

@Composable
fun LyricLineBlock(
    text: String?,
    isCurrent: Boolean,
    colors: PlayerContentColors,
    currentStyle: TextStyle,
    otherStyle: TextStyle,
    modifier: Modifier = Modifier,
    colorSpec: androidx.compose.animation.core.AnimationSpec<Color> = rememberLyricLineColorSpec(),
) {
    val style = if (isCurrent) currentStyle else otherStyle
    val rows = LyricDisplayRows.splitForDisplay(text.orEmpty())
    val bilingualGap = if (rows.size > 1) HifiSpacing.lyricBilingualGap else 0.dp
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(bilingualGap),
    ) {
        rows.forEach { row ->
            AnimatedLyricLineText(
                text = row,
                style = style,
                isCurrent = isCurrent,
                colors = colors,
                colorSpec = colorSpec,
            )
        }
    }
}

@Composable
fun AnimatedLyricLineText(
    text: String,
    style: TextStyle,
    isCurrent: Boolean,
    colors: PlayerContentColors,
    modifier: Modifier = Modifier,
    colorSpec: androidx.compose.animation.core.AnimationSpec<Color> = rememberLyricLineColorSpec(),
) {
    val color by animateColorAsState(
        targetValue = if (isCurrent) colors.primary else colors.tertiary,
        animationSpec = colorSpec,
        label = "lyricLineColor",
    )
    Text(
        text = text.takeIf { it.isNotBlank() } ?: LYRIC_LINE_PLACEHOLDER,
        style = style,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun lyricTransitionSpec(motionEnabled: Boolean) =
    remember(motionEnabled) {
        fadeIn(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationLongMs)) togetherWith
            fadeOut(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationLongMs))
    }

/** 播放页紧凑歌词相对全屏歌词的字号比例（缩小 1/3）。 */
const val PlayerPanelLyricScale = 2f / 3f

private fun TextStyle.scaledForPlayerPanel(): TextStyle = copy(
    fontSize = fontSize * PlayerPanelLyricScale,
    lineHeight = lineHeight * PlayerPanelLyricScale,
)

/** 播放页歌词：字重/动效与歌词页一致，字号为歌词页的 [PlayerPanelLyricScale]。 */
@Composable
fun rememberPlayerPanelLyricStyles(): Pair<TextStyle, TextStyle> {
    val typography = MicaTheme.typography
    return remember(typography) {
        typography.lyricCurrent.scaledForPlayerPanel() to
            typography.lyricOther.scaledForPlayerPanel()
    }
}
