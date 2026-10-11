package com.mica.music.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.DEFAULT_LYRICS_PAGE_FONT_SIZE_SP
import com.mica.music.data.DEFAULT_LYRICS_PAGE_LINE_SPACING_DP
import com.mica.music.data.DEFAULT_LYRICS_CURRENT_LINE_POSITION_PERCENT
import com.mica.music.data.MIN_LYRICS_CURRENT_LINE_POSITION_PERCENT
import com.mica.music.data.MAX_LYRICS_CURRENT_LINE_POSITION_PERCENT
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.LyricsWordAnimationPreset
import com.mica.music.data.LyricsRenderState
import com.mica.music.ui.components.LyricLineBlock
import com.mica.music.ui.components.LyricsAreaEdgeFade
import com.mica.music.ui.components.rememberLyricUniformStyle
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalLyricSplitEnabled
import com.mica.music.ui.theme.PlayerContentColors
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

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
    lyricsLineSpacingDp: Int = DEFAULT_LYRICS_PAGE_LINE_SPACING_DP,
    lyricsCurrentLinePositionPercent: Int = DEFAULT_LYRICS_CURRENT_LINE_POSITION_PERCENT,
    lyricsWordAnimationPreset: LyricsWordAnimationPreset = LyricsWordAnimationPreset.SYLLABLE_LIFT,
    bilingualDisplayMode: LyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
    lyricsPageOpen: Boolean = true,
    lyricsLayoutFocus: Float = 1f,
    /** QA-only real geometry/scroll observer. Inactive in production. */
    debugMotionTrace: ((ExpandedLyricsMotionTrace) -> Unit)? = null,
    /** Integration-test control: separate a fixed list from ordinary auto-follow. */
    debugFreezeFollowAfterSnap: Boolean = false,
) {
    val layoutTransition = expandedLyricsLayoutTransition(lyricsPageOpen, lyricsLayoutFocus)
    val lyrics = renderState.lyrics
    val positionMs = renderState.positionMs
    val textStyle = rememberLyricUniformStyle().withFontSizeSp(lyricsFontSizeSp)
    val translationTextStyle = rememberLyricUniformStyle().withFontSizeSp(lyricsTranslationFontSizeSp)
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

    val activeDisplayItems = remember(lyrics, positionMs) {
        expandedLyricsDisplayItems(lyrics, playbackPositionMs = positionMs)
    }
    val activeInterlude = activeDisplayItems.filterIsInstance<ExpandedLyricDisplayItem.Interlude>()
        .firstOrNull()
    // Keep a departing interlude mounted for its 250ms fade-out. Its key is stable:
    // no ghost copies of normal rows, and no Foundation moving-away placement.
    var mostRecentInterlude by remember(lyrics) {
        mutableStateOf<ExpandedLyricDisplayItem.Interlude?>(null)
    }
    var interludeExitFinished by remember(lyrics) { mutableStateOf(false) }
    SideEffect {
        if (activeInterlude != null) mostRecentInterlude = activeInterlude
    }
    LaunchedEffect(activeInterlude?.key) {
        if (activeInterlude != null) interludeExitFinished = false
    }
    val exitingInterlude = mostRecentInterlude?.takeIf { candidate ->
        activeInterlude == null && !interludeExitFinished &&
            positionMs >= candidate.endTimeMs &&
            positionMs < candidate.endTimeMs + CLASSIC_LYRICS_FADE_MS
    }
    LaunchedEffect(exitingInterlude?.key) {
        if (exitingInterlude != null) {
            delay(CLASSIC_LYRICS_FADE_MS.toLong())
            interludeExitFinished = true
        }
    }
    val displayItems = if (exitingInterlude == null) {
        activeDisplayItems
    } else {
        buildList {
            activeDisplayItems.forEach { item ->
                if (item is ExpandedLyricDisplayItem.Line &&
                    item.lyricIndex == exitingInterlude.nextLyricIndex
                ) add(exitingInterlude)
                add(item)
            }
        }
    }
    val timed = renderState.hasTimedLyrics
    val currentIndex = renderState.activeLineIndex
    val currentDisplayItemIndex = displayItems.indexOfFirst { item ->
        item is ExpandedLyricDisplayItem.Line && item.lyricIndex == currentIndex
    }
    val interludeKey = activeInterlude?.key
    val listState = rememberLazyListState()
    val scrollAuthority = remember(lyrics) { LyricsScrollAuthority() }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    val debugLedger = remember { ExpandedLyricsScrollLedger() }
    // A stable measured geometry receipt from BEFORE the interlude enters the list.
    // It is keyed by real lyric identity, never by the shifting LazyColumn index.
    val preInterludeGeometry = remember(lyrics) { mutableMapOf<String, ExpandedLyricsInterludeReceipt>() }
    var debugListRootY by remember { mutableFloatStateOf(Float.NaN) }
    val density = LocalDensity.current
    val lineHeightPx = with(density) { textStyle.lineHeight.toPx().toInt() }
    val translationLineHeightPx = with(density) { translationTextStyle.lineHeight.toPx().toInt() }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var frozenLayoutViewportPx by remember { mutableIntStateOf(0) }
    SideEffect {
        if (layoutTransition.snapshotViewport) {
            if (frozenLayoutViewportPx == 0 && viewportHeightPx > 0) {
                frozenLayoutViewportPx = viewportHeightPx
            }
        } else {
            frozenLayoutViewportPx = 0
        }
    }
    val layoutViewportHeightPx = if (layoutTransition.snapshotViewport && frozenLayoutViewportPx > 0) {
        frozenLayoutViewportPx
    } else {
        viewportHeightPx
    }
    val currentLineAnchorYPx = expandedLyricsCurrentLineAnchorYPx(
        viewportHeightPx = layoutViewportHeightPx,
        currentLinePositionPercent = lyricsCurrentLinePositionPercent,
    )
    val anchorRangePadding = expandedLyricsAnchorRangePaddingPx(
        viewportHeightPx = layoutViewportHeightPx,
        itemHeightPx = lineHeightPx,
    )
    val leadingPaddingPx = maxOf(
        with(density) { HifiSpacing.sm.roundToPx() },
        anchorRangePadding.leadingPx,
    )
    val leadingPadding = with(density) { leadingPaddingPx.toDp() }
    val trailingPadding = HifiSpacing.xl + with(density) {
        anchorRangePadding.trailingPx.toDp()
    }
    val staggerOffsets = remember { mutableStateMapOf<Int, Float>() }
    val motionEnabled = rememberMicaMotionEnabled()
    val lineIntervalMs = currentIndex.takeIf { it > 0 }?.let { index ->
        lyrics[index].timeMs - lyrics[index - 1].timeMs
    } ?: 800
    var previousInterludeKey by remember(lyrics) { mutableStateOf(interludeKey) }
    var interludeBridgeActive by remember(lyrics) { mutableStateOf(false) }
    // Snap (not spring) when lyrics document changes / first layout; spring only for line follow.
    // Also hide until snapped so "start at top → animate down to anchor" is not visible.
    val lyricsContentKey = remember(lyrics) {
        listOf(
            lyrics.size,
            lyrics.firstOrNull()?.timeMs,
            lyrics.firstOrNull()?.text,
            lyrics.lastOrNull()?.timeMs,
            lyrics.lastOrNull()?.text,
        )
    }
    // The layout motion controller is local to this lyrics document, not the Lazy item animator.
    val ownedMotionScope = rememberCoroutineScope()
    val ownedMotion = remember(lyricsContentKey) { OwnedLyricsPositionMotion(ownedMotionScope) }
    DisposableEffect(ownedMotion) { onDispose { ownedMotion.dispose() } }
    var revealedLyricsKey by remember { mutableStateOf<List<Any?>?>(null) }
    val lyricsRevealed = (!timed || revealedLyricsKey == lyricsContentKey) &&
        !layoutTransition.hideUntilSettled
    // After a track/lyrics snap, one more layout pass often re-triggers the spring follow with
    // staggerOffsets; those translationY lags get clipped by the item bounds (top/bottom shaved).
    var suppressedFollowIndex by remember { mutableStateOf<Int?>(null) }
    val motionActive = rememberUpdatedState(
        motionEnabled && lyricsRevealed && !layoutTransition.freezeScroll,
    )
    val currentMotionStiffness = rememberUpdatedState(classicLyricsMoveStiffness(lineIntervalMs))
    // The only authority for item layout endpoints is measured LazyColumn geometry.
    // Nested post-scroll contributes the consumed screen travel exactly once.
    val latestInterludeKey = rememberUpdatedState(interludeKey)
    val upcomingEndMs = lyrics.getOrNull(currentIndex)?.endTimeMs
    val approachingInsertion = upcomingEndMs != null &&
        (lyrics.getOrNull(currentIndex + 1)?.timeMs ?: 0) - upcomingEndMs >= 7_000 &&
        positionMs in (upcomingEndMs - 2_000) until upcomingEndMs
    // A running B3 FLIP spring updates graphic-layer Y without requiring a new
    // Lazy measurement. Refresh the visual receipt each frame close to the
    // insertion boundary, otherwise a stale receipt can cost 10px on rapid cues.
    LaunchedEffect(ownedMotion, listState, approachingInsertion) {
        if (!approachingInsertion) return@LaunchedEffect
        while (latestInterludeKey.value == null) {
            withFrameNanos { }
            if (latestInterludeKey.value != null) break
            // Only the currently visible geometry belongs to the pending transaction.
            // Do not retain an O(all visited lyric lines) historical map.
            preInterludeGeometry.clear()
            listState.layoutInfo.visibleItemsInfo.forEach { info ->
                val key = info.key.toString()
                preInterludeGeometry[key] = ExpandedLyricsInterludeReceipt(
                    itemOffsetPx = info.offset,
                    visualViewportYPx = info.offset +
                        ownedMotion.translationFor(key) + (staggerOffsets[info.index] ?: 0f),
                    followConsumedPx = debugLedger.followPx,
                )
            }
        }
    }
    LaunchedEffect(ownedMotion, listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.map { info ->
                OwnedLyricGeometry(
                    key = info.key.toString(),
                    offsetPx = info.offset,
                    heightPx = info.size,
                )
            }
        }.collect { measured ->
            if (latestInterludeKey.value == null) {
                preInterludeGeometry.clear()
                // Saved in the SAME Lazy viewport coordinate space as the next measurement.
                // A new insertion must not blindly scroll by its nominal height.
                measured.forEach {
                    preInterludeGeometry[it.key] = ExpandedLyricsInterludeReceipt(
                        itemOffsetPx = it.offsetPx,
                        visualViewportYPx = it.offsetPx + ownedMotion.translationFor(it.key),
                        followConsumedPx = debugLedger.followPx,
                    )
                }
            }
            ownedMotion.accept(
                measured = measured,
                animate = motionActive.value,
                stiffness = currentMotionStiffness.value,
                scrolling = listState.isScrollInProgress,
            )
        }
    }
    LaunchedEffect(ownedMotion, viewportHeightPx, layoutTransition.freezeScroll,
        lyricsFontSizeSp, lyricsLineSpacingDp, lyricsCurrentLinePositionPercent,
    ) {
        ownedMotion.reset()
        preInterludeGeometry.clear()
    }
    LaunchedEffect(ownedMotion, renderState.positionRevision) {
        scrollAuthority.resetToFollowing()
        ownedMotion.reset()
        interludeBridgeActive = false
        preInterludeGeometry.clear()
    }
    // Drag and fling own the true LazyListState. The 4-second idle timer begins
    // only after kinetic scrolling ends; new touch/seek invalidates its generation.
    LaunchedEffect(isUserDragging, listState, scrollAuthority) {
        if (isUserDragging) {
            scrollAuthority.beginGesture()
            interludeBridgeActive = false
            ownedMotion.releasePins()
        } else if (scrollAuthority.mode == LyricsScrollMode.BROWSING_DRAG) {
            val token = scrollAuthority.handOffToFling()
            snapshotFlow { listState.isScrollInProgress }.first { !it }
            if (scrollAuthority.settleGesture(token)) {
                delay(4_000L)
                scrollAuthority.returnToCurrent(token)
            }
        }
    }

    val currentDebugState = rememberUpdatedState(
        Triple(positionMs, isPlaying, interludeKey),
    )
    val currentDebugOffsets = rememberUpdatedState(staggerOffsets.toMap())
    if (debugMotionTrace != null) {
        LaunchedEffect(debugMotionTrace, ownedMotion, listState) {
            while (true) {
                val frame = withFrameNanos { it }
                val (samplePosition, samplePlaying, sampleInterlude) = currentDebugState.value
                val info = listState.layoutInfo
                debugMotionTrace(
                    ExpandedLyricsMotionTrace(
                        frameNanos = frame,
                        positionMs = samplePosition,
                        playing = samplePlaying,
                        interludeKey = sampleInterlude,
                        event = "frame",
                        followConsumedPx = debugLedger.followPx,
                        interludeConsumedPx = debugLedger.interludePx,
                        listRootYPx = debugListRootY,
                        firstVisibleIndex = listState.firstVisibleItemIndex,
                        firstVisibleScrollOffset = listState.firstVisibleItemScrollOffset,
                        items = info.visibleItemsInfo.map { measured ->
                            val key = measured.key.toString()
                            ExpandedLyricsRowTrace(
                                key = key,
                                index = measured.index,
                                measuredViewportYPx = measured.offset,
                                measuredHeightPx = measured.size,
                                rootGeometryYPx = debugListRootY + measured.offset,
                                ownedTranslationPx = ownedMotion.translationFor(key),
                                staggerTranslationPx = currentDebugOffsets.value[measured.index] ?: 0f,
                            )
                        },
                    ),
                )
            }
        }
    }

    LaunchedEffect(layoutTransition.hideUntilSettled) {
        if (layoutTransition.hideUntilSettled) {
            revealedLyricsKey = null
            suppressedFollowIndex = null
        }
    }

    LaunchedEffect(interludeKey, motionEnabled, lyricsLineSpacingDp,
        layoutTransition.freezeScroll, scrollAuthority.generation,
    ) {
        if (layoutTransition.freezeScroll || scrollAuthority.browsing) {
            previousInterludeKey = interludeKey
            interludeBridgeActive = false
            ownedMotion.releasePins()
            return@LaunchedEffect
        }
        val interludeAppeared = previousInterludeKey == null && interludeKey != null
        previousInterludeKey = interludeKey
        if (!interludeAppeared) {
            interludeBridgeActive = false
            ownedMotion.releasePins()
            return@LaunchedEffect
        }

        // ENTERING is a bounded layout transaction, not a state lasting for
        // the entire 12-second interlude. The next-line visual position is
        // held only until the measured list correction has been consumed.
        interludeBridgeActive = true
        try {
            withFrameNanos { }
            val entering = activeInterlude ?: return@LaunchedEffect
            val nextLine = lyrics.getOrNull(entering.nextLyricIndex) ?: return@LaunchedEffect
            val nextKey = ExpandedLyricDisplayItem.Line(entering.nextLyricIndex, nextLine).key
            val before = preInterludeGeometry[nextKey] ?: return@LaunchedEffect
            val after = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key.toString() == nextKey } ?: return@LaunchedEffect
            val maxHeightPx = with(density) { (10.dp + lyricsLineSpacingDp.dp).toPx() }
            val correction = expandedLyricsMeasuredInterludeCorrectionPx(
                beforeItemOffsetPx = before.itemOffsetPx,
                afterItemOffsetPx = after.offset,
                followScrollSinceBeforePx = debugLedger.followPx - before.followConsumedPx,
                maxInsertionHeightPx = maxHeightPx,
            )
            if (correction > 0.75f) {
                if (!motionEnabled) {
                    listState.scroll(MutatePriority.Default) {
                        debugLedger.interludePx += scrollBy(correction)
                    }
                } else {
                    val animation = TargetBasedAnimation(
                        animationSpec = classicLyricsScrollSpring(lineIntervalMs),
                        typeConverter = Float.VectorConverter,
                        initialValue = 0f,
                        targetValue = correction,
                    )
                    // The whole programmatic animation is one scroll mutation.
                    // LazyColumn's user-input priority can interrupt it cleanly.
                    listState.scroll(MutatePriority.Default) {
                        val start = withFrameNanos { it }
                        var previous = 0f
                        while (true) {
                            val elapsed = (withFrameNanos { it } - start).coerceAtLeast(0L)
                            val current = animation.getValueFromNanos(
                                elapsed.coerceAtMost(animation.durationNanos),
                            )
                            debugLedger.interludePx += scrollBy(current - previous)
                            previous = current
                            if (elapsed >= animation.durationNanos) break
                        }
                    }
                }
            }
        } finally {
            interludeBridgeActive = false
            ownedMotion.releasePins()
            preInterludeGeometry.clear()
        }
    }

    LaunchedEffect(
        currentIndex,
        currentDisplayItemIndex,
        timed,
        lyricsContentKey,
        currentLineAnchorYPx,
        layoutViewportHeightPx,
        lineHeightPx,
        translationLineHeightPx,
        layoutTransition.freezeScroll,
        debugFreezeFollowAfterSnap,
        scrollAuthority.generation,
        scrollAuthority.returnToCurrentRevision,
    ) {
        if (layoutTransition.freezeScroll || !scrollAuthority.mayFollow) return@LaunchedEffect
        if (debugFreezeFollowAfterSnap && revealedLyricsKey == lyricsContentKey) {
            return@LaunchedEffect
        }
        if (!timed || currentIndex < 0 || currentDisplayItemIndex < 0) {
            revealedLyricsKey = lyricsContentKey
            return@LaunchedEffect
        }
        if (viewportHeightPx <= 0) return@LaunchedEffect
        staggerOffsets.clear()
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
        val indexedScrollOffset = expandedLyricsIndexedScrollOffset(leadingPaddingPx, offset)
        val needsSnap = revealedLyricsKey != lyricsContentKey
        val currentLayoutInfo = listState.layoutInfo
        val visibleTarget = currentLayoutInfo.visibleItemsInfo
            .firstOrNull { it.index == currentDisplayItemIndex }
        val scrollDistance = visibleTarget?.let { itemInfo ->
            expandedLyricsGeometryScrollDelta(
                viewportStartOffsetPx = currentLayoutInfo.viewportStartOffset,
                viewportEndOffsetPx = currentLayoutInfo.viewportEndOffset,
                currentLinePositionPercent = lyricsCurrentLinePositionPercent,
                itemOffsetPx = itemInfo.offset,
                itemSizePx = itemInfo.size,
            )
        }
        val followDecision = expandedLyricsFollowDecision(
            needsSnap = needsSnap,
            suppressedIndex = suppressedFollowIndex,
            currentIndex = currentIndex,
            motionEnabled = motionEnabled,
            targetVisible = visibleTarget != null,
            scrollDistancePx = scrollDistance,
        )

        if (followDecision.mode == ExpandedLyricsFollowMode.SPRING) {
            val springDistance = requireNotNull(scrollDistance)
            val scrollAnimation = TargetBasedAnimation(
                animationSpec = classicLyricsScrollSpring(lineIntervalMs),
                typeConverter = Float.VectorConverter,
                initialValue = 0f,
                targetValue = springDistance,
            )
            var previousScroll = 0f
            var maxDelayNanos = 0L
            try {
                // One scrolling mutation for the full follow animation. A drag
                // preempts it via LazyColumn's UserInput priority.
                listState.scroll(MutatePriority.Default) {
                val startNanos = withFrameNanos { it }
                while (true) {
                    val playTimeNanos = (withFrameNanos { it } - startNanos).coerceAtLeast(0L)
                    val actualPlayTimeNanos = playTimeNanos.coerceAtMost(scrollAnimation.durationNanos)
                    val actualScroll = scrollAnimation.getValueFromNanos(actualPlayTimeNanos)
                    debugLedger.followPx += scrollBy(actualScroll - previousScroll)
                    previousScroll = actualScroll

                    listState.layoutInfo.visibleItemsInfo.forEach { itemInfo ->
                        val delayNanos = classicLyricsStaggerDelayMs(
                            kotlin.math.abs(itemInfo.index - currentDisplayItemIndex),
                        ) * 1_000_000L
                        maxDelayNanos = maxOf(maxDelayNanos, delayNanos)
                        val delayedPlayTimeNanos = (playTimeNanos - delayNanos)
                            .coerceIn(0L, scrollAnimation.durationNanos)
                        val delayedScroll = scrollAnimation.getValueFromNanos(delayedPlayTimeNanos)
                        staggerOffsets[itemInfo.index] = classicLyricsLagOffset(
                            actualScrollPx = actualScroll,
                            delayedScrollPx = delayedScroll,
                        )
                    }
                    if (playTimeNanos >= scrollAnimation.durationNanos + maxDelayNanos) break
                }
                }
            } finally {
                staggerOffsets.clear()
            }
        } else if (followDecision.mode == ExpandedLyricsFollowMode.INSTANT) {
            check(scrollDistance != null)
            debugLedger.followPx += listState.scrollBy(scrollDistance)
        } else {
            // A non-local seek cannot inherit a previous row's FLIP translation.
            ownedMotion.reset()
            listState.scrollToItem(currentDisplayItemIndex, scrollOffset = indexedScrollOffset)
            // The snap is an authority reset, not a displacement-animation event.
            withFrameNanos { }
            val measuredLayoutInfo = listState.layoutInfo
            measuredLayoutInfo.visibleItemsInfo
                .firstOrNull { it.index == currentDisplayItemIndex }
                ?.let { measuredItem ->
                    val correction = expandedLyricsGeometryScrollDelta(
                        viewportStartOffsetPx = measuredLayoutInfo.viewportStartOffset,
                        viewportEndOffsetPx = measuredLayoutInfo.viewportEndOffset,
                        currentLinePositionPercent = lyricsCurrentLinePositionPercent,
                        itemOffsetPx = measuredItem.offset,
                        itemSizePx = measuredItem.size,
                    )
                    if (kotlin.math.abs(correction) > 0.5f) {
                        debugLedger.followPx += listState.scrollBy(correction)
                    }
                }
        }
        revealedLyricsKey = lyricsContentKey
        suppressedFollowIndex = followDecision.nextSuppressedIndex
        scrollAuthority.followSettled()
    }

    LyricsAreaEdgeFade(
        modifier = modifier.graphicsLayer {
            alpha = if (lyricsRevealed) 1f else 0f
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(ownedMotion.nestedScrollConnection)
                .onGloballyPositioned { debugListRootY = it.positionInRoot().y }
                .onSizeChanged { viewportHeightPx = it.height },
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = leadingPadding,
                bottom = trailingPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(lyricsLineSpacingDp.dp),
            horizontalAlignment = horizontalAlignment,
        ) {
            itemsIndexed(
                items = displayItems,
                key = { _, item -> item.key },
            ) { displayIndex, item ->
                val staggerOffsetY = staggerOffsets[displayIndex] ?: 0f
                var entered by remember(item.key) { mutableStateOf(false) }
                LaunchedEffect(item.key) { entered = true }
                val itemFadeAlpha by animateFloatAsState(
                    targetValue = if (entered && item != exitingInterlude) 1f else 0f,
                    animationSpec = tween(CLASSIC_LYRICS_FADE_MS),
                    label = "ownedLyricsEnterFade",
                )
                when (item) {
                    is ExpandedLyricDisplayItem.Line -> {
                        val index = item.lyricIndex
                        val line = item.line
                        val isCurrent = timed && index == currentIndex
                        val lineScale by animateFloatAsState(
                            targetValue = if (isCurrent) 1f else 0.97f,
                            animationSpec = spring(
                                stiffness = 100f,
                                dampingRatio = 0.88f,
                            ),
                            label = "classicLyricsLineScale",
                        )
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val pressScale = remember { Animatable(1f) }
                        LaunchedEffect(isPressed, motionEnabled) {
                            if (!motionEnabled) {
                                pressScale.snapTo(1f)
                            } else if (isPressed) {
                                pressScale.animateTo(0.985f, tween(90, easing = MicaMotion.Easing))
                            } else if (pressScale.value < 1f) {
                                pressScale.animateTo(1.015f, tween(120, easing = MicaMotion.Easing))
                                pressScale.animateTo(1f, tween(180, easing = MicaMotion.Easing))
                            }
                        }
                        val pressEmphasis by animateFloatAsState(
                            targetValue = if (isPressed) 1f else 0f,
                            animationSpec = tween(if (motionEnabled) 90 else 0, easing = MicaMotion.Easing),
                            label = "classicLyricsPressEmphasis",
                        )
                        val pressedColors = colors.copy(
                            secondary = lerp(colors.secondary, colors.primary, pressEmphasis * 0.35f),
                            tertiary = lerp(colors.tertiary, colors.primary, pressEmphasis * 0.35f),
                        )
                        val pressShadow = Shadow(
                            color = colors.primary.copy(alpha = 0.38f * pressEmphasis),
                            offset = Offset.Zero,
                            blurRadius = 12f * pressEmphasis,
                        )
                        LyricLineBlock(
                            text = line.text,
                            isCurrent = isCurrent,
                            colors = pressedColors,
                            textStyle = textStyle.copy(shadow = pressShadow),
                            colorSpec = if (isCurrent) {
                                tween(250, delayMillis = 250, easing = ClassicLyricsColorEasing)
                            } else {
                                tween(350, delayMillis = 250, easing = ClassicLyricsColorEasing)
                            },
                            maxLines = Int.MAX_VALUE,
                            lyricLine = line,
                            nextLineTimeMs = lyrics.getOrNull(index + 1)?.timeMs,
                            positionMs = positionMs,
                            positionRevision = renderState.positionRevision,
                            isPlaying = isPlaying,
                            textAlign = textAlign,
                            horizontalAlignment = horizontalAlignment,
                            bilingualDisplayMode = bilingualDisplayMode,
                            translationTextStyle = translationTextStyle.copy(shadow = pressShadow),
                            parts = renderState.document.lines.getOrNull(index)?.parts,
                            tokens = renderState.document.lines.getOrNull(index)?.tokens,
                            karaokeSyllableLift = lyricsWordAnimationPreset.syllableLiftEnabled,
                            karaokeDiscreteActiveCue = lyricsWordAnimationPreset.usesDiscreteCueFill,
                            karaokeWordFadeWidthEm = lyricsWordAnimationPreset.wordFadeWidthEm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onPlaced { coordinates ->
                                    // Synchronous layout-stage handoff, before graphicsLayer is
                                    // drawn: no one-frame +84px dip while a spring is starting.
                                    val receipt = preInterludeGeometry[item.key]
                                    if (activeInterlude != null &&
                                        (interludeBridgeActive || previousInterludeKey == null) &&
                                        !scrollAuthority.browsing &&
                                        index >= activeInterlude.nextLyricIndex &&
                                        receipt != null
                                    ) {
                                        // onPlaced's LayoutCoordinates belong to the
                                        // LyricLineBlock's modifier, not the Lazy item
                                        // placement authority: use the actual Lazy info
                                        // instead of subtracting unrelated root origins.
                                        val rawY = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key.toString() == item.key }
                                            ?.offset?.toFloat()
                                        if (rawY != null) {
                                            val naturalScroll = debugLedger.followPx - receipt.followConsumedPx
                                            ownedMotion.pin(
                                                item.key,
                                                receipt.visualViewportYPx - naturalScroll - rawY - staggerOffsetY,
                                            )
                                        }
                                    }
                                }
                                .graphicsLayer {
                                    clip = false
                                    alpha = itemFadeAlpha
                                    scaleX = lineScale * pressScale.value
                                    scaleY = lineScale * pressScale.value
                                    translationY = staggerOffsetY + ownedMotion.translationFor(item.key)
                                }
                                .then(
                                    if (timed) {
                                        Modifier.clickable(
                                            interactionSource = interactionSource,
                                            indication = null,
                                        ) {
                                            scrollAuthority.resetToFollowing()
                                            ownedMotion.reset()
                                            onLineClick(line.timeMs)
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    is ExpandedLyricDisplayItem.Interlude -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = itemFadeAlpha
                                translationY = staggerOffsetY + ownedMotion.translationFor(item.key)
                            },
                    ) {
                        InterludeDots(
                            colors = colors,
                            animate = isPlaying,
                            startTimeMs = item.startTimeMs,
                            endTimeMs = (item.endTimeMs - INTERLUDE_END_LEAD_MS)
                                .coerceAtLeast(item.startTimeMs + 1),
                            positionMs = positionMs,
                            alignment = lyricsAlignment,
                        )
                    }
                }
            }
        }
    }
}

internal data class ExpandedLyricsLayoutTransition(
    val freezeScroll: Boolean,
    /** Keep viewport/padding fixed while lyrics chrome is still visible during close. */
    val snapshotViewport: Boolean,
    /** Hide list content until open layout settles, then snap once. */
    val hideUntilSettled: Boolean,
)

internal fun expandedLyricsLayoutTransition(
    lyricsPageOpen: Boolean,
    lyricsLayoutFocus: Float,
): ExpandedLyricsLayoutTransition {
    val closing = !lyricsPageOpen && lyricsLayoutFocus > 0.01f
    val opening = lyricsPageOpen && lyricsLayoutFocus < 0.999f
    return ExpandedLyricsLayoutTransition(
        freezeScroll = closing || opening,
        snapshotViewport = closing,
        hideUntilSettled = opening,
    )
}

internal fun classicLyricsStaggerDelayMs(distance: Int): Long {
    var delaySeconds = 0.0
    var stepSeconds = 0.05
    repeat(distance.coerceAtLeast(0)) {
        delaySeconds += stepSeconds
        stepSeconds /= 1.05
    }
    return (delaySeconds * 1_000).toLong()
}

internal fun classicLyricsLagOffset(actualScrollPx: Float, delayedScrollPx: Float): Float =
    (actualScrollPx - delayedScrollPx) * 0.25f

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
        val startTimeMs: Int,
        val endTimeMs: Int,
    ) : ExpandedLyricDisplayItem {
        override val key: String = "interlude-$nextLyricIndex"
    }
}

