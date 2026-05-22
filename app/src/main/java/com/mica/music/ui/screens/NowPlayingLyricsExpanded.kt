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
import androidx.compose.ui.text.style.TextAlign
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
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
    colors: PlayerContentColors,
    onLineClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyle = rememberLyricUniformStyle()
    val colorSpec = rememberLyricLineColorSpec()
    val lyricSplitEnabled = LocalLyricSplitEnabled.current

    if (!lyrics.hasDisplayableLyrics()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = HifiSpacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = EmptyLyricsText,
                style = textStyle,
                color = colors.secondary,
                textAlign = TextAlign.Center,
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
            ?.let { LyricDisplayRows.splitForDisplay(it, lyricSplitEnabled).size } ?: 1
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
                start = HifiSpacing.lg,
                end = HifiSpacing.lg,
                top = HifiSpacing.sm,
                bottom = HifiSpacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
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
