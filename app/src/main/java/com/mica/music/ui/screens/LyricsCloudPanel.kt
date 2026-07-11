package com.mica.music.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsRenderState
import com.mica.music.data.LyricsSync
import com.mica.music.ui.components.rememberLyricUniformStyle
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.theme.LocalLyricSplitEnabled
import com.mica.music.ui.theme.PlayerContentColors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.delay

internal data class LyricsCloudSize(val width: Float, val height: Float)
internal data class LyricsCloudNode(val x: Float, val y: Float, val width: Float, val height: Float)

@Composable
internal fun LyricsCloudPanel(
    renderState: LyricsRenderState,
    isPlaying: Boolean,
    isVisible: Boolean,
    colors: PlayerContentColors,
    onLineClick: (Int) -> Unit,
    bilingualDisplayMode: LyricsBilingualDisplayMode,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val motionEnabled = rememberMicaMotionEnabled()
    val splitEnabled = LocalLyricSplitEnabled.current
    val textMeasurer = rememberTextMeasurer()
    val uniformStyle = rememberLyricUniformStyle()
    val lyrics = renderState.lyrics
    val seed = remember(renderState.document) {
        renderState.document.lines.fold(17) { value, line -> value * 31 + line.id.hashCode() }
    }
    val fontSizes = remember(seed, lyrics.size) {
        val random = Random(seed xor 0x51A7)
        List(lyrics.size) { 16 + random.nextInt(15) }
    }
    val lineStyles = remember(fontSizes, uniformStyle) {
        fontSizes.map { uniformStyle.withCloudFontSize(it) }
    }
    val translationStyles = remember(fontSizes, uniformStyle) {
        fontSizes.map { uniformStyle.withCloudFontSize((it - 3).coerceAtLeast(14)) }
    }
    val displayRows = remember(lyrics, splitEnabled, bilingualDisplayMode) {
        lyrics.map { line ->
            LyricDisplayRows.rowsForBilingualDisplayMode(
                text = line.text,
                enabled = splitEnabled,
                mode = bilingualDisplayMode,
            )
        }
    }
    val unit = min(widthPx, heightPx).coerceAtLeast(1) * 0.34f
    val sizes = remember(displayRows, lineStyles, translationStyles, unit) {
        displayRows.mapIndexed { index, rows ->
            val measured = rows.map { row ->
                val style = if (row.splitIndex > 0) translationStyles[index] else lineStyles[index]
                val whole = textMeasurer.measure(
                    text = row.text,
                    style = style,
                    softWrap = false,
                ).size
                val characterWidth = row.text.sumOf { character ->
                    textMeasurer.measure(
                        text = character.toString(),
                        style = style.copy(fontWeight = FontWeight.Normal),
                        softWrap = false,
                    ).size.width
                }
                LyricsCloudMeasuredRow(
                    width = maxOf(whole.width, characterWidth) + 4,
                    height = whole.height,
                )
            }
            LyricsCloudSize(
                width = (measured.maxOfOrNull { it.width } ?: 1) / unit,
                height = measured.sumOf { it.height }.coerceAtLeast(1) / unit,
            )
        }
    }
    val nodes = remember(sizes, seed) { buildLyricsCloudLayout(sizes, seed) }
    val currentIndex = renderState.activeLineIndex.coerceIn(0, nodes.lastIndex.coerceAtLeast(0))
    val currentNode = nodes.getOrNull(currentIndex) ?: LyricsCloudNode(0f, 0f, 0f, 0f)
    val reveal = remember(seed) { Animatable(0f) }
    LaunchedEffect(seed, motionEnabled, isVisible) {
        if (!motionEnabled) {
            reveal.snapTo(if (isVisible) 1f else 0f)
        } else if (isVisible) {
            if (reveal.value == 0f) delay(240)
            reveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 360, easing = MicaMotion.Easing),
            )
        } else {
            reveal.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 360, easing = MicaMotion.Easing),
            )
        }
    }
    val currentLine = lyrics.getOrNull(currentIndex)
    val framePositionMs = rememberCloudFramePosition(
        anchorPositionMs = renderState.positionMs,
        isPlaying = isPlaying,
    )
    val nextLineTimeMs = lyrics.getOrNull(currentIndex + 1)?.timeMs
    val cueOffset = if (currentLine != null) {
        lyricsCloudPanOffset(
            lineStartMs = currentLine.timeMs,
            lineEndMs = currentLine.endTimeMs ?: nextLineTimeMs,
            positionMs = framePositionMs + LyricsSync.LEAD_MS,
            lineWidthPx = currentNode.width * unit,
            viewportWidthPx = widthPx.toFloat(),
            unitPx = unit,
        )
    } else {
        0f
    }
    val (cameraBaseX, cameraY) = key(widthPx, heightPx) {
        val animatedX by animateFloatAsState(
            targetValue = currentNode.x,
            animationSpec = tween(if (motionEnabled) MicaMotion.DurationLongMs else 0, easing = MicaMotion.Easing),
            label = "lyricsCloudCameraBaseX",
        )
        val animatedY by animateFloatAsState(
            targetValue = currentNode.y,
            animationSpec = tween(if (motionEnabled) MicaMotion.DurationLongMs else 0, easing = MicaMotion.Easing),
            label = "lyricsCloudCameraY",
        )
        animatedX to animatedY
    }
    val cameraX = cameraBaseX + cueOffset

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                widthPx = it.width
                heightPx = it.height
            },
    ) {
        nodes.forEachIndexed { index, node ->
            val screenX = (node.x - cameraX) * unit
            val screenY = (node.y - cameraY) * unit
            val nearby = abs(screenX) <= widthPx + node.width * unit / 2f &&
                abs(screenY) <= heightPx + node.height * unit / 2f
            if (!nearby) return@forEachIndexed
            val line = lyrics[index]
            val revealProgress = lyricsCloudRevealProgress(
                globalProgress = reveal.value,
                distanceFromCurrent = hypot(node.x - currentNode.x, node.y - currentNode.y),
                isCurrent = index == currentIndex,
            )
            CloudLyricLine(
                rows = displayRows[index],
                line = line,
                isCurrent = index == currentIndex,
                colors = colors,
                textStyle = lineStyles[index],
                translationTextStyle = translationStyles[index],
                nextLineTimeMs = lyrics.getOrNull(index + 1)?.timeMs,
                positionMs = framePositionMs,
                modifier = Modifier
                    .align(Alignment.Center)
                    .requiredWidth(with(density) { (node.width * unit).toDp() })
                    .offset {
                        IntOffset(screenX.roundToInt(), screenY.roundToInt())
                    }
                    .graphicsLayer {
                        val scale = if (index == currentIndex) 1.08f else 1f
                        scaleX = scale
                        scaleY = scale
                        alpha = revealProgress
                    }
                    .clickable { onLineClick(line.timeMs) },
            )
        }
    }
}

