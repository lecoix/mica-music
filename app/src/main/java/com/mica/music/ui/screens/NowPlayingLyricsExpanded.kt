package com.mica.music.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.DEFAULT_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.DEFAULT_LYRICS_PAGE_LINE_SPACING_DP
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.LyricsWordAnimationPreset
import com.mica.music.data.LyricsRenderState
import com.mica.music.ui.components.LyricLineBlock
import com.mica.music.ui.components.LyricsAreaEdgeFade
import com.mica.music.ui.components.rememberLyricUniformStyle
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalLyricSplitEnabled
import com.mica.music.ui.theme.PlayerContentColors
import kotlin.math.roundToInt

@Composable
internal fun ExpandedLyricsPanel(
    renderState: LyricsRenderState,
    isPlaying: Boolean,
    colors: PlayerContentColors,
    onLineClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    lyricsAlignment: LyricsPageAlignment = LyricsPageAlignment.CENTER,
    lyricsFontSizeSp: Int = DEFAULT_LYRICS_PAGE_FONT_SIZE_SP,
    lyricsTranslationFontSizeSp: Int = lyricsFontSizeSp,
    lyricsLineSpacingDp: Int = DEFAULT_LYRICS_PAGE_LINE_SPACING_DP,
    lyricsWordAnimationPreset: LyricsWordAnimationPreset = LyricsWordAnimationPreset.SYLLABLE_LIFT,
    bilingualDisplayMode: LyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
) {
    val lyrics = renderState.lyrics
    val positionMs = renderState.positionMs
    val textStyle = rememberLyricUniformStyle().withFontSizeSp(lyricsFontSizeSp)
    val translationTextStyle = rememberLyricUniformStyle().withFontSizeSp(lyricsTranslationFontSizeSp)
    val lyricSplitEnabled = LocalLyricSplitEnabled.current
    val textAlign = lyricsAlignment.toTextAlign()
    val horizontalAlignment = lyricsAlignment.toHorizontalAlignment()
    val horizontalPadding = if (lyricsAlignment == LyricsPageAlignment.CENTER) {
        HifiSpacing.lg
    } else {
        HifiSpacing.lg * 1.5f
    }

    if (!lyrics.hasDisplayableLyrics()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = EmptyLyricsText,
                style = textStyle,
                color = colors.secondary,
                textAlign = textAlign,
            )
        }
        return
    }

    val displayItems = remember(lyrics, positionMs) {
        expandedLyricsDisplayItems(lyrics, playbackPositionMs = positionMs)
    }
    val timed = renderState.hasTimedLyrics
    val currentIndex = renderState.activeLineIndex
    val currentDisplayItemIndex = displayItems.indexOfFirst { item ->
        item is ExpandedLyricDisplayItem.Line && item.lyricIndex == currentIndex
    }
    val interludeKey = displayItems.firstOrNull { it is ExpandedLyricDisplayItem.Interlude }?.key
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val lineHeightPx = with(density) { textStyle.lineHeight.toPx().toInt() }
    val translationLineHeightPx = with(density) { translationTextStyle.lineHeight.toPx().toInt() }
    val currentLineAnchorYPx = lineHeightPx * CLASSIC_LYRICS_ANCHOR_LINE_HEIGHTS
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val leadingPaddingPx = maxOf(
        with(density) { HifiSpacing.sm.roundToPx() },
        expandedLyricsLeadingPaddingPx(
            viewportHeightPx = viewportHeightPx,
            itemHeightPx = lineHeightPx,
            currentLineAnchorYPx = currentLineAnchorYPx,
        ),
    )
    val leadingPadding = with(density) { leadingPaddingPx.toDp() }
    val trailingPadding = HifiSpacing.xl + with(density) {
        expandedLyricsTrailingPaddingPx(viewportHeightPx, currentLineAnchorYPx).toDp()
    }
    val staggerOffsets = remember { mutableStateMapOf<Int, Float>() }
    val motionEnabled = rememberMicaMotionEnabled()
    val lineIntervalMs = currentIndex.takeIf { it > 0 }?.let { index ->
        lyrics[index].timeMs - lyrics[index - 1].timeMs
    } ?: 800
    val moveSpring = classicLyricsMoveSpring(lineIntervalMs)
    var previousInterludeKey by remember(lyrics) { mutableStateOf(interludeKey) }

    LaunchedEffect(interludeKey, motionEnabled, lyricsLineSpacingDp) {
        val interludeAppeared = previousInterludeKey == null && interludeKey != null
        previousInterludeKey = interludeKey
        if (!interludeAppeared) return@LaunchedEffect
        val insertionHeightPx = with(density) { (10.dp + lyricsLineSpacingDp.dp).toPx() }
        if (motionEnabled) {
            listState.animateScrollBy(
                insertionHeightPx,
                animationSpec = classicLyricsScrollSpring(lineIntervalMs),
            )
        } else {
            listState.scrollBy(insertionHeightPx)
        }
    }

    LaunchedEffect(
        currentIndex,
        currentDisplayItemIndex,
        timed,
        lyrics,
        currentLineAnchorYPx,
        viewportHeightPx,
        lineHeightPx,
        translationLineHeightPx,
    ) {
        if (!timed || currentIndex < 0 || currentDisplayItemIndex < 0) return@LaunchedEffect
        if (viewportHeightPx <= 0) return@LaunchedEffect
        staggerOffsets.clear()
        val currentRows = lyrics.getOrNull(currentIndex)?.text
            ?.let {
                LyricDisplayRows.rowsForBilingualDisplayMode(
                    text = it,
                    enabled = lyricSplitEnabled,
                    mode = bilingualDisplayMode,
                )
            }.orEmpty()
        val bilingualGapPx = with(density) { HifiSpacing.lyricBilingualGap.roundToPx() }
        val itemHeightPx = if (currentRows.isEmpty()) {
            lineHeightPx
        } else {
            currentRows.sumOf { row ->
                if (row.splitIndex > 0) translationLineHeightPx else lineHeightPx
            } + bilingualGapPx * (currentRows.size - 1).coerceAtLeast(0)
        }
        val offset = expandedLyricsScrollOffset(
            viewportHeightPx = viewportHeightPx,
            itemHeightPx = itemHeightPx,
            currentLineAnchorYPx = currentLineAnchorYPx,
        )
        val indexedScrollOffset = expandedLyricsIndexedScrollOffset(leadingPaddingPx, offset)
        if (motionEnabled) {
            val visibleTarget = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == currentDisplayItemIndex }
            if (visibleTarget != null) {
                val desiredTopPx = -offset.toFloat()
                val scrollDistance = visibleTarget.offset - desiredTopPx
                val scrollAnimation = TargetBasedAnimation(
                    animationSpec = classicLyricsScrollSpring(lineIntervalMs),
                    typeConverter = Float.VectorConverter,
                    initialValue = 0f,
                    targetValue = scrollDistance,
                )
                var previousScroll = 0f
                var maxDelayNanos = 0L
                val startNanos = withFrameNanos { it }
                try {
                    while (true) {
                        val playTimeNanos = (withFrameNanos { it } - startNanos).coerceAtLeast(0L)
                        val actualPlayTimeNanos = playTimeNanos.coerceAtMost(scrollAnimation.durationNanos)
                        val actualScroll = scrollAnimation.getValueFromNanos(actualPlayTimeNanos)
                        listState.scrollBy(actualScroll - previousScroll)
                        previousScroll = actualScroll

                        listState.layoutInfo.visibleItemsInfo.forEach { itemInfo ->
                            val delayNanos = classicLyricsStaggerDelayMs(
                                kotlin.math.abs(itemInfo.index - currentDisplayItemIndex),
                            ) * 1_000_000L
                            maxDelayNanos = maxOf(maxDelayNanos, delayNanos)
                            val delayedPlayTimeNanos = (playTimeNanos - delayNanos)
                                .coerceIn(0L, scrollAnimation.durationNanos)
                            val delayedScroll = scrollAnimation.getValueFromNanos(delayedPlayTimeNanos)
                            staggerOffsets[itemInfo.index] = classicLyricsLagOffset(
                                actualScrollPx = actualScroll,
                                delayedScrollPx = delayedScroll,
                            )
                        }
                        if (playTimeNanos >= scrollAnimation.durationNanos + maxDelayNanos) break
                    }
                } finally {
                    staggerOffsets.clear()
                }
            } else {
                listState.scrollToItem(currentDisplayItemIndex, scrollOffset = indexedScrollOffset)
            }
        } else {
            listState.scrollToItem(currentDisplayItemIndex, scrollOffset = indexedScrollOffset)
        }
    }

    LyricsAreaEdgeFade(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportHeightPx = it.height },
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = leadingPadding,
                bottom = trailingPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(lyricsLineSpacingDp.dp),
            horizontalAlignment = horizontalAlignment,
        ) {
            itemsIndexed(
                items = displayItems,
                key = { _, item -> item.key },
            ) { displayIndex, item ->
                val staggerOffsetY = staggerOffsets[displayIndex] ?: 0f
                when (item) {
                    is ExpandedLyricDisplayItem.Line -> {
                        val index = item.lyricIndex
                        val line = item.line
                        val isCurrent = timed && index == currentIndex
                        val lineScale by animateFloatAsState(
                            targetValue = if (isCurrent) 1f else 0.97f,
                            animationSpec = spring(
                                stiffness = 100f,
                                dampingRatio = 0.88f,
                            ),
                            label = "classicLyricsLineScale",
                        )
                        LyricLineBlock(
                            text = line.text,
                            isCurrent = isCurrent,
                            colors = colors,
                            textStyle = textStyle,
                            colorSpec = if (isCurrent) {
                                tween(250, delayMillis = 250, easing = ClassicLyricsColorEasing)
                            } else {
                                tween(350, delayMillis = 250, easing = ClassicLyricsColorEasing)
                            },
                            maxLines = Int.MAX_VALUE,
                            lyricLine = line,
                            nextLineTimeMs = lyrics.getOrNull(index + 1)?.timeMs,
                            positionMs = positionMs,
                            isPlaying = isPlaying,
                            textAlign = textAlign,
                            horizontalAlignment = horizontalAlignment,
                            bilingualDisplayMode = bilingualDisplayMode,
                            translationTextStyle = translationTextStyle,
                            karaokeSyllableLift = lyricsWordAnimationPreset.syllableLiftEnabled,
                            karaokeDiscreteActiveCue = lyricsWordAnimationPreset.usesDiscreteCueFill,
                            karaokeWordFadeWidthEm = lyricsWordAnimationPreset.wordFadeWidthEm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(
                                    fadeInSpec = tween(CLASSIC_LYRICS_FADE_MS),
                                    fadeOutSpec = tween(CLASSIC_LYRICS_FADE_MS),
                                    placementSpec = moveSpring,
                                )
                                .graphicsLayer {
                                    scaleX = lineScale
                                    scaleY = lineScale
                                    translationY = staggerOffsetY
                                }
                                .then(
                                    if (timed) {
                                        Modifier.clickable { onLineClick(line.timeMs) }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    is ExpandedLyricDisplayItem.Interlude -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { translationY = staggerOffsetY }
                            .animateItem(
                                fadeInSpec = tween(CLASSIC_LYRICS_FADE_MS),
                                fadeOutSpec = tween(CLASSIC_LYRICS_FADE_MS),
                                placementSpec = moveSpring,
                            ),
                    ) {
                        InterludeDots(
                            colors = colors,
                            animate = isPlaying,
                            startTimeMs = item.startTimeMs,
                            endTimeMs = (item.endTimeMs - INTERLUDE_END_LEAD_MS)
                                .coerceAtLeast(item.startTimeMs + 1),
                            positionMs = positionMs,
                            alignment = lyricsAlignment,
                        )
                    }
                }
            }
        }
    }
}

internal fun classicLyricsStaggerDelayMs(distance: Int): Long {
    var delaySeconds = 0.0
    var stepSeconds = 0.05
    repeat(distance.coerceAtLeast(0)) {
        delaySeconds += stepSeconds
        stepSeconds /= 1.05
    }
    return (delaySeconds * 1_000).toLong()
}

internal fun classicLyricsLagOffset(actualScrollPx: Float, delayedScrollPx: Float): Float =
    (actualScrollPx - delayedScrollPx) * 0.25f

/**
 * Full-screen lyrics render display items rather than raw lyric rows, so later interlude items
 * cannot break the mapping used by scrolling and seek actions.
 */
internal sealed interface ExpandedLyricDisplayItem {
    val key: String

    data class Line(
        val lyricIndex: Int,
        val line: LyricLine,
    ) : ExpandedLyricDisplayItem {
        override val key: String = "line-$lyricIndex-${line.timeMs}-${line.text}"
    }

    data class Interlude(
        val nextLyricIndex: Int,
        val startTimeMs: Int,
        val endTimeMs: Int,
    ) : ExpandedLyricDisplayItem {
        override val key: String = "interlude-$nextLyricIndex"
    }
}

private const val MIN_NEXT_LYRIC_DELTA_FOR_INTERLUDE_MS = 7_000
private const val CLASSIC_LYRICS_ANCHOR_LINE_HEIGHTS = 3f
private const val INTERLUDE_END_LEAD_MS = 500
private const val INTERLUDE_TAIL_MS = 800
private const val INTERLUDE_DOT_STAGE_MS = 750
private const val CLASSIC_LYRICS_FADE_MS = 250
private val ClassicLyricsColorEasing = CubicBezierEasing(0.39f, 0.575f, 0.565f, 1f)

internal fun classicLyricsMoveSpring(intervalMs: Int) = spring<IntOffset>(
    stiffness = classicLyricsMoveStiffness(intervalMs),
    dampingRatio = CLASSIC_LYRICS_DYNAMIC_DAMPING_RATIO,
)

internal fun classicLyricsScrollSpring(intervalMs: Int) = spring<Float>(
    stiffness = classicLyricsMoveStiffness(intervalMs),
    dampingRatio = CLASSIC_LYRICS_DYNAMIC_DAMPING_RATIO,
)

internal fun classicLyricsMoveStiffness(intervalMs: Int): Float {
    val clamped = intervalMs.coerceIn(100, 800)
    val ratio = 1f - (clamped - 100f) / 700f
    return 170f + ratio.pow(0.2f) * 50f
}

private const val CLASSIC_LYRICS_DYNAMIC_DAMPING_RATIO = 1.16f

internal fun expandedLyricsDisplayItems(
    lyrics: List<LyricLine>,
    playbackPositionMs: Int? = null,
): List<ExpandedLyricDisplayItem> {
    val interlude = playbackPositionMs?.let { yInterludeForPosition(lyrics, it) }
    return buildList {
    lyrics.forEachIndexed { index, line ->
        if (interlude?.nextLyricIndex == index) add(interlude)
        add(ExpandedLyricDisplayItem.Line(index, line))
    }
    }
}

private fun yInterludeForPosition(
    lyrics: List<LyricLine>,
    playbackPositionMs: Int,
): ExpandedLyricDisplayItem.Interlude? {
    val activeLineIds = lyrics.mapIndexedNotNull { index, line ->
        index.takeIf { line.endTimeMs != null && playbackPositionMs in line.timeMs until line.endTimeMs }
    }
    if (activeLineIds.isNotEmpty()) return null

    val nextLyricIndex = lyrics.indexOfFirst { it.timeMs > playbackPositionMs }
    if (nextLyricIndex < 0) return null
    val previousLineEndMs = lyrics.getOrNull(nextLyricIndex - 1)?.endTimeMs ?: return null
    if (previousLineEndMs > playbackPositionMs) return null
    val nextLineTimeMs = lyrics[nextLyricIndex].timeMs
    if (nextLineTimeMs - previousLineEndMs < MIN_NEXT_LYRIC_DELTA_FOR_INTERLUDE_MS) return null

    return ExpandedLyricDisplayItem.Interlude(
        nextLyricIndex = nextLyricIndex,
        startTimeMs = previousLineEndMs,
        endTimeMs = nextLineTimeMs,
    )
}

@Composable
internal fun InterludeDots(
    colors: PlayerContentColors,
    animate: Boolean,
    startTimeMs: Int,
    endTimeMs: Int,
    positionMs: Int,
    alignment: LyricsPageAlignment,
) {
    val motionEnabled = rememberMicaMotionEnabled()
    val framePositionMs = rememberClassicInterludePositionMs(positionMs, animate && motionEnabled)
    val visuals = classicInterludeVisuals(startTimeMs, endTimeMs, framePositionMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = if (motionEnabled) visuals.globalAlpha else 0.85f
                scaleX = if (motionEnabled) visuals.scale else 1f
                scaleY = if (motionEnabled) visuals.scale else 1f
            },
        horizontalArrangement = when (alignment) {
            LyricsPageAlignment.START -> Arrangement.spacedBy(6.dp, Alignment.Start)
            LyricsPageAlignment.CENTER -> Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            LyricsPageAlignment.END -> Arrangement.spacedBy(6.dp, Alignment.End)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        alpha = if (motionEnabled) visuals.dotAlpha[index] else 1f
                    }
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(colors.primary),
            )
        }
    }
}