private const val MIN_NEXT_LYRIC_DELTA_FOR_INTERLUDE_MS = 7_000
private const val INTERLUDE_END_LEAD_MS = 500
private const val INTERLUDE_TAIL_MS = 800
private const val CLASSIC_LYRICS_FADE_MS = 250
private val ClassicLyricsColorEasing = CubicBezierEasing(0.39f, 0.575f, 0.565f, 1f)

internal fun classicLyricsMoveSpring(intervalMs: Int) = spring<IntOffset>(
    stiffness = classicLyricsMoveStiffness(intervalMs),
    dampingRatio = CLASSIC_LYRICS_DYNAMIC_DAMPING_RATIO,
)

internal fun classicLyricsScrollSpring(intervalMs: Int) = spring<Float>(
    stiffness = classicLyricsMoveStiffness(intervalMs),
    dampingRatio = CLASSIC_LYRICS_DYNAMIC_DAMPING_RATIO,
)

internal fun classicLyricsMoveStiffness(intervalMs: Int): Float {
    val clamped = intervalMs.coerceIn(100, 800)
    val ratio = 1f - (clamped - 100f) / 700f
    return 170f + ratio.pow(0.2f) * 50f
}

private const val CLASSIC_LYRICS_DYNAMIC_DAMPING_RATIO = 1.16f

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
    val nextLineTimeMs = lyrics[nextLyricIndex].timeMs
    if (nextLineTimeMs - previousLineEndMs < MIN_NEXT_LYRIC_DELTA_FOR_INTERLUDE_MS) return null

    return ExpandedLyricDisplayItem.Interlude(
        nextLyricIndex = nextLyricIndex,
        startTimeMs = previousLineEndMs,
        endTimeMs = nextLineTimeMs,
    )
}

