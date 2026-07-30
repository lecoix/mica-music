package com.mica.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mica.music.R
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsRenderState
import com.mica.music.data.LyricsTimelinePhase
import com.mica.music.ui.components.rememberLyricUniformStyle
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.floor

/**
 * PROTOTYPE — disposable portrait "letter paper" lyrics surface.
 *
 * This intentionally keeps theme-specific layout and interaction in one file until the
 * page-writing direction, pagination density, and overview gesture have been accepted.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun LetterLyricsPrototype(
    renderState: LyricsRenderState,
    isPlaying: Boolean,
    bilingualDisplayMode: LyricsBilingualDisplayMode,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val motionEnabled = rememberMicaMotionEnabled()
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val lyricStyle = rememberLyricUniformStyle()
    var overviewVisible by remember(renderState.document) { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(false) }
    val shouldShowHint = remember { LetterPrototypeHintSession.claim() }
    val framePositionMs = rememberLetterFramePosition(renderState.positionMs, isPlaying)

    BackHandler(enabled = overviewVisible) {
        overviewVisible = false
    }

    LaunchedEffect(shouldShowHint) {
        if (!shouldShowHint) return@LaunchedEffect
        kotlinx.coroutines.delay(650)
        hintVisible = true
        kotlinx.coroutines.delay(2_200)
        hintVisible = false
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                contentDescription = if (overviewVisible) {
                    "信笺歌词总览"
                } else {
                    "信笺歌词，双指合拢查看已经写下的信纸"
                }
                customActions = listOf(
                    CustomAccessibilityAction(
                        label = if (overviewVisible) "返回当前信纸" else "查看信笺总览",
                    ) {
                        overviewVisible = !overviewVisible
                        true
                    },
                )
            }
            .pointerInput(overviewVisible) {
                detectTransformGestures { _, _, zoom, _ ->
                    when {
                        zoom < 0.88f -> overviewVisible = true
                        zoom > 1.12f -> overviewVisible = false
                    }
                }
            },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val metrics = remember(widthPx, heightPx, density.density) {
            LetterPageMetrics(
                widthPx = widthPx,
                heightPx = heightPx,
                horizontalPaddingPx = with(density) { 30.dp.toPx() },
                verticalPaddingPx = with(density) { 82.dp.toPx() },
                mainFontPx = with(density) { 20.sp.toPx() },
                translationFontPx = with(density) { 11.5.sp.toPx() },
                mainCharacterStepPx = with(density) { 30.dp.toPx() },
                translationCharacterStepPx = with(density) { 18.dp.toPx() },
                columnPitchPx = with(density) { 38.dp.toPx() },
                pageCapacityColumnPitchPx = with(density) { 52.dp.toPx() },
            )
        }
        val pages = remember(
            renderState.document,
            bilingualDisplayMode,
            metrics,
        ) {
            buildLetterPages(
                lines = renderState.document.lines,
                bilingualDisplayMode = bilingualDisplayMode,
                metrics = metrics,
            )
        }
        val activeLineIndex = letterActiveLineIndex(renderState)
        val activeLine = renderState.document.lines.getOrNull(activeLineIndex)
        val activeLineReveal = activeLine?.let { line ->
            letterRevealForLine(
                line = line,
                positionMs = framePositionMs,
                fallbackEndMs = renderState.document.lines
                    .getOrNull(activeLineIndex + 1)
                    ?.startMs,
            )
        } ?: LetterReveal.EMPTY
        val currentPageIndex = remember(pages, activeLineIndex, activeLineReveal) {
            letterPageIndex(
                pages = pages,
                activeLineIndex = activeLineIndex,
                activeLineProgress = activeLineReveal.progress,
            )
        }
        val visiblePageCount = (currentPageIndex + 1).coerceIn(1, pages.size.coerceAtLeast(1))

        if (overviewVisible) {
            LetterPagesOverview(
                pages = pages.take(visiblePageCount),
                metrics = metrics,
                activeLineIndex = activeLineIndex,
                framePositionMs = framePositionMs,
                textMeasurer = textMeasurer,
                lyricStyle = lyricStyle,
                onClose = { overviewVisible = false },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AnimatedContent(
                targetState = currentPageIndex,
                transitionSpec = {
                    val forward = targetState >= initialState
                    val enterOffset: (Int) -> Int = { width ->
                        if (forward) -width / 12 else width / 8
                    }
                    val exitOffset: (Int) -> Int = { width ->
                        if (forward) width / 5 else -width / 6
                    }
                    (
                        fadeIn(tween(if (motionEnabled) 420 else 0, easing = MicaMotion.Easing)) +
                            slideInHorizontally(
                                animationSpec = tween(
                                    if (motionEnabled) 460 else 0,
                                    easing = MicaMotion.Easing,
                                ),
                                initialOffsetX = enterOffset,
                            )
                        ) togetherWith (
                        fadeOut(tween(if (motionEnabled) 360 else 0, easing = MicaMotion.Easing)) +
                            slideOutHorizontally(
                                animationSpec = tween(
                                    if (motionEnabled) 420 else 0,
                                    easing = MicaMotion.Easing,
                                ),
                                targetOffsetX = exitOffset,
                            )
                        )
                },
                label = "letterPaperChange",
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                val page = pages.getOrNull(pageIndex) ?: LetterPage.EMPTY
                LetterPaperCanvas(
                    page = page,
                    metrics = metrics,
                    activeLineIndex = activeLineIndex,
                    framePositionMs = framePositionMs,
                    textMeasurer = textMeasurer,
                    lyricStyle = lyricStyle,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        AnimatedVisibility(
            visible = hintVisible && !overviewVisible,
            enter = fadeIn(tween(260)),
            exit = fadeOut(tween(520)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 34.dp),
        ) {
            Text(
                text = "双指合拢，查看已经写下的信",
                color = LETTER_INK.copy(alpha = 0.42f),
                style = lyricStyle.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.8.sp,
                ),
            )
        }
    }
}

@Composable
private fun LetterPagesOverview(
    pages: List<LetterPage>,
    metrics: LetterPageMetrics,
    activeLineIndex: Int,
    framePositionMs: Int,
    textMeasurer: TextMeasurer,
    lyricStyle: TextStyle,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(LETTER_OVERVIEW_BACKDROP)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.12f),
                        Color.Transparent,
                        LETTER_INK.copy(alpha = 0.04f),
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy((-26).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(pages, key = { index, _ -> index }) { index, page ->
                Box(
                    modifier = Modifier
                        .padding(
                            top = if (index == 0) 72.dp else 0.dp,
                            bottom = if (index == pages.lastIndex) 72.dp else 0.dp,
                        )
                        .fillMaxWidth(0.72f)
                        .aspectRatio(metrics.widthPx / metrics.heightPx)
                        .graphicsLayer {
                            shadowElevation = 10.dp.toPx()
                            shape = RectangleShape
                        }
                        .clickable(onClick = onClose),
                ) {
                    LetterPaperCanvas(
                        page = page,
                        metrics = metrics,
                        activeLineIndex = activeLineIndex,
                        framePositionMs = framePositionMs,
                        textMeasurer = textMeasurer,
                        lyricStyle = lyricStyle,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LetterPaperCanvas(
    page: LetterPage,
    metrics: LetterPageMetrics,
    activeLineIndex: Int,
    framePositionMs: Int,
    textMeasurer: TextMeasurer,
    lyricStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(LETTER_PAPER_BASE)) {
        Image(
            painter = painterResource(R.drawable.letter_paper_fine_warm_seal_v1),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasScale = (size.width / metrics.widthPx)
                .coerceAtMost(size.height / metrics.heightPx)
            scale(canvasScale, canvasScale, pivot = Offset.Zero) {
                page.columns.forEach { column ->
                    if (column.lineIndex > activeLineIndex) return@forEach
                    val reveal = when {
                        column.lineIndex < activeLineIndex -> LetterReveal.COMPLETE
                        else -> letterRevealForLine(
                            line = column.line,
                            positionMs = framePositionMs,
                            fallbackEndMs = column.fallbackEndMs,
                        )
                    }
                    drawLetterColumn(
                        column = column,
                        reveal = reveal,
                        metrics = metrics,
                        textMeasurer = textMeasurer,
                        lyricStyle = lyricStyle,
                        framePositionMs = framePositionMs,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawLetterColumn(
    column: LetterColumn,
    reveal: LetterReveal,
    metrics: LetterPageMetrics,
    textMeasurer: TextMeasurer,
    lyricStyle: TextStyle,
    framePositionMs: Int,
) {
    val x = metrics.widthPx - metrics.horizontalPaddingPx -
        (column.rightOffsetUnits + column.widthUnits / 2f) * metrics.columnPitchPx
    val fontPx = if (column.isTranslation) metrics.translationFontPx else metrics.mainFontPx
    val characterStep = if (column.isTranslation) {
        metrics.translationCharacterStepPx
    } else {
        metrics.mainCharacterStepPx
    }
    val style = lyricStyle.copy(
        fontSize = with(this) { fontPx.toSp() },
        fontWeight = FontWeight.Normal,
    )
    val inkAlpha = if (column.isTranslation) 0.64f else 0.92f
    val localScaledProgress = (
        reveal.progress * column.revealTotalCount - column.revealStartIndex
        ).coerceIn(0f, column.graphemes.size.toFloat())
    val visibleCount = floor(localScaledProgress + 0.9999f).toInt()
        .coerceAtMost(column.graphemes.size)

    if (column.rotateLatinPhrase) {
        val visible = column.graphemes.take(visibleCount).joinToString("")
        if (visible.isEmpty()) return
        val layout = textMeasurer.measure(
            text = visible,
            style = style.copy(color = LETTER_INK.copy(alpha = inkAlpha)),
            maxLines = 1,
            softWrap = false,
        )
        rotate(degrees = 90f, pivot = Offset(x, metrics.verticalPaddingPx)) {
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(x - layout.size.height / 2f, metrics.verticalPaddingPx),
            )
        }
        return
    }

    val lineStartMs = column.line.startMs
    val lineEndMs = column.line.endMs ?: column.fallbackEndMs ?: (lineStartMs + 4_000)
    val lineDurationMs = (lineEndMs - lineStartMs).coerceAtLeast(1)
    val revealTotalCount = column.revealTotalCount.coerceAtLeast(1)
    column.graphemes.forEachIndexed { index, grapheme ->
        val glyphGlobalIndex = column.revealStartIndex + index
        val glyphRevealMs = lineStartMs +
            (lineDurationMs.toLong() * glyphGlobalIndex / revealTotalCount).toInt()
        if (framePositionMs < glyphRevealMs) return@forEachIndexed
        val topLeft = Offset(
            x = x,
            y = metrics.verticalPaddingPx + index * characterStep,
        )
        val layout = textMeasurer.measure(
            text = grapheme,
            style = style.copy(
                color = LETTER_INK.copy(alpha = inkAlpha),
            ),
            maxLines = 1,
            softWrap = false,
        )
        val centeredTopLeft = topLeft.copy(x = x - layout.size.width / 2f)
        drawText(
            textLayoutResult = layout,
            topLeft = centeredTopLeft,
        )
    }
}

private fun buildLetterPages(
    lines: List<LyricLineNode>,
    bilingualDisplayMode: LyricsBilingualDisplayMode,
    metrics: LetterPageMetrics,
): List<LetterPage> {
    if (lines.isEmpty()) return listOf(LetterPage.EMPTY)
    val capacityUnits = (
        (metrics.widthPx - metrics.horizontalPaddingPx * 2f) /
            metrics.pageCapacityColumnPitchPx
        ).coerceAtLeast(1f)
    val mainCharactersPerColumn = (
        (metrics.heightPx - metrics.verticalPaddingPx * 2f) / metrics.mainCharacterStepPx
        ).toInt().coerceAtLeast(1)
    val translationCharactersPerColumn = (
        (metrics.heightPx - metrics.verticalPaddingPx * 2f) /
            metrics.translationCharacterStepPx
        ).toInt().coerceAtLeast(1)
    val pages = mutableListOf<MutableList<LetterColumn>>()
    var currentColumns = mutableListOf<LetterColumn>()
    var usedUnits = 0f

    fun finishPage() {
        if (currentColumns.isNotEmpty()) pages += currentColumns
        currentColumns = mutableListOf()
        usedUnits = 0f
    }

    lines.forEachIndexed { lineIndex, line ->
        val originals = line.parts
            .filter { it.role == LyricTextRole.ORIGINAL }
            .joinToString("") { it.text }
            .trim()
        val translations = line.parts
            .filter { it.role == LyricTextRole.TRANSLATION || it.role == LyricTextRole.EXTRA }
            .joinToString(" ") { it.text }
            .trim()
        val primaryText = when (bilingualDisplayMode) {
            LyricsBilingualDisplayMode.TRANSLATION -> translations.ifEmpty { originals }
            else -> originals.ifEmpty { translations }
        }
        val secondaryText = when (bilingualDisplayMode) {
            LyricsBilingualDisplayMode.ALL -> translations.takeIf { originals.isNotEmpty() }.orEmpty()
            else -> ""
        }
        val primarySegments = splitIntoVerticalSegments(primaryText, mainCharactersPerColumn)
        val secondarySegments = splitIntoVerticalSegments(
            secondaryText,
            translationCharactersPerColumn,
        )
        val primaryTotal = primaryText.graphemes().size.coerceAtLeast(1)
        val secondaryTotal = secondaryText.graphemes().size.coerceAtLeast(1)
        var primaryStart = 0
        var secondaryStart = 0
        val columnSpecs = buildList {
            primarySegments.forEach { segment ->
                add(
                    LetterColumnSpec(
                        text = segment,
                        isTranslation = false,
                        widthUnits = MAIN_COLUMN_UNITS,
                        revealStartIndex = primaryStart,
                        revealTotalCount = primaryTotal,
                    ),
                )
                primaryStart += segment.graphemes().size
            }
            secondarySegments.forEach { segment ->
                add(
                    LetterColumnSpec(
                        text = segment,
                        isTranslation = true,
                        widthUnits = TRANSLATION_COLUMN_UNITS,
                        revealStartIndex = secondaryStart,
                        revealTotalCount = secondaryTotal,
                    ),
                )
                secondaryStart += segment.graphemes().size
            }
        }
        val groupUnits = columnSpecs.sumOf { it.widthUnits.toDouble() }.toFloat() +
            GROUP_GAP_UNITS
        val groupFitsOnePage = groupUnits <= capacityUnits

        if (
            currentColumns.isNotEmpty() &&
            (usedUnits + groupUnits > capacityUnits || !groupFitsOnePage)
        ) {
            finishPage()
        }

        fun appendColumn(spec: LetterColumnSpec) {
            val text = spec.text
            val graphemes = text.graphemes()
            currentColumns += LetterColumn(
                lineIndex = lineIndex,
                line = line,
                text = text,
                graphemes = graphemes,
                isTranslation = spec.isTranslation,
                rotateLatinPhrase = text.isRotatedLatinPhrase(),
                rightOffsetUnits = usedUnits,
                widthUnits = spec.widthUnits,
                fallbackEndMs = lines.getOrNull(lineIndex + 1)?.startMs,
                revealStartIndex = spec.revealStartIndex,
                revealTotalCount = spec.revealTotalCount,
            )
            usedUnits += spec.widthUnits
        }

        columnSpecs.forEach { spec ->
            if (currentColumns.isNotEmpty() && usedUnits + spec.widthUnits > capacityUnits) {
                finishPage()
            }
            appendColumn(spec)
        }
        if (usedUnits + GROUP_GAP_UNITS <= capacityUnits) {
            usedUnits += GROUP_GAP_UNITS
        } else {
            finishPage()
        }

        val explicitEnd = line.endMs
        val nextStart = lines.getOrNull(lineIndex + 1)?.startMs
        if (
            explicitEnd != null &&
            nextStart != null &&
            nextStart - explicitEnd >= LETTER_INTERLUDE_MIN_MS
        ) {
            if (usedUnits + INTERLUDE_BLANK_UNITS <= capacityUnits) {
                usedUnits += INTERLUDE_BLANK_UNITS
            } else {
                finishPage()
            }
        }

        if (usedUnits >= capacityUnits - 0.25f) finishPage()
    }
    finishPage()
    return pages.map { columns ->
        LetterPage(
            columns = columns,
            firstLineIndex = columns.minOf { it.lineIndex },
            lastLineIndex = columns.maxOf { it.lineIndex },
        )
    }.ifEmpty { listOf(LetterPage.EMPTY) }
}

private fun splitIntoVerticalSegments(text: String, maxCharacters: Int): List<String> {
    if (text.isBlank()) return emptyList()
    val graphemes = text.graphemes()
    return graphemes.chunked(maxCharacters).map { it.joinToString("") }
}

private fun letterActiveLineIndex(renderState: LyricsRenderState): Int = when (
    val phase = renderState.timeline.phase
) {
    is LyricsTimelinePhase.Line -> phase.index
    is LyricsTimelinePhase.Gap -> phase.previousIndex
    LyricsTimelinePhase.BeforeFirstLine -> -1
    LyricsTimelinePhase.AfterLastLine -> renderState.document.lines.lastIndex
}

private fun letterRevealForLine(
    line: LyricLineNode,
    positionMs: Int,
    fallbackEndMs: Int?,
): LetterReveal {
    val endMs = line.endMs ?: fallbackEndMs ?: (line.startMs + 4_000)
    if (positionMs <= line.startMs) return LetterReveal.EMPTY
    if (positionMs >= endMs || endMs <= line.startMs) return LetterReveal.COMPLETE
    val progress = ((positionMs - line.startMs).toFloat() / (endMs - line.startMs))
        .coerceIn(0f, 1f)
    return LetterReveal(progress)
}

private fun letterPageIndex(
    pages: List<LetterPage>,
    activeLineIndex: Int,
    activeLineProgress: Float,
): Int {
    if (pages.isEmpty() || activeLineIndex < 0) return 0
    var selected = pages.indexOfFirst { activeLineIndex in it.firstLineIndex..it.lastLineIndex }
        .coerceAtLeast(0)
    pages.forEachIndexed { pageIndex, page ->
        val firstPrimaryColumn = page.columns
            .filter { it.lineIndex == activeLineIndex && !it.isTranslation }
            .minByOrNull { it.revealStartIndex }
            ?: return@forEachIndexed
        val startProgress = firstPrimaryColumn.revealStartIndex.toFloat() /
            firstPrimaryColumn.revealTotalCount.coerceAtLeast(1)
        if (activeLineProgress + 0.0001f >= startProgress) selected = pageIndex
    }
    return selected.coerceIn(0, pages.lastIndex)
}

@Composable
private fun rememberLetterFramePosition(anchorPositionMs: Int, isPlaying: Boolean): Int {
    var framePositionMs by remember { mutableIntStateOf(anchorPositionMs) }
    LaunchedEffect(anchorPositionMs, isPlaying) {
        framePositionMs = anchorPositionMs
        if (!isPlaying) return@LaunchedEffect
        val startNanos = withFrameNanos { it }
        while (true) {
            val frameNanos = withFrameNanos { it }
            framePositionMs = anchorPositionMs +
                ((frameNanos - startNanos) / 1_000_000L).toInt()
        }
    }
    return framePositionMs
}

private fun String.graphemes(): List<String> {
    if (isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(this)
    val result = ArrayList<String>(length)
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result += substring(start, end)
        start = end
        end = iterator.next()
    }
    return result
}

private fun String.isRotatedLatinPhrase(): Boolean {
    val visible = filterNot(Char::isWhitespace)
    return visible.isNotEmpty() &&
        visible.any { it.isLetter() && it.code < 128 } &&
        visible.all { it.code < 128 }
}

private data class LetterPageMetrics(
    val widthPx: Float,
    val heightPx: Float,
    val horizontalPaddingPx: Float,
    val verticalPaddingPx: Float,
    val mainFontPx: Float,
    val translationFontPx: Float,
    val mainCharacterStepPx: Float,
    val translationCharacterStepPx: Float,
    val columnPitchPx: Float,
    val pageCapacityColumnPitchPx: Float,
)

private data class LetterColumn(
    val lineIndex: Int,
    val line: LyricLineNode,
    val text: String,
    val graphemes: List<String>,
    val isTranslation: Boolean,
    val rotateLatinPhrase: Boolean,
    val rightOffsetUnits: Float,
    val widthUnits: Float,
    val fallbackEndMs: Int?,
    val revealStartIndex: Int,
    val revealTotalCount: Int,
)

private data class LetterColumnSpec(
    val text: String,
    val isTranslation: Boolean,
    val widthUnits: Float,
    val revealStartIndex: Int,
    val revealTotalCount: Int,
)

private data class LetterPage(
    val columns: List<LetterColumn>,
    val firstLineIndex: Int,
    val lastLineIndex: Int,
) {
    companion object {
        val EMPTY = LetterPage(emptyList(), 0, 0)
    }
}

private data class LetterReveal(val progress: Float) {
    companion object {
        val EMPTY = LetterReveal(0f)
        val COMPLETE = LetterReveal(1f)
    }
}

private val LETTER_PAPER_BASE = Color(0xFFF2E5CF)
private val LETTER_OVERVIEW_BACKDROP = Color(0xFFE4D2B5)
private val LETTER_INK = Color(0xFF382C24)
private const val MAIN_COLUMN_UNITS = 1f
private const val TRANSLATION_COLUMN_UNITS = 0.58f
private const val GROUP_GAP_UNITS = 0.42f
private const val INTERLUDE_BLANK_UNITS = 1f
private const val LETTER_INTERLUDE_MIN_MS = 7_000

private object LetterPrototypeHintSession {
    private var claimed = false

    fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}