internal data class ClassicInterludeVisuals(
    val scale: Float,
    val globalAlpha: Float,
    val dotAlpha: List<Float>,
)

internal fun classicInterludeVisuals(startMs: Int, endMs: Int, positionMs: Int): ClassicInterludeVisuals {
    val duration = (endMs - startMs).coerceAtLeast(1)
    val elapsed = (positionMs - startMs).coerceIn(0, duration)
    val breatheDuration = duration.toFloat() / kotlin.math.ceil(duration / 1500f).coerceAtLeast(1f)
    var scale = kotlin.math.sin(1.5f * Math.PI.toFloat() - elapsed / breatheDuration * 2f) / 20f + 1f
    var globalAlpha = 1f

    if (elapsed < 2_000) scale *= 1f - 2f.pow(-10f * elapsed / 2_000f)
    if (elapsed < 500) globalAlpha = 0f
    else if (elapsed < 1_000) globalAlpha = (elapsed - 500f) / 500f

    val remaining = duration - elapsed
    if (remaining < 750) scale *= 1f - easeInOutBack((750f - remaining) / 1500f)
    if (remaining < 375) globalAlpha *= (remaining / 375f).coerceIn(0f, 1f)

    val dotsDuration = (duration - INTERLUDE_TAIL_MS).coerceAtLeast(1).toFloat()
    val dotAlpha = List(3) { index ->
        val offset = dotsDuration / 3f * index
        (((elapsed - offset) * 3f / dotsDuration) * 0.75f).coerceIn(0.25f, 1f)
    }
    return ClassicInterludeVisuals(
        scale = scale.coerceAtLeast(0f) * 0.7f,
        globalAlpha = globalAlpha.coerceIn(0f, 1f),
        dotAlpha = dotAlpha,
    )
}