@Composable
internal fun InterludeDots(
    colors: PlayerContentColors,
    animate: Boolean,
    startTimeMs: Int,
    endTimeMs: Int,
    positionMs: Int,
    alignment: LyricsPageAlignment,
) {
    val motionEnabled = rememberMicaMotionEnabled()
    val framePositionMs = rememberClassicInterludePositionMs(positionMs, animate && motionEnabled)
    val visuals = classicInterludeVisuals(startTimeMs, endTimeMs, framePositionMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = if (motionEnabled) visuals.globalAlpha else 0.85f
                scaleX = if (motionEnabled) visuals.scale else 1f
                scaleY = if (motionEnabled) visuals.scale else 1f
            },
        horizontalArrangement = when (alignment) {
            LyricsPageAlignment.START -> Arrangement.spacedBy(6.dp, Alignment.Start)
            LyricsPageAlignment.CENTER -> Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            LyricsPageAlignment.END -> Arrangement.spacedBy(6.dp, Alignment.End)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        alpha = if (motionEnabled) visuals.dotAlpha[index] else 1f
                    }
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(colors.primary),
            )
        }
    }
}

internal data class ClassicInterludeVisuals(
    val scale: Float,
    val globalAlpha: Float,
    val dotAlpha: List<Float>,
)

internal fun classicInterludeVisuals(startMs: Int, endMs: Int, positionMs: Int): ClassicInterludeVisuals {
    val duration = (endMs - startMs).coerceAtLeast(1)
    val elapsed = (positionMs - startMs).coerceIn(0, duration)
    val breatheDuration = duration.toFloat() / kotlin.math.ceil(duration / 1500f).coerceAtLeast(1f)
    var scale = kotlin.math.sin(1.5f * Math.PI.toFloat() - elapsed / breatheDuration * 2f) / 20f + 1f
    var globalAlpha = 1f

    if (elapsed < 2_000) scale *= 1f - 2f.pow(-10f * elapsed / 2_000f)
    if (elapsed < 500) globalAlpha = 0f
    else if (elapsed < 1_000) globalAlpha = (elapsed - 500f) / 500f

    val remaining = duration - elapsed
    if (remaining < 750) scale *= 1f - easeInOutBack((750f - remaining) / 1500f)
    if (remaining < 375) globalAlpha *= (remaining / 375f).coerceIn(0f, 1f)

    val dotsDuration = (duration - INTERLUDE_TAIL_MS).coerceAtLeast(1).toFloat()
    val dotAlpha = List(3) { index ->
        val offset = dotsDuration / 3f * index
        (((elapsed - offset) * 3f / dotsDuration) * 0.75f).coerceIn(0.25f, 1f)
    }
    return ClassicInterludeVisuals(
        scale = scale.coerceAtLeast(0f) * 0.7f,
        globalAlpha = globalAlpha.coerceIn(0f, 1f),
        dotAlpha = dotAlpha,
    )
}