internal fun buildLyricsCloudLayout(
    sizes: List<LyricsCloudSize>,
    seed: Int,
): List<LyricsCloudNode> {
    val random = Random(seed)
    val result = MutableList<LyricsCloudNode?>(sizes.size) { null }
    val placed = mutableListOf<LyricsCloudNode>()
    var halfWidth = 2.6f
    var halfHeight = 3.8f
    val gap = 0.14f
    val placementOrder = if (sizes.isEmpty()) emptyList() else listOf(sizes.lastIndex) + (0 until sizes.lastIndex)
    placementOrder.forEach { index ->
        val size = sizes[index]
        if (index == sizes.lastIndex) {
            val center = LyricsCloudNode(0f, 0f, size.width, size.height)
            result[index] = center
            placed += center
            return@forEach
        }
        halfWidth = maxOf(halfWidth, size.width / 2f + gap)
        halfHeight = maxOf(halfHeight, size.height / 2f + gap)
        var accepted: LyricsCloudNode? = null
        var attempts = 0
        while (accepted == null) {
            if (attempts > 0 && attempts % 40 == 0) {
                halfWidth *= 1.1f
                halfHeight *= 1.1f
            }
            val xRange = (halfWidth - size.width / 2f).coerceAtLeast(0f)
            val yRange = (halfHeight - size.height / 2f).coerceAtLeast(0f)
            val candidate = LyricsCloudNode(
                x = (random.nextFloat() * 2f - 1f) * xRange,
                y = (random.nextFloat() * 2f - 1f) * yRange,
                width = size.width,
                height = size.height,
            )
            if (placed.none { other -> candidate.overlaps(other, gap) }) accepted = candidate
            attempts++
        }
        placed += accepted
        result[index] = accepted
    }
    return result.map { requireNotNull(it) }
}

internal fun LyricsCloudNode.overlaps(other: LyricsCloudNode, gap: Float): Boolean =
    abs(x - other.x) * 2f < width + other.width + gap * 2f &&
        abs(y - other.y) * 2f < height + other.height + gap * 2f

private fun TextStyle.withCloudFontSize(fontSizeSp: Int): TextStyle = copy(
    fontSize = fontSizeSp.sp,
    lineHeight = (fontSizeSp * 1.45f).sp,
)

internal data class CloudCharacterState(val activeIndex: Int, val progress: Float)
private data class LyricsCloudMeasuredRow(val width: Int, val height: Int)

internal fun lyricsCloudRevealProgress(
    globalProgress: Float,
    distanceFromCurrent: Float,
    isCurrent: Boolean,
): Float {
    val delayFraction = if (isCurrent) 0f else {
        (distanceFromCurrent * 0.06f).coerceIn(0.08f, 0.45f)
    }
    return ((globalProgress - delayFraction) / (1f - delayFraction)).coerceIn(0f, 1f)
}

