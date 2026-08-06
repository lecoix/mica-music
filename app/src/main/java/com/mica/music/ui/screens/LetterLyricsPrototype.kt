package com.mica.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mica.music.R
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsRenderState
import com.mica.music.data.LyricsTimelinePhase
import com.mica.music.ui.components.rememberLyricUniformStyle
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.theme.LocalLyricReadingEnabled
import com.mica.music.util.LetterRevealDiagnostics
import coil.compose.AsyncImage
import java.io.File

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
    customSealImagePath: String?,
    sealSizeDp: Int,
    sealOpacityPercent: Int,
    sealRotationDegrees: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val motionEnabled = rememberMicaMotionEnabled()
    val readingEnabled = LocalLyricReadingEnabled.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val lyricStyle = rememberLyricUniformStyle()
    var overviewVisible by remember(renderState.document) { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(false) }
    val shouldShowHint = remember { LetterPrototypeHintSession.claim() }
    val framePositionMs = rememberLetterFramePositionMs(renderState.positionMs, isPlaying)
    val revealLogSessionKey = remember(renderState.document) {
        renderState.document.lines.joinToString("|") { it.id }
    }

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
        val metrics = remember(widthPx, heightPx, density.density, density.fontScale) {
            LetterPageMetrics(
                widthPx = widthPx,
                heightPx = heightPx,
                horizontalPaddingPx = with(density) { 30.dp.toPx() },
                verticalPaddingPx = with(density) { 82.dp.toPx() },
                mainFontPx = with(density) { 19.5.sp.toPx() },
                translationFontPx = with(density) { 11.5.sp.toPx() },
                mainCharacterStepPx = with(density) { 30.dp.toPx() },
                translationCharacterStepPx = with(density) { 18.dp.toPx() },
                columnPitchPx = with(density) { 30.dp.toPx() },
                pageCapacityColumnPitchPx = with(density) { 35.dp.toPx() },
            )
        }
        val letterPagesBuild = remember(
            renderState.document,
            bilingualDisplayMode,
            metrics,
            textMeasurer,
            lyricStyle,
        ) {
            val mainLetterStyle = lyricStyle.copy(
                fontSize = with(density) { metrics.mainFontPx.toSp() },
                fontWeight = FontWeight.Normal,
            )
            val translationLetterStyle = lyricStyle.copy(
                fontSize = with(density) { metrics.translationFontPx.toSp() },
                fontWeight = FontWeight.Normal,
            )
            val mainInkStyle = mainLetterStyle.copy(color = LETTER_INK)
            val translationInkStyle = translationLetterStyle.copy(color = LETTER_INK)
            buildLetterPages(
                lines = renderState.document.lines,
                bilingualDisplayMode = bilingualDisplayMode,
                metrics = metrics,
                measureLatinTextWidthPx = { text, isTranslation ->
                    textMeasurer.measure(
                        text = text,
                        style = if (isTranslation) {
                            translationLetterStyle
                        } else {
                            mainLetterStyle
                        },
                        maxLines = 1,
                        softWrap = false,
                    ).size.width.toFloat()
                },
                measureGraphemeLayout = { text, isTranslation ->
                    textMeasurer.measure(
                        text = text,
                        style = if (isTranslation) translationInkStyle else mainInkStyle,
                        maxLines = 1,
                        softWrap = false,
                    )
                },
                readingEnabled = readingEnabled,
            )
        }
        val pages = letterPagesBuild.pages
        val primaryRevealSchedules = letterPagesBuild.primaryRevealSchedules

        LaunchedEffect(revealLogSessionKey, primaryRevealSchedules) {
            LetterGlyphInkFloors.resetSession(revealLogSessionKey)
            LetterRevealDiagnostics.logSongSchedules(
                sessionKey = revealLogSessionKey,
                lines = renderState.document.lines,
                primaryRevealSchedules = primaryRevealSchedules,
            )
        }

        val activeLineIndex = letterActiveLineIndex(renderState)
        val activeLine = renderState.document.lines.getOrNull(activeLineIndex)
        val activeLineFallbackEndMs = renderState.document.lines
            .getOrNull(activeLineIndex + 1)
            ?.startMs
        val activeLineRevealProgress = activeLine?.let { line ->
            letterLineRevealProgress(
                primarySchedule = primaryRevealSchedules[activeLineIndex],
                framePositionMs = framePositionMs,
                lineStartMs = line.startMs,
                lineEndMs = line.endMs ?: activeLineFallbackEndMs ?: (line.startMs + 4_000),
            )
        } ?: 0f
        val currentPageIndex = remember(pages, activeLineIndex, activeLineRevealProgress) {
            letterPageIndex(
                pages = pages,
                activeLineIndex = activeLineIndex,
                activeLineProgress = activeLineRevealProgress,
            )
        }
        val visiblePageCount = (currentPageIndex + 1).coerceIn(1, pages.size.coerceAtLeast(1))
        val sealAppearance = remember(
            customSealImagePath,
            sealSizeDp,
            sealOpacityPercent,
            sealRotationDegrees,
        ) {
            LetterSealAppearance(
                customImagePath = customSealImagePath,
                sizeDp = sealSizeDp,
                opacityPercent = sealOpacityPercent,
                rotationDegrees = sealRotationDegrees,
            )
        }

        AnimatedContent(
            targetState = overviewVisible,
            transitionSpec = {
                if (targetState) {
                    (
                        fadeIn(
                            tween(
                                durationMillis = if (motionEnabled) 260 else 0,
                                delayMillis = if (motionEnabled) 70 else 0,
                                easing = MicaMotion.Easing,
                            ),
                        ) +
                            scaleIn(
                                animationSpec = tween(
                                    durationMillis = if (motionEnabled) 320 else 0,
                                    easing = MicaMotion.Easing,
                                ),
                                initialScale = if (motionEnabled) 0.92f else 1f,
                            )
                        ) togetherWith (
                        fadeOut(tween(if (motionEnabled) 180 else 0)) +
                            scaleOut(
                                animationSpec = tween(
                                    durationMillis = if (motionEnabled) 320 else 0,
                                    easing = MicaMotion.Easing,
                                ),
                                targetScale = if (motionEnabled) 0.72f else 1f,
                            )
                        )
                } else {
                    (
                        fadeIn(tween(if (motionEnabled) 220 else 0)) +
                            scaleIn(
                                animationSpec = tween(
                                    durationMillis = if (motionEnabled) 320 else 0,
                                    easing = MicaMotion.Easing,
                                ),
                                initialScale = if (motionEnabled) 0.72f else 1f,
                            )
                        ) togetherWith (
                        fadeOut(tween(if (motionEnabled) 180 else 0)) +
                            scaleOut(
                                animationSpec = tween(
                                    durationMillis = if (motionEnabled) 260 else 0,
                                    easing = MicaMotion.Easing,
                                ),
                                targetScale = if (motionEnabled) 0.92f else 1f,
                            )
                        )
                }
            },
            label = "letterOverviewChange",
            modifier = Modifier.fillMaxSize(),
        ) { showingOverview ->
            if (showingOverview) {
                LetterPagesOverview(
                    pages = pages.take(visiblePageCount),
                    metrics = metrics,
                    activeLineIndex = activeLineIndex,
                    framePositionMs = framePositionMs,
                    anchorPositionMs = renderState.positionMs,
                    inkSessionKey = revealLogSessionKey,
                    textMeasurer = textMeasurer,
                    lyricStyle = lyricStyle,
                    inkMotionEnabled = motionEnabled && isPlaying,
                    sealAppearance = sealAppearance,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LetterCurrentPageStage(
                    pages = pages,
                    currentPageIndex = currentPageIndex,
                    metrics = metrics,
                    activeLineIndex = activeLineIndex,
                    framePositionMs = framePositionMs,
                    anchorPositionMs = renderState.positionMs,
                    inkSessionKey = revealLogSessionKey,
                    textMeasurer = textMeasurer,
                    lyricStyle = lyricStyle,
                    inkMotionEnabled = motionEnabled && isPlaying,
                    motionEnabled = motionEnabled,
                    sealAppearance = sealAppearance,
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
private fun LetterCurrentPageStage(
    pages: List<LetterPage>,
    currentPageIndex: Int,
    metrics: LetterPageMetrics,
    activeLineIndex: Int,
    framePositionMs: Int,
    anchorPositionMs: Int,
    inkSessionKey: String,
    textMeasurer: TextMeasurer,
    lyricStyle: TextStyle,
    inkMotionEnabled: Boolean,
    motionEnabled: Boolean,
    sealAppearance: LetterSealAppearance,
    modifier: Modifier = Modifier,
) {
    var displayedPageIndex by remember(pages) { mutableIntStateOf(currentPageIndex) }
    var outgoingPageIndex by remember(pages) { mutableStateOf<Int?>(null) }
    var exitDirection by remember(pages) { mutableIntStateOf(1) }
    val exitOffsetPx = remember(pages) { Animatable(0f) }

    LaunchedEffect(currentPageIndex, motionEnabled, pages) {
        if (currentPageIndex == displayedPageIndex) return@LaunchedEffect

        val previousPageIndex = displayedPageIndex
        displayedPageIndex = currentPageIndex
        if (!motionEnabled) {
            outgoingPageIndex = null
            exitOffsetPx.snapTo(0f)
            return@LaunchedEffect
        }

        exitDirection = if (currentPageIndex > previousPageIndex) 1 else -1
        outgoingPageIndex = previousPageIndex
        exitOffsetPx.snapTo(0f)
        exitOffsetPx.animateTo(
            targetValue = metrics.widthPx * exitDirection,
            animationSpec = tween(
                durationMillis = 520,
                easing = MicaMotion.Easing,
            ),
        )
        outgoingPageIndex = null
    }

    Box(modifier = modifier) {
        LetterPaperCanvas(
            page = pages.getOrNull(displayedPageIndex) ?: LetterPage.EMPTY,
            metrics = metrics,
            activeLineIndex = activeLineIndex,
            framePositionMs = framePositionMs,
            anchorPositionMs = anchorPositionMs,
            inkSessionKey = inkSessionKey,
            textMeasurer = textMeasurer,
            lyricStyle = lyricStyle,
            inkMotionEnabled = inkMotionEnabled,
            sealAppearance = sealAppearance,
            modifier = Modifier.fillMaxSize(),
        )

        outgoingPageIndex?.let { pageIndex ->
            LetterPaperCanvas(
                page = pages.getOrNull(pageIndex) ?: LetterPage.EMPTY,
                metrics = metrics,
                activeLineIndex = activeLineIndex,
                framePositionMs = framePositionMs,
                anchorPositionMs = anchorPositionMs,
                inkSessionKey = inkSessionKey,
                textMeasurer = textMeasurer,
                lyricStyle = lyricStyle,
                inkMotionEnabled = false,
                sealAppearance = sealAppearance,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val progress = (
                            kotlin.math.abs(exitOffsetPx.value) /
                                metrics.widthPx.coerceAtLeast(1f)
                            ).coerceIn(0f, 1f)
                        val lift = 4f * progress * (1f - progress)
                        translationX = exitOffsetPx.value
                        translationY = -6.dp.toPx() * lift
                        rotationZ = exitDirection * 0.9f * progress
                        shadowElevation = 14.dp.toPx() * lift
                        shape = RectangleShape
                    },
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
    anchorPositionMs: Int,
    inkSessionKey: String,
    textMeasurer: TextMeasurer,
    lyricStyle: TextStyle,
    inkMotionEnabled: Boolean,
    sealAppearance: LetterSealAppearance,
    modifier: Modifier = Modifier,
) {
    var inspectedPageIndex by remember(pages.size) { mutableStateOf<Int?>(null) }
    BackHandler(enabled = inspectedPageIndex != null) {
        inspectedPageIndex = null
    }

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
            verticalArrangement = Arrangement.spacedBy((-72).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(pages, key = { index, _ -> index }) { index, page ->
                val isCurrentPage = index == pages.lastIndex
                Box(
                    modifier = Modifier
                        .padding(
                            top = if (index == 0) 72.dp else 0.dp,
                            bottom = if (index == pages.lastIndex) 72.dp else 0.dp,
                        )
                        .zIndex(index.toFloat())
                        .fillMaxWidth(if (isCurrentPage) 0.74f else 0.7f)
                        .aspectRatio(metrics.widthPx / metrics.heightPx)
                        .graphicsLayer {
                            rotationZ = LETTER_OVERVIEW_ROTATIONS[
                                index % LETTER_OVERVIEW_ROTATIONS.size
                            ]
                            translationX = when (index % 3) {
                                0 -> -5.dp.toPx()
                                1 -> 4.dp.toPx()
                                else -> 1.dp.toPx()
                            }
                            shadowElevation = (if (isCurrentPage) 16.dp else 8.dp).toPx()
                            shape = RectangleShape
                        }
                        .clickable { inspectedPageIndex = index },
                ) {
                    LetterPaperCanvas(
                        page = page,
                        metrics = metrics,
                        activeLineIndex = activeLineIndex,
                        framePositionMs = framePositionMs,
                        anchorPositionMs = anchorPositionMs,
                        inkSessionKey = inkSessionKey,
                        textMeasurer = textMeasurer,
                        lyricStyle = lyricStyle,
                        inkMotionEnabled = inkMotionEnabled,
                        sealAppearance = sealAppearance,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        text = "第 ${index + 1} 笺",
                        color = LETTER_INK.copy(alpha = if (isCurrentPage) 0.5f else 0.34f),
                        style = lyricStyle.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = 0.5.sp,
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 18.dp, bottom = 16.dp),
                    )
                }
            }
        }

        inspectedPageIndex?.let { pageIndex ->
            val page = pages.getOrNull(pageIndex) ?: return@let
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LETTER_INK.copy(alpha = 0.24f))
                    .clickable { inspectedPageIndex = null },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .aspectRatio(metrics.widthPx / metrics.heightPx)
                        .graphicsLayer {
                            shadowElevation = 22.dp.toPx()
                            shape = RectangleShape
                        }
                        .clickable { inspectedPageIndex = null },
                ) {
                    LetterPaperCanvas(
                        page = page,
                        metrics = metrics,
                        activeLineIndex = activeLineIndex,
                        framePositionMs = framePositionMs,
                        anchorPositionMs = anchorPositionMs,
                        inkSessionKey = inkSessionKey,
                        textMeasurer = textMeasurer,
                        lyricStyle = lyricStyle,
                        inkMotionEnabled = inkMotionEnabled && pageIndex == pages.lastIndex,
                        sealAppearance = sealAppearance,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        text = "第 ${pageIndex + 1} 笺",
                        color = LETTER_INK.copy(alpha = 0.5f),
                        style = lyricStyle.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = 0.6.sp,
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 22.dp, bottom = 18.dp),
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
    anchorPositionMs: Int,
    inkSessionKey: String,
    textMeasurer: TextMeasurer,
    lyricStyle: TextStyle,
    inkMotionEnabled: Boolean,
    sealAppearance: LetterSealAppearance,
    modifier: Modifier = Modifier,
) {
    val customSealFile = remember(sealAppearance.customImagePath) {
        sealAppearance.customImagePath?.let(::File)?.takeIf { it.isFile }
    }
    Box(modifier = modifier.background(LETTER_PAPER_BASE)) {
        Image(
            painter = painterResource(R.drawable.letter_paper_fine_warm_v2),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.5f },
        )
        val sealModifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 44.dp, bottom = 82.dp)
            .size(sealAppearance.sizeDp.dp)
            .graphicsLayer {
                alpha = (sealAppearance.opacityPercent / 100f).coerceIn(0f, 1f)
                rotationZ = sealAppearance.rotationDegrees.toFloat()
            }
        if (customSealFile != null) {
            AsyncImage(
                model = customSealFile,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = sealModifier,
            )
        } else {
            Image(
                painter = painterResource(R.drawable.letter_seal_default_v2),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = sealModifier,
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasScale = (size.width / metrics.widthPx)
                .coerceAtMost(size.height / metrics.heightPx)
            scale(canvasScale, canvasScale, pivot = Offset.Zero) {
                page.columns.forEach { column ->
                    if (column.lineIndex > activeLineIndex) return@forEach
                    drawLetterColumn(
                        column = column,
                        activeLineIndex = activeLineIndex,
                        metrics = metrics,
                        framePositionMs = framePositionMs,
                        anchorPositionMs = anchorPositionMs,
                        inkSessionKey = inkSessionKey,
                        inkMotionEnabled = inkMotionEnabled,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawLetterColumn(
    column: LetterColumn,
    activeLineIndex: Int,
    metrics: LetterPageMetrics,
    framePositionMs: Int,
    anchorPositionMs: Int,
    inkSessionKey: String,
    inkMotionEnabled: Boolean,
) {
    val x = metrics.widthPx - metrics.horizontalPaddingPx -
        (column.rightOffsetUnits + column.widthUnits / 2f) * metrics.columnPitchPx
    val characterStep = if (column.isTranslation) {
        metrics.translationCharacterStepPx
    } else {
        metrics.mainCharacterStepPx
    }
    val inkAlpha = if (column.isTranslation) 0.64f else 0.92f
    val syncTimeMs = letterSyncTimeMs(framePositionMs)
    val isCurrentLine = column.lineIndex == activeLineIndex
    fun inkRevealMs(index: Int): Int = LetterGlyphInkFloors.inkRevealMs(
        sessionKey = inkSessionKey,
        lineIndex = column.lineIndex,
        glyphIndex = column.revealStartIndex + index,
        scheduledMs = column.graphemeRevealMs[index],
        syncTimeMs = syncTimeMs,
        isCurrentLine = isCurrentLine,
    )
    val visibleCount = letterColumnVisibleCount(
        columnGraphemeCount = column.graphemes.size,
        graphemeRevealMs = column.graphemeRevealMs,
        lineIndex = column.lineIndex,
        activeLineIndex = activeLineIndex,
        framePositionMs = framePositionMs,
    )

    if (column.rotateLatinPhrase) {
        if (visibleCount == 0) return
        val visibleLayouts = column.graphemeLayouts.take(visibleCount)
        val progressByGlyph = visibleLayouts.indices.map { index ->
            letterInkSettleProgress(
                syncTimeMs = syncTimeMs,
                glyphRevealMs = inkRevealMs(index),
                motionEnabled = inkMotionEnabled,
            )
        }
        val firstMaskedIndex = when {
            !inkMotionEnabled -> visibleCount
            else -> progressByGlyph.indexOfFirst { it < 1f }.takeIf { it >= 0 } ?: visibleCount
        }
        rotate(degrees = 90f, pivot = Offset(x, metrics.verticalPaddingPx)) {
            val totalWidthPx = visibleLayouts.sumOf { it.size.width }
            val topLeft = letterRotatedLatinTopLeft(
                columnCenterX = x,
                verticalTopPx = metrics.verticalPaddingPx,
                layoutHeightPx = totalWidthPx,
            )
            var glyphLeft = topLeft.x
            val maskedGlyphs = ArrayList<LetterInkGlyphDraw>(visibleCount - firstMaskedIndex)
            for (index in 0 until visibleCount) {
                val layout = visibleLayouts[index]
                val glyphTopLeft = Offset(glyphLeft, topLeft.y)
                val progress = progressByGlyph[index]
                LetterRevealDiagnostics.onGlyphShown(
                    lineIndex = column.lineIndex,
                    glyphIndex = column.revealStartIndex + index,
                    char = column.graphemes[index],
                    scheduledMs = column.graphemeRevealMs[index],
                    frameMs = framePositionMs,
                    anchorMs = anchorPositionMs,
                    inkProgress = progress,
                    isTranslation = column.isTranslation,
                )
                if (index < firstMaskedIndex) {
                    drawText(
                        textLayoutResult = layout,
                        topLeft = glyphTopLeft,
                        alpha = inkAlpha,
                    )
                } else {
                    maskedGlyphs += LetterInkGlyphDraw(
                        layout = layout,
                        topLeft = glyphTopLeft,
                        targetAlpha = inkAlpha,
                        progress = progress,
                        maskSeed = column.graphemes[index].hashCode() xor
                            (column.lineIndex * 31 + column.revealStartIndex + index),
                    )
                }
                glyphLeft += layout.size.width
            }
            drawLetterInkGlyphs(maskedGlyphs)
        }
        return
    }

    val maskedGlyphs = ArrayList<LetterInkGlyphDraw>()
    column.graphemes.forEachIndexed { index, grapheme ->
        if (index >= visibleCount) return@forEachIndexed
        val glyphRevealMs = column.graphemeRevealMs[index]
        val inkGlyphRevealMs = inkRevealMs(index)
        val topLeft = Offset(
            x = x,
            y = metrics.verticalPaddingPx + index * characterStep,
        )
        val layout = column.graphemeLayouts[index]
        val centeredTopLeft = topLeft.copy(x = x - layout.size.width / 2f)
        val inkProgress = letterInkSettleProgress(
            syncTimeMs = syncTimeMs,
            glyphRevealMs = inkGlyphRevealMs,
            motionEnabled = inkMotionEnabled,
        )
        LetterRevealDiagnostics.onGlyphShown(
            lineIndex = column.lineIndex,
            glyphIndex = column.revealStartIndex + index,
            char = grapheme,
            scheduledMs = glyphRevealMs,
            frameMs = framePositionMs,
            anchorMs = anchorPositionMs,
            inkProgress = inkProgress,
            isTranslation = column.isTranslation,
        )
        if (!inkMotionEnabled || inkProgress >= 1f) {
            drawText(
                textLayoutResult = layout,
                topLeft = centeredTopLeft,
                alpha = inkAlpha,
            )
        } else {
            maskedGlyphs += LetterInkGlyphDraw(
                layout = layout,
                topLeft = centeredTopLeft,
                targetAlpha = inkAlpha,
                progress = inkProgress,
                maskSeed = grapheme.hashCode() xor
                    (column.lineIndex * 31 + column.revealStartIndex + index),
            )
        }
    }
    drawLetterInkGlyphs(maskedGlyphs)
}

internal fun letterRotatedLatinTopLeft(
    columnCenterX: Float,
    verticalTopPx: Float,
    layoutHeightPx: Int,
): Offset = Offset(
    x = columnCenterX,
    y = verticalTopPx - layoutHeightPx / 2f,
)

/**
 * PROTOTYPE — a per-glyph paper-absorption mask for the letter-paper theme.
 *
 * Several deterministic ink pools spread through each glyph and merge along short fibre
 * tendrils. All unfinished glyphs in a column share one pair of offscreen layers; each still
 * completes from its own reveal timestamp, independent of later glyphs.
 */
private fun DrawScope.drawLetterInkGlyphs(
    glyphs: List<LetterInkGlyphDraw>,
) {
    if (glyphs.isEmpty()) return

    val bounds = Rect(
        left = glyphs.minOf { it.topLeft.x },
        top = glyphs.minOf { it.topLeft.y },
        right = glyphs.maxOf { it.topLeft.x + it.layout.size.width },
        bottom = glyphs.maxOf { it.topLeft.y + it.layout.size.height },
    )

    drawContext.canvas.saveLayer(bounds, Paint())
    glyphs.forEach { glyph ->
        drawText(
            textLayoutResult = glyph.layout,
            topLeft = glyph.topLeft,
            alpha = glyph.targetAlpha,
        )
    }

    val maskLayerPaint = Paint().apply { blendMode = BlendMode.DstIn }
    drawContext.canvas.saveLayer(bounds, maskLayerPaint)
    glyphs.forEach { glyph ->
        val glyphBounds = glyph.bounds()
        clipRect(
            left = glyphBounds.left,
            top = glyphBounds.top,
            right = glyphBounds.right,
            bottom = glyphBounds.bottom,
        ) {
            drawLetterInkMask(glyph)
        }
    }
    drawContext.canvas.restore()
    drawContext.canvas.restore()
}

private fun DrawScope.drawLetterInkMask(
    glyph: LetterInkGlyphDraw,
) {
    val layout = glyph.layout
    val topLeft = glyph.topLeft
    val progress = glyph.progress
    val maskSeed = glyph.maskSeed
    val glyphWidth = layout.size.width.toFloat().coerceAtLeast(1f)
    val glyphHeight = layout.size.height.toFloat().coerceAtLeast(1f)
    val minDimension = minOf(glyphWidth, glyphHeight)
    val bounds = Rect(
        left = topLeft.x,
        top = topLeft.y,
        right = topLeft.x + glyphWidth,
        bottom = topLeft.y + glyphHeight,
    )
    val settled = progress * progress * (3f - 2f * progress)

    var randomState = maskSeed.takeIf { it != 0 } ?: 0x51A7C3

    fun nextUnit(): Float {
        randomState = randomState * 1_664_525 + 1_013_904_223
        return ((randomState ushr 8) and 0x00FFFFFF) / 16_777_215f
    }

    repeat(LETTER_INK_POOL_COUNT) {
        val center = Offset(
            x = bounds.left + glyphWidth * (0.12f + nextUnit() * 0.76f),
            y = bounds.top + glyphHeight * (0.1f + nextUnit() * 0.8f),
        )
        val radiusVariance = 0.82f + nextUnit() * 0.36f
        val radius = minDimension *
            (LETTER_INK_POOL_START_RADIUS + LETTER_INK_POOL_GROWTH * settled) *
            radiusVariance
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.White,
                    0.68f to Color.White,
                    1f to Color.Transparent,
                ),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }

    repeat(LETTER_INK_FIBRE_COUNT) {
        val start = Offset(
            x = bounds.left + glyphWidth * nextUnit(),
            y = bounds.top + glyphHeight * nextUnit(),
        )
        val direction = Offset(
            x = (nextUnit() - 0.5f) * minDimension * (0.22f + settled * 0.28f),
            y = (nextUnit() - 0.5f) * minDimension * (0.38f + settled * 0.32f),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.48f + settled * 0.34f),
            start = start,
            end = start + direction,
            strokeWidth = (0.55f + nextUnit() * 0.8f) * density,
        )
    }
}

private data class LetterInkGlyphDraw(
    val layout: TextLayoutResult,
    val topLeft: Offset,
    val targetAlpha: Float,
    val progress: Float,
    val maskSeed: Int,
) {
    fun bounds(): Rect = Rect(
        left = topLeft.x,
        top = topLeft.y,
        right = topLeft.x + layout.size.width,
        bottom = topLeft.y + layout.size.height,
    )
}

private data class LetterPagesBuild(
    val pages: List<LetterPage>,
    val primaryRevealSchedules: Map<Int, IntArray>,
)

private fun buildLetterPages(
    lines: List<LyricLineNode>,
    bilingualDisplayMode: LyricsBilingualDisplayMode,
    metrics: LetterPageMetrics,
    measureLatinTextWidthPx: (text: String, isTranslation: Boolean) -> Float,
    measureGraphemeLayout: (text: String, isTranslation: Boolean) -> TextLayoutResult,
    readingEnabled: Boolean = true,
): LetterPagesBuild {
    if (lines.isEmpty()) {
        return LetterPagesBuild(pages = listOf(LetterPage.EMPTY), primaryRevealSchedules = emptyMap())
    }
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
    val latinColumnWidthPx = (
        metrics.heightPx - metrics.verticalPaddingPx * 2f
        ).coerceAtLeast(1f)
    val pages = mutableListOf<MutableList<LetterColumn>>()
    val primaryRevealSchedules = mutableMapOf<Int, IntArray>()
    var currentColumns = mutableListOf<LetterColumn>()
    var usedUnits = 0f

    fun finishPage() {
        if (currentColumns.isNotEmpty()) pages += currentColumns
        currentColumns = mutableListOf()
        usedUnits = 0f
    }

    lines.forEachIndexed { lineIndex, line ->
        val readings = line.parts
            .filter { it.role == LyricTextRole.READING }
            .joinToString(" ") { it.text }
            .trim()
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
        val readingText = when {
            !readingEnabled -> ""
            bilingualDisplayMode == LyricsBilingualDisplayMode.TRANSLATION -> ""
            else -> readings
        }
        val secondaryText = when (bilingualDisplayMode) {
            LyricsBilingualDisplayMode.ALL -> translations.takeIf { originals.isNotEmpty() }.orEmpty()
            else -> ""
        }
        val readingSegments = splitIntoVerticalSegments(
            text = readingText,
            maxCharacters = mainCharactersPerColumn,
            maxLatinWidthPx = latinColumnWidthPx,
            measureLatinTextWidthPx = { measureLatinTextWidthPx(it, false) },
        )
        val primarySegments = splitIntoVerticalSegments(
            text = primaryText,
            maxCharacters = mainCharactersPerColumn,
            maxLatinWidthPx = latinColumnWidthPx,
            measureLatinTextWidthPx = { measureLatinTextWidthPx(it, false) },
        )
        val secondarySegments = splitIntoVerticalSegments(
            text = secondaryText,
            maxCharacters = translationCharactersPerColumn,
            maxLatinWidthPx = latinColumnWidthPx,
            measureLatinTextWidthPx = { measureLatinTextWidthPx(it, true) },
        )
        val primaryTotal = primaryText.letterGraphemes().size.coerceAtLeast(1)
        val readingTotal = readingText.letterGraphemes().size.coerceAtLeast(1)
        val secondaryTotal = secondaryText.letterGraphemes().size.coerceAtLeast(1)
        val lineFallbackEndMs = lines.getOrNull(lineIndex + 1)?.startMs
        val lineEndMs = line.endMs ?: lineFallbackEndMs ?: (line.startMs + 4_000)
        val primaryWordSchedule = if (
            bilingualDisplayMode != LyricsBilingualDisplayMode.TRANSLATION &&
            originals.isNotEmpty()
        ) {
            buildLetterGraphemeRevealMs(
                line = line,
                displayText = originals,
                tokens = letterOriginalWordTokens(line),
                fallbackEndMs = lineFallbackEndMs,
            )
        } else {
            null
        }
        primaryWordSchedule?.let { primaryRevealSchedules[lineIndex] = it }
        var readingStart = 0
        var primaryStart = 0
        var secondaryStart = 0
        val columnSpecs = buildList {
            readingSegments.forEach { segment ->
                add(
                    LetterColumnSpec(
                        text = segment,
                        isTranslation = true,
                        usePrimaryWordSchedule = false,
                        widthUnits = TRANSLATION_COLUMN_UNITS,
                        revealStartIndex = readingStart,
                        revealTotalCount = readingTotal,
                    ),
                )
                readingStart += segment.letterGraphemes().size
            }
            primarySegments.forEach { segment ->
                add(
                    LetterColumnSpec(
                        text = segment,
                        isTranslation = false,
                        usePrimaryWordSchedule = primaryWordSchedule != null,
                        widthUnits = MAIN_COLUMN_UNITS,
                        revealStartIndex = primaryStart,
                        revealTotalCount = primaryTotal,
                    ),
                )
                primaryStart += segment.letterGraphemes().size
            }
            secondarySegments.forEach { segment ->
                add(
                    LetterColumnSpec(
                        text = segment,
                        isTranslation = true,
                        usePrimaryWordSchedule = false,
                        widthUnits = TRANSLATION_COLUMN_UNITS,
                        revealStartIndex = secondaryStart,
                        revealTotalCount = secondaryTotal,
                    ),
                )
                secondaryStart += segment.letterGraphemes().size
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
            val graphemes = text.letterGraphemes()
            val graphemeRevealMs = if (spec.usePrimaryWordSchedule && primaryWordSchedule != null) {
                val sliceStart = spec.revealStartIndex
                val sliceEnd = (sliceStart + graphemes.size).coerceAtMost(primaryWordSchedule.size)
                primaryWordSchedule.copyOfRange(sliceStart, sliceEnd)
            } else {
                buildUniformLetterGraphemeRevealMs(
                    lineStartMs = line.startMs,
                    lineEndMs = lineEndMs,
                    graphemeCount = graphemes.size,
                    revealStartIndex = spec.revealStartIndex,
                    revealTotalCount = spec.revealTotalCount,
                )
            }
            currentColumns += LetterColumn(
                lineIndex = lineIndex,
                line = line,
                text = text,
                graphemes = graphemes,
                graphemeLayouts = graphemes.map { measureGraphemeLayout(it, spec.isTranslation) },
                graphemeRevealMs = graphemeRevealMs,
                isTranslation = spec.isTranslation,
                rotateLatinPhrase = text.isRotatedLatinPhrase(),
                rightOffsetUnits = usedUnits,
                widthUnits = spec.widthUnits,
                fallbackEndMs = lineFallbackEndMs,
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
    val builtPages = pages.map { columns ->
        LetterPage(
            columns = columns,
            firstLineIndex = columns.minOf { it.lineIndex },
            lastLineIndex = columns.maxOf { it.lineIndex },
        )
    }.ifEmpty { listOf(LetterPage.EMPTY) }
    return LetterPagesBuild(
        pages = builtPages,
        primaryRevealSchedules = primaryRevealSchedules,
    )
}

private fun splitIntoVerticalSegments(
    text: String,
    maxCharacters: Int,
    maxLatinWidthPx: Float,
    measureLatinTextWidthPx: (String) -> Float,
): List<String> {
    if (text.isBlank()) return emptyList()
    if (text.isRotatedLatinPhrase()) {
        return splitLatinPhraseIntoSegments(
            text = text,
            maxWidthPx = maxLatinWidthPx,
            measureTextWidthPx = measureLatinTextWidthPx,
        )
    }
    val graphemes = text.letterGraphemes()
    return graphemes.chunked(maxCharacters).map { it.joinToString("") }
}

internal fun splitLatinPhraseIntoSegments(
    text: String,
    maxWidthPx: Float,
    measureTextWidthPx: (String) -> Float,
): List<String> {
    if (text.isBlank()) return emptyList()
    val graphemes = text.letterGraphemes()
    val segments = ArrayList<String>()
    var segmentStart = 0

    while (segmentStart < graphemes.size) {
        var fittedEnd = segmentStart
        var lastBreakEnd = -1
        var candidateEnd = segmentStart + 1

        while (candidateEnd <= graphemes.size) {
            val candidate = graphemes
                .subList(segmentStart, candidateEnd)
                .joinToString("")
            if (measureTextWidthPx(candidate) > maxWidthPx) break

            fittedEnd = candidateEnd
            if (graphemes[candidateEnd - 1].isLatinLineBreakOpportunity()) {
                lastBreakEnd = candidateEnd
            }
            candidateEnd += 1
        }

        if (fittedEnd == graphemes.size) {
            segments += graphemes.subList(segmentStart, fittedEnd).joinToString("")
            break
        }

        val segmentEnd = when {
            lastBreakEnd > segmentStart -> lastBreakEnd
            fittedEnd > segmentStart -> fittedEnd
            else -> segmentStart + 1
        }
        segments += graphemes.subList(segmentStart, segmentEnd).joinToString("")
        segmentStart = segmentEnd
    }

    return segments
}

private fun String.isLatinLineBreakOpportunity(): Boolean {
    if (all(Char::isWhitespace)) return true
    val codePoint = codePointAt(0)
    return when (Character.getType(codePoint)) {
        Character.DASH_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        -> true
        else -> false
    }
}

private fun letterActiveLineIndex(renderState: LyricsRenderState): Int = when (
    val phase = renderState.timeline.phase
) {
    is LyricsTimelinePhase.Line -> phase.index
    is LyricsTimelinePhase.Gap -> phase.previousIndex
    LyricsTimelinePhase.BeforeFirstLine -> -1
    LyricsTimelinePhase.AfterLastLine -> renderState.document.lines.lastIndex
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

private data class LetterSealAppearance(
    val customImagePath: String?,
    val sizeDp: Int,
    val opacityPercent: Int,
    val rotationDegrees: Int,
)

private data class LetterColumn(
    val lineIndex: Int,
    val line: LyricLineNode,
    val text: String,
    val graphemes: List<String>,
    val graphemeLayouts: List<TextLayoutResult>,
    val graphemeRevealMs: IntArray,
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
    val usePrimaryWordSchedule: Boolean,
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

private val LETTER_PAPER_BASE = Color(0xFFF2E5CF)
private val LETTER_OVERVIEW_BACKDROP = Color(0xFFE4D2B5)
private val LETTER_INK = Color(0xFF382C24)
private const val MAIN_COLUMN_UNITS = 1f
private const val TRANSLATION_COLUMN_UNITS = 0.58f
private const val GROUP_GAP_UNITS = 0.42f
private const val INTERLUDE_BLANK_UNITS = 1f
private const val LETTER_INTERLUDE_MIN_MS = 7_000
private const val LETTER_INK_POOL_COUNT = 7
private const val LETTER_INK_FIBRE_COUNT = 9
private const val LETTER_INK_POOL_START_RADIUS = 0.17f
private const val LETTER_INK_POOL_GROWTH = 0.5f
private val LETTER_OVERVIEW_ROTATIONS = floatArrayOf(-1.1f, 0.7f, -0.35f, 0.9f)

private object LetterPrototypeHintSession {
    private var claimed = false

    fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}
