package com.mica.music.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.DEFAULT_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.LyricsRenderState
import com.mica.music.ui.components.LyricLineBlock
import com.mica.music.ui.components.LyricsAreaEdgeFade
import com.mica.music.ui.components.rememberLyricLineColorSpec
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
    bilingualDisplayMode: LyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
    currentLineAnchorYPx: Float? = null,
) {
    val lyrics = renderState.lyrics
    val positionMs = renderState.positionMs
    val textStyle = rememberLyricUniformStyle().withFontSizeSp(lyricsFontSizeSp)
    val translationTextStyle = rememberLyricUniformStyle().withFontSizeSp(lyricsTranslationFontSizeSp)
    val colorSpec = rememberLyricLineColorSpec()
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
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val lineHeightPx = with(density) { textStyle.lineHeight.toPx().toInt() }
    val translationLineHeightPx = with(density) { translationTextStyle.lineHeight.toPx().toInt() }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var currentLineInitiallyPlaced by remember(lyrics) { mutableStateOf(false) }

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
        if (currentLineInitiallyPlaced) {
            listState.animateScrollToItem(currentDisplayItemIndex, scrollOffset = offset)
        } else {
            listState.scrollToItem(currentDisplayItemIndex, scrollOffset = offset)
            currentLineInitiallyPlaced = true
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
                top = HifiSpacing.sm,
                bottom = HifiSpacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.lg),
            horizontalAlignment = horizontalAlignment,
        ) {
            items(
                items = displayItems,
                key = { item -> item.key },
            ) { item ->
                when (item) {
                    is ExpandedLyricDisplayItem.Line -> {
                        val index = item.lyricIndex
                        val line = item.line
                        val isCurrent = timed && index == currentIndex
                        LyricLineBlock(
                            text = line.text,
                            isCurrent = isCurrent,
                            colors = colors,
                            textStyle = textStyle,
                            colorSpec = colorSpec,
                            maxLines = Int.MAX_VALUE,
                            lyricLine = line,
                            nextLineTimeMs = lyrics.getOrNull(index + 1)?.timeMs,
                            positionMs = positionMs,
                            isPlaying = isPlaying,
                            textAlign = textAlign,
                            horizontalAlignment = horizontalAlignment,
                            bilingualDisplayMode = bilingualDisplayMode,
                            translationTextStyle = translationTextStyle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (timed) {
                                        Modifier.clickable { onLineClick(line.timeMs) }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    is ExpandedLyricDisplayItem.Interlude -> InterludeDots(
                        colors = colors,
                        animate = isPlaying,
                    )
                }
            }
        }
    }
}

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
    ) : ExpandedLyricDisplayItem {
        override val key: String = "interlude-$nextLyricIndex"
    }
}

private const val MIN_NEXT_LYRIC_DELTA_FOR_INTERLUDE_MS = 7_000

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
    val deltaMs = lyrics[nextLyricIndex].timeMs - playbackPositionMs
    if (deltaMs < MIN_NEXT_LYRIC_DELTA_FOR_INTERLUDE_MS) return null

    return ExpandedLyricDisplayItem.Interlude(nextLyricIndex)
}

@Composable
private fun InterludeDots(
    colors: PlayerContentColors,
    animate: Boolean,
) {
    val motionEnabled = rememberMicaMotionEnabled()
    val transition = rememberInfiniteTransition(label = "lyricsInterlude")
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha = if (animate && motionEnabled) {
                transition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 520, delayMillis = index * 160),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "lyricsInterludeDot$index",
                ).value
            } else {
                0.85f
            }
            val scale = if (animate && motionEnabled) {
                transition.animateFloat(
                    initialValue = 0.72f,
                    targetValue = 1.28f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 520, delayMillis = index * 160),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "lyricsInterludeDotScale$index",
                ).value
            } else {
                1f
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(colors.primary),
            )
        }
    }
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