private fun easeInOutBack(x: Float): Float {
    val c2 = 1.70158f * 1.525f
    return if (x < 0.5f) {
        (2f * x).pow(2) * ((c2 + 1f) * 2f * x - c2) / 2f
    } else {
        ((2f * x - 2f).pow(2) * ((c2 + 1f) * (2f * x - 2f) + c2) + 2f) / 2f
    }
}

private fun Float.pow(power: Float): Float = Math.pow(toDouble(), power.toDouble()).toFloat()
private fun Float.pow(power: Int): Float = pow(power.toFloat())

@Composable
internal fun rememberClassicInterludePositionMs(anchorPositionMs: Int, running: Boolean): Int {
    // D3: pausing freezes the last drawn animation phase. Resuming continues
    // from it; ordinary player time updates do not restart the dots at frame 0.
    var framePositionMs by remember { mutableIntStateOf(anchorPositionMs) }
    var previousPlayerPositionMs by remember { mutableIntStateOf(anchorPositionMs) }
    LaunchedEffect(anchorPositionMs, running) {
        val jumped = anchorPositionMs < previousPlayerPositionMs ||
            anchorPositionMs - previousPlayerPositionMs > 250
        previousPlayerPositionMs = anchorPositionMs
        if (jumped) framePositionMs = anchorPositionMs
        if (!running) return@LaunchedEffect
        val base = maxOf(framePositionMs, anchorPositionMs)
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            framePositionMs = base + ((now - start).coerceAtLeast(0L) / 1_000_000L).toInt()
        }
    }
    return framePositionMs
}