private fun easeInOutBack(x: Float): Float {
    val c2 = 1.70158f * 1.525f
    return if (x < 0.5f) {
        (2f * x).pow(2) * ((c2 + 1f) * 2f * x - c2) / 2f
    } else {
        ((2f * x - 2f).pow(2) * ((c2 + 1f) * (2f * x - 2f) + c2) + 2f) / 2f
    }
}

private fun Float.pow(power: Float): Float = Math.pow(toDouble(), power.toDouble()).toFloat()
private fun Float.pow(power: Int): Float = pow(power.toFloat())

@Composable
private fun rememberClassicInterludePositionMs(anchorPositionMs: Int, running: Boolean): Int {
    var framePositionMs by remember { mutableIntStateOf(anchorPositionMs) }
    LaunchedEffect(anchorPositionMs, running) {
        framePositionMs = anchorPositionMs
        if (!running) return@LaunchedEffect
        val startNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            framePositionMs = anchorPositionMs + ((nowNanos - startNanos) / 1_000_000L).toInt()
        }
    }
    return framePositionMs
}

internal fun expandedLyricsScrollOffset(
    viewportHeightPx: Int,
    itemHeightPx: Int,
    currentLineAnchorYPx: Float?,
): Int {
    if (viewportHeightPx <= 0) return 0
    val anchor = currentLineAnchorYPx
        ?.takeIf { it.isFinite() && it > 0f }
        ?: (viewportHeightPx / 2f)
    return -((anchor - itemHeightPx / 2f).coerceAtLeast(0f)).roundToInt()
}