internal fun lyricsCloudPanOffset(
    lineStartMs: Int,
    lineEndMs: Int?,
    positionMs: Int,
    lineWidthPx: Float,
    viewportWidthPx: Float,
    unitPx: Float,
): Float {
    if (lineEndMs == null || lineEndMs <= lineStartMs || unitPx <= 0f) return 0f
    val overflowPx = lineWidthPx - viewportWidthPx * 0.72f
    if (overflowPx <= 0f) return 0f
    val progress = ((positionMs - lineStartMs).toFloat() / (lineEndMs - lineStartMs)).coerceIn(0f, 1f)
    val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
    val travelWorld = overflowPx / unitPx * 1.08f
    return (eased - 0.5f) * travelWorld
}

internal fun cloudCharacterState(
    line: LyricLine,
    positionMs: Int,
    nextLineTimeMs: Int?,
): CloudCharacterState? {
    if (line.cues.isEmpty()) return null
    val timeMs = positionMs + LyricsSync.LEAD_MS
    val cueIndex = line.cues.indexOfLast { it.timeMs <= timeMs }
    if (cueIndex < 0) return CloudCharacterState(-1, 0f)
    val cue = line.cues[cueIndex]
    val cueEndMs = line.cues.getOrNull(cueIndex + 1)?.timeMs
        ?: line.endTimeMs
        ?: nextLineTimeMs
        ?: (cue.timeMs + 1_000)
    val cueProgress = if (cueEndMs <= cue.timeMs) 1f else {
        ((timeMs - cue.timeMs).toFloat() / (cueEndMs - cue.timeMs)).coerceIn(0f, 1f)
    }
    var searchFrom = 0
    var cueStart = -1
    line.cues.take(cueIndex + 1).forEach { part ->
        val raw = part.text
        val found = line.text.indexOf(raw, startIndex = searchFrom).takeIf { it >= 0 }
            ?: line.text.indexOf(raw.trim(), startIndex = searchFrom)
        if (found >= 0) {
            cueStart = found
            searchFrom = found + raw.trim().length
        }
    }
    if (cueStart < 0) return null
    val characterCount = cue.text.trim().length.coerceAtLeast(1)
    val scaled = cueProgress * characterCount
    val characterOffset = scaled.toInt().coerceIn(0, characterCount - 1)
    return CloudCharacterState(
        activeIndex = cueStart + characterOffset,
        progress = (scaled - characterOffset).coerceIn(0f, 1f),
    )
}

@Composable
private fun CloudLyricLine(
    rows: List<LyricDisplayRows.DisplayRow>,
    line: LyricLine,
    isCurrent: Boolean,
    colors: PlayerContentColors,
    textStyle: TextStyle,
    translationTextStyle: TextStyle,
    nextLineTimeMs: Int?,
    positionMs: Int,
    modifier: Modifier = Modifier,
) {
    val characterState = if (isCurrent) {
        cloudCharacterState(line, positionMs, nextLineTimeMs)
    } else {
        null
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            val style = if (row.splitIndex > 0) translationTextStyle else textStyle
            if (characterState != null && characterState.activeIndex in row.start until row.endExclusive) {
                Row {
                    row.text.forEachIndexed { localIndex, character ->
                        val sourceIndex = row.start + localIndex
                        val isActiveCharacter = sourceIndex == characterState.activeIndex
                        val completed = sourceIndex < characterState.activeIndex
                        Text(
                            text = character.toString(),
                            style = style.copy(fontWeight = FontWeight.Normal),
                            color = when {
                                completed -> colors.primary
                                isActiveCharacter -> lerp(
                                    colors.tertiary,
                                    colors.primary,
                                    characterState.progress,
                                )
                                else -> colors.tertiary
                            },
                        )
                    }
                }
            } else {
                Text(
                    text = row.text,
                    style = style.copy(fontWeight = FontWeight.Normal),
                    color = if (isCurrent) colors.primary else colors.tertiary,
                    textAlign = TextAlign.Center,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun rememberCloudFramePosition(
    anchorPositionMs: Int,
    isPlaying: Boolean,
): Int {
    var framePositionMs by remember { mutableIntStateOf(anchorPositionMs) }
    LaunchedEffect(anchorPositionMs, isPlaying) {
        framePositionMs = anchorPositionMs
        if (!isPlaying) return@LaunchedEffect
        val startNanos = withFrameNanos { it }
        while (true) {
            val frameNanos = withFrameNanos { it }
            framePositionMs = anchorPositionMs + ((frameNanos - startNanos) / 1_000_000L).toInt()
        }
    }
    return framePositionMs
}