internal fun expandedLyricsCurrentLineAnchorYPx(
    viewportHeightPx: Int,
    currentLinePositionPercent: Int,
): Float {
    if (viewportHeightPx <= 0) return 0f
    val normalizedPercent = currentLinePositionPercent.coerceIn(
        MIN_LYRICS_CURRENT_LINE_POSITION_PERCENT,
        MAX_LYRICS_CURRENT_LINE_POSITION_PERCENT,
    )
    return viewportHeightPx * (normalizedPercent / 100f)
}

internal enum class ExpandedLyricsFollowMode {
    SPRING,
    INSTANT,
    SNAP,
}

internal data class ExpandedLyricsFollowDecision(
    val mode: ExpandedLyricsFollowMode,
    val nextSuppressedIndex: Int?,
)

internal fun expandedLyricsFollowDecision(
    needsSnap: Boolean,
    suppressedIndex: Int?,
    currentIndex: Int,
    motionEnabled: Boolean,
    targetVisible: Boolean,
    scrollDistancePx: Float?,
): ExpandedLyricsFollowDecision {
    val suppressThisLine = suppressedIndex != null && suppressedIndex == currentIndex
    val useSpring = motionEnabled &&
        !needsSnap &&
        !suppressThisLine &&
        targetVisible &&
        scrollDistancePx != null &&
        kotlin.math.abs(scrollDistancePx) > 1f
    val mode = when {
        useSpring -> ExpandedLyricsFollowMode.SPRING
        targetVisible && scrollDistancePx != null -> ExpandedLyricsFollowMode.INSTANT
        else -> ExpandedLyricsFollowMode.SNAP
    }
    return ExpandedLyricsFollowDecision(
        mode = mode,
        nextSuppressedIndex = if (needsSnap) currentIndex else null,
    )
}