internal fun expandedLyricsIndexedScrollOffset(leadingPaddingPx: Int, viewportOffsetPx: Int): Int =
    leadingPaddingPx + viewportOffsetPx

internal fun expandedLyricsTrailingPaddingPx(
    viewportHeightPx: Int,
    currentLineAnchorYPx: Float?,
): Int {
    if (viewportHeightPx <= 0) return 0
    val anchor = currentLineAnchorYPx
        ?.takeIf { it.isFinite() && it > 0f }
        ?: (viewportHeightPx / 2f)
    return (viewportHeightPx - anchor).coerceAtLeast(0f).roundToInt()
}

internal fun expandedLyricsLeadingPaddingPx(
    viewportHeightPx: Int,
    itemHeightPx: Int,
    currentLineAnchorYPx: Float?,
): Int {
    if (viewportHeightPx <= 0) return 0
    val anchor = currentLineAnchorYPx
        ?.takeIf { it.isFinite() && it > 0f }
        ?: (viewportHeightPx / 2f)
    return (anchor - itemHeightPx / 2f).coerceAtLeast(0f).roundToInt()
}

private fun TextStyle.withFontSizeSp(fontSizeSp: Int): TextStyle {
    val lineHeightRatio = if (fontSize.value > 0f) lineHeight.value / fontSize.value else 1.45f
    return copy(
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * lineHeightRatio).sp,
    )
}

private fun LyricsPageAlignment.toTextAlign(): TextAlign = when (this) {
    LyricsPageAlignment.START -> TextAlign.Start
    LyricsPageAlignment.CENTER -> TextAlign.Center
    LyricsPageAlignment.END -> TextAlign.End
}

private fun LyricsPageAlignment.toHorizontalAlignment(): Alignment.Horizontal = when (this) {
    LyricsPageAlignment.START -> Alignment.Start
    LyricsPageAlignment.CENTER -> Alignment.CenterHorizontally
    LyricsPageAlignment.END -> Alignment.End
}
