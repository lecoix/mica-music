package com.mica.music.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsSync
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors

private val ExpandedLyricCurrentStyle
    @Composable get() = MicaTheme.typography.lyricCurrent.copy(
        fontSize = 26.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
    )

private val ExpandedLyricOtherStyle
    @Composable get() = MicaTheme.typography.lyricOther.copy(
        fontSize = 18.sp,
        lineHeight = 28.sp,
    )

@Composable
internal fun ExpandedLyricsPanel(
    lyrics: List<LyricLine>,
    positionMs: Int,
    colors: PlayerContentColors,
    onLineClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = HifiSpacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "暂无歌词",
                style = ExpandedLyricOtherStyle,
                color = colors.tertiary,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val timed = LyricsSync.hasTimedLyrics(lyrics)
    val currentIndex = LyricsSync.indexForPosition(lyrics, positionMs)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val lineHeightPx = with(density) { 36.dp.roundToPx() }

    LaunchedEffect(currentIndex, timed) {
        if (!timed || currentIndex < 0) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        val offset = -((viewport - lineHeightPx) / 2).coerceAtLeast(0)
        listState.animateScrollToItem(currentIndex, scrollOffset = offset)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
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
            val textColor by animateColorAsState(
                targetValue = if (isCurrent) colors.primary else colors.tertiary,
                animationSpec = tween(400),
                label = "expandedLyricColor",
            )
            Text(
                text = line.text,
                style = if (isCurrent) ExpandedLyricCurrentStyle else ExpandedLyricOtherStyle,
                color = textColor,
                textAlign = TextAlign.Center,
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
