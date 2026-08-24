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
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsRenderState
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

internal fun safeLyricDisplayIndex(lyricsSize: Int, displayIndex: Int): Int? =
    if (lyricsSize <= 0) null else displayIndex.coerceIn(0, lyricsSize - 1)

@Composable
internal fun LyricsSection(
    renderState: LyricsRenderState,
    isPlaying: Boolean,
    colors: PlayerContentColors,
    lineSlots: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bilingualDisplayMode: LyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
    contentScale: Float = 1f,
) {
    val lyrics = renderState.lyrics
    val positionMs = renderState.positionMs
    val index = renderState.activeLineIndex
    val compact = lineSlots <= 1
    val textStyle = rememberPlayerPanelLyricStyle().let { style ->
        style.copy(
            fontSize = style.fontSize * contentScale,
            lineHeight = style.lineHeight * contentScale,
        )
    }
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
                        partsForIndex = { lineIndex ->
                            renderState.document.lines.getOrNull(lineIndex)?.parts
                        },
                        targetIndex = index,
                        compact = compact,
                        colors = colors,
                        textStyle = textStyle,
                        colorSpec = colorSpec,
                        lineStepPx = lineStepPx,
                        positionMs = positionMs,
                        positionRevision = renderState.positionRevision,
                        isPlaying = isPlaying,
                        bilingualDisplayMode = bilingualDisplayMode,
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
    partsForIndex: (Int) -> List<LyricTextPart>?,
    targetIndex: Int,
    compact: Boolean,
    colors: PlayerContentColors,
    textStyle: TextStyle,
    colorSpec: AnimationSpec<Color>,
    lineStepPx: Float,
    positionMs: Int,
    positionRevision: Long,
    isPlaying: Boolean,
    bilingualDisplayMode: LyricsBilingualDisplayMode,
) {
    val safeTargetIndex = targetIndex.coerceIn(0, lyrics.lastIndex.coerceAtLeast(0))
    PlayerLyricsIndexRoll(
        targetIndex = safeTargetIndex,
        lineStepPx = lineStepPx,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg),
    ) { displayIndex ->
        val safeDisplayIndex = displayIndex.coerceIn(0, lyrics.lastIndex.coerceAtLeast(0))
        when {
            compact -> CompactSingleLyricLine(
                lyrics = lyrics,
                partsForIndex = partsForIndex,
                displayIndex = safeDisplayIndex,
                colors = colors,
                textStyle = textStyle,
                colorSpec = colorSpec,
                positionMs = positionMs,
                positionRevision = positionRevision,
                isPlaying = isPlaying,
                bilingualDisplayMode = bilingualDisplayMode,
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
                partsForIndex = partsForIndex,
                displayIndex = safeDisplayIndex,
                colors = colors,
                textStyle = textStyle,
                colorSpec = colorSpec,
                positionMs = positionMs,
                positionRevision = positionRevision,
                isPlaying = isPlaying,
                bilingualDisplayMode = bilingualDisplayMode,
            )
        }
    }
}

@Composable
private fun CompactSingleLyricLine(
    lyrics: List<LyricLine>,
    partsForIndex: (Int) -> List<LyricTextPart>?,
    displayIndex: Int,
    colors: PlayerContentColors,
    textStyle: TextStyle,
    colorSpec: AnimationSpec<Color>,
    positionMs: Int,
    positionRevision: Long,
    isPlaying: Boolean,
    bilingualDisplayMode: LyricsBilingualDisplayMode,
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
        lyricLine = lyrics.getOrNull(displayIndex),
        nextLineTimeMs = lyrics.getOrNull(displayIndex + 1)?.timeMs,
        positionMs = positionMs,
        positionRevision = positionRevision,
        isPlaying = isPlaying,
        bilingualDisplayMode = bilingualDisplayMode,
        parts = partsForIndex(displayIndex),
    )
}

@Composable
private fun CompactThreeLyricLines(
    lyrics: List<LyricLine>,
    partsForIndex: (Int) -> List<LyricTextPart>?,
    displayIndex: Int,
    colors: PlayerContentColors,
    textStyle: TextStyle,
    colorSpec: AnimationSpec<Color>,
    positionMs: Int,
    positionRevision: Long,
    isPlaying: Boolean,
    bilingualDisplayMode: LyricsBilingualDisplayMode,
) {
    val safeIndex = safeLyricDisplayIndex(lyrics.size, displayIndex) ?: return
    LyricLineBlock(
        text = lyrics.getOrNull(safeIndex - 1)?.text,
        isCurrent = false,
        colors = colors,
        textStyle = textStyle,
        colorSpec = colorSpec,
        lyricLine = lyrics.getOrNull(safeIndex - 1),
        nextLineTimeMs = lyrics.getOrNull(safeIndex)?.timeMs,
        positionMs = positionMs,
        positionRevision = positionRevision,
        isPlaying = false,
        bilingualDisplayMode = bilingualDisplayMode,
        parts = partsForIndex(safeIndex - 1),
    )
    LyricLineBlock(
        text = lyrics[safeIndex].text,
        isCurrent = true,
        colors = colors,
        textStyle = textStyle,
        colorSpec = colorSpec,
        lyricLine = lyrics[safeIndex],
        nextLineTimeMs = lyrics.getOrNull(safeIndex + 1)?.timeMs,
        positionMs = positionMs,
        positionRevision = positionRevision,
        isPlaying = isPlaying,
        bilingualDisplayMode = bilingualDisplayMode,
        parts = partsForIndex(safeIndex),
    )
    LyricLineBlock(
        text = lyrics.getOrNull(safeIndex + 1)?.text,
        isCurrent = false,
        colors = colors,
        textStyle = textStyle,
        colorSpec = colorSpec,
        lyricLine = lyrics.getOrNull(safeIndex + 1),
        nextLineTimeMs = lyrics.getOrNull(safeIndex + 2)?.timeMs,
        positionMs = positionMs,
        positionRevision = positionRevision,
        isPlaying = false,
        bilingualDisplayMode = bilingualDisplayMode,
        parts = partsForIndex(safeIndex + 1),
    )
}
