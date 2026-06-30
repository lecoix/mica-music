package com.mica.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.DEFAULT_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.LyricsSync
import com.mica.music.ui.components.LyricLineBlock
import com.mica.music.ui.components.LyricsAreaEdgeFade
import com.mica.music.ui.components.rememberLyricLineColorSpec
import com.mica.music.ui.components.rememberLyricUniformStyle
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalLyricSplitEnabled
import com.mica.music.ui.theme.PlayerContentColors

@Composable
internal fun ExpandedLyricsPanel(
    lyrics: List<LyricLine>,
    positionMs: Int,
    isPlaying: Boolean,
    colors: PlayerContentColors,
    onLineClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    lyricsAlignment: LyricsPageAlignment = LyricsPageAlignment.CENTER,
    lyricsFontSizeSp: Int = DEFAULT_LYRICS_PAGE_FONT_SIZE_SP,
    bilingualDisplayMode: LyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
) {
    val textStyle = rememberLyricUniformStyle().withFontSizeSp(lyricsFontSizeSp)
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

    val timed = LyricsSync.hasTimedLyrics(lyrics)
    val currentIndex = LyricsSync.indexForPosition(lyrics, positionMs)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val lineHeightPx = with(density) { textStyle.lineHeight.toPx().toInt() }

    LaunchedEffect(currentIndex, timed, lyrics) {
        if (!timed || currentIndex < 0) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        val currentRows = lyrics.getOrNull(currentIndex)?.text
            ?.let {
                LyricDisplayRows.rowsForBilingualDisplayMode(
                    text = it,
                    enabled = lyricSplitEnabled,
                    mode = bilingualDisplayMode,
                ).size
            } ?: 1
        val bilingualGapPx = with(density) { HifiSpacing.lyricBilingualGap.roundToPx() }
        val itemHeightPx = lineHeightPx * currentRows + bilingualGapPx * (currentRows - 1).coerceAtLeast(0)
        val offset = -((viewport - itemHeightPx) / 2).coerceAtLeast(0)
        listState.animateScrollToItem(currentIndex, scrollOffset = offset)
    }

    LyricsAreaEdgeFade(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = HifiSpacing.sm,
                bottom = HifiSpacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.lg),
            horizontalAlignment = horizontalAlignment,
        ) {
            itemsIndexed(
                lyrics,
                key = { index, line -> "$index-${line.timeMs}-${line.text}" },
            ) { index, line ->
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
        }
    }
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
