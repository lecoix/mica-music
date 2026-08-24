package com.mica.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsRenderState
import com.mica.music.ui.components.LyricLineBlock
import com.mica.music.ui.components.PlayerLyricsIndexRoll
import com.mica.music.ui.components.rememberLyricLineColorSpec
import com.mica.music.ui.components.rememberLyricUniformStyle
import com.mica.music.ui.components.rememberPlayerLyricLineStepPx
import com.mica.music.ui.theme.PlayerContentColors

/**
 * A landscape-only lyric strip: one visible row without the multi-line lyrics
 * viewport, edge fade, or full-height clipping container.
 */
@Composable
internal fun LandscapeSingleLineLyrics(
    renderState: LyricsRenderState,
    isPlaying: Boolean,
    colors: PlayerContentColors,
    bilingualDisplayMode: LyricsBilingualDisplayMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lyrics = renderState.lyrics
    val displayIndex = renderState.activeLineIndex
        .coerceIn(0, lyrics.lastIndex.coerceAtLeast(0))
    val textStyle = rememberLyricUniformStyle().copy(
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )
    val lineStepPx = rememberPlayerLyricLineStepPx(textStyle)
    val colorSpec = rememberLyricLineColorSpec()
    // This surface has room for one physical row. Keep explicit translation-only
    // selection, but show the original row when the global mode requests both.
    val singleRowMode = when (bilingualDisplayMode) {
        LyricsBilingualDisplayMode.ALL -> LyricsBilingualDisplayMode.ORIGINAL
        else -> bilingualDisplayMode
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (!lyrics.hasDisplayableLyrics()) {
            Text(
                text = EmptyLyricsText,
                style = textStyle,
                color = colors.tertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PlayerLyricsIndexRoll(
                targetIndex = displayIndex,
                lineStepPx = lineStepPx,
                modifier = Modifier.fillMaxWidth(),
            ) { index ->
                val line = lyrics.getOrNull(index)
                LyricLineBlock(
                    text = line?.text ?: lyrics.first().text,
                    isCurrent = line != null,
                    colors = colors,
                    textStyle = textStyle,
                    colorSpec = colorSpec,
                    maxLines = 1,
                    lyricLine = line,
                    nextLineTimeMs = lyrics.getOrNull(index + 1)?.timeMs,
                    positionMs = renderState.positionMs,
                    positionRevision = renderState.positionRevision,
                    isPlaying = isPlaying,
                    bilingualDisplayMode = singleRowMode,
                )
            }
        }
    }
}
