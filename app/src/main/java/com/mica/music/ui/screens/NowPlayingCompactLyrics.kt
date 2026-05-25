package com.mica.music.ui.screens

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsSync
import com.mica.music.ui.components.LyricLineBlock
import com.mica.music.ui.components.LyricsAreaEdgeFade
import com.mica.music.ui.components.PlayerLyricsIndexRoll
import com.mica.music.ui.components.rememberLyricLineColorSpec
import com.mica.music.ui.components.rememberPlayerLyricLineStepPx
import com.mica.music.ui.components.rememberPlayerPanelLyricStyle
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.PlayerContentColors

internal const val EmptyLyricsText = "暂无歌词"

internal fun List<LyricLine>.hasDisplayableLyrics(): Boolean =
    any { it.text.isNotBlank() }

@Composable
internal fun LyricsSection(
    lyrics: List<LyricLine>,
    positionMs: Int,
    colors: PlayerContentColors,
    lineSlots: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = LyricsSync.indexForPosition(lyrics, positionMs)
    val compact = lineSlots <= 1
    val textStyle = rememberPlayerPanelLyricStyle()
    val colorSpec = rememberLyricLineColorSpec()
    val lineStepPx = rememberPlayerLyricLineStepPx(textStyle)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LyricsAreaEdgeFade(
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !lyrics.hasDisplayableLyrics() -> EmptyCompactLyrics(colors, textStyle)
                    else -> CompactLyricsRows(
                        lyrics = lyrics,
                        targetIndex = index,
                        compact = compact,
                        colors = colors,
                        textStyle = textStyle,
                        colorSpec = colorSpec,
                        lineStepPx = lineStepPx,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCompactLyrics(
    colors: PlayerContentColors,
    textStyle: TextStyle,
) {
    Text(
        text = EmptyLyricsText,
        style = textStyle,
        color = colors.tertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg),
    )
}

@Composable
private fun CompactLyricsRows(
    lyrics: List<LyricLine>,
    targetIndex: Int,
    compact: Boolean,
    colors: PlayerContentColors,
    textStyle: TextStyle,
    colorSpec: AnimationSpec<Color>,
    lineStepPx: Float,
) {
    PlayerLyricsIndexRoll(
        targetIndex = targetIndex,
        lineStepPx = lineStepPx,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg),
    ) { displayIndex ->
        when {
            compact -> CompactSingleLyricLine(
                lyrics = lyrics,
                displayIndex = displayIndex,
                colors = colors,
                textStyle = textStyle,
                colorSpec = colorSpec,
            )
            displayIndex < 0 -> Text(
                text = lyrics.firstOrNull()?.text ?: EmptyLyricsText,
                style = textStyle,
                color = colors.tertiary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> CompactThreeLyricLines(
                lyrics = lyrics,
                displayIndex = displayIndex,
                colors = colors,
                textStyle = textStyle,
                colorSpec = colorSpec,
            )
        }
    }
}

@Composable
private fun CompactSingleLyricLine(
    lyrics: List<LyricLine>,
    displayIndex: Int,
    colors: PlayerContentColors,
    textStyle: TextStyle,
    colorSpec: AnimationSpec<Color>,
) {
    val lineText = when {
        displayIndex in lyrics.indices -> lyrics[displayIndex].text
        else -> lyrics.firstOrNull()?.text ?: EmptyLyricsText
    }
    LyricLineBlock(
        text = lineText,
        isCurrent = displayIndex in lyrics.indices,
        colors = colors,
        textStyle = textStyle,
        colorSpec = colorSpec,
    )
}

@Composable
private fun CompactThreeLyricLines(
    lyrics: List<LyricLine>,
    displayIndex: Int,
    colors: PlayerContentColors,
    textStyle: TextStyle,
    colorSpec: AnimationSpec<Color>,
) {
    LyricLineBlock(
        text = lyrics.getOrNull(displayIndex - 1)?.text,
        isCurrent = false,
        colors = colors,
        textStyle = textStyle,
        colorSpec = colorSpec,
    )
    LyricLineBlock(
        text = lyrics[displayIndex].text,
        isCurrent = true,
        colors = colors,
        textStyle = textStyle,
        colorSpec = colorSpec,
    )
    LyricLineBlock(
        text = lyrics.getOrNull(displayIndex + 1)?.text,
        isCurrent = false,
        colors = colors,
        textStyle = textStyle,
        colorSpec = colorSpec,
    )
}