internal fun expandedLyricsGeometryScrollDelta(
    viewportStartOffsetPx: Int,
    viewportEndOffsetPx: Int,
    currentLinePositionPercent: Int,
    itemOffsetPx: Int,
    itemSizePx: Int,
): Float {
    val viewportSpanPx = (viewportEndOffsetPx - viewportStartOffsetPx).coerceAtLeast(0)
    val anchorInViewportPx = expandedLyricsCurrentLineAnchorYPx(
        viewportHeightPx = viewportSpanPx,
        currentLinePositionPercent = currentLinePositionPercent,
    )
    val targetAnchorPx = viewportStartOffsetPx + anchorInViewportPx
    return itemOffsetPx + itemSizePx / 2f - targetAnchorPx
}

internal data class ExpandedLyricsAnchorRangePadding(
    val leadingPx: Int,
    val trailingPx: Int,
)

internal fun expandedLyricsAnchorRangePaddingPx(
    viewportHeightPx: Int,
    itemHeightPx: Int,
): ExpandedLyricsAnchorRangePadding {
    if (viewportHeightPx <= 0) return ExpandedLyricsAnchorRangePadding(0, 0)
    val lowestAnchor = expandedLyricsCurrentLineAnchorYPx(
        viewportHeightPx = viewportHeightPx,
        currentLinePositionPercent = MAX_LYRICS_CURRENT_LINE_POSITION_PERCENT,
    )
    val highestAnchor = expandedLyricsCurrentLineAnchorYPx(
        viewportHeightPx = viewportHeightPx,
        currentLinePositionPercent = MIN_LYRICS_CURRENT_LINE_POSITION_PERCENT,
    )
    return ExpandedLyricsAnchorRangePadding(
        leadingPx = expandedLyricsLeadingPaddingPx(
            viewportHeightPx = viewportHeightPx,
            itemHeightPx = itemHeightPx,
            currentLineAnchorYPx = lowestAnchor,
        ),
        trailingPx = expandedLyricsTrailingPaddingPx(
            viewportHeightPx = viewportHeightPx,
            currentLineAnchorYPx = highestAnchor,
        ),
    )
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

internal fun expandedLyricsIndexedScrollOffset(leadingPaddingPx: Int, viewportOffsetPx: Int): Int =
    leadingPaddingPx + viewportOffsetPx

internal fun expandedLyricsTrailingPaddingPx(
    viewportHeightPx: Int,
    currentLineAnchorYPx: Float?,
): Int {
    if (viewportHeightPx <= 0) return 0
    val anchor = currentLineAnchorYPx
        ?.takeIf { it.isFinite() && it > 0f }
        ?: (viewportHeightPx / 2f)
    return (viewportHeightPx - anchor).coerceAtLeast(0f).roundToInt()
}

internal fun expandedLyricsLeadingPaddingPx(
    viewportHeightPx: Int,
    itemHeightPx: Int,
    currentLineAnchorYPx: Float?,
): Int {
    if (viewportHeightPx <= 0) return 0
    val anchor = currentLineAnchorYPx
        ?.takeIf { it.isFinite() && it > 0f }
        ?: (viewportHeightPx / 2f)
    return (anchor - itemHeightPx / 2f).coerceAtLeast(0f).roundToInt()
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
