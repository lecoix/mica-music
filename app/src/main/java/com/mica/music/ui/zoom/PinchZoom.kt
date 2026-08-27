package com.mica.music.ui.zoom

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Continuous ordered preset state for the library zoom gesture.
 *
 * Preset identity and preset order stay separate. A physical pinch is locked to the settled preset
 * and exactly one adjacent preset for its whole lifetime; reversing the fingers moves back toward
 * the source but never crosses through it into another preset during the same gesture.
 */
@Stable
class PinchZoomState internal constructor(
    initialIndex: Int,
    internal val presetCount: Int,
    private val onSettledIndexChanged: (Int) -> Unit,
) {
    var position by mutableFloatStateOf(initialIndex.toFloat())
        private set

    var settledIndex by mutableIntStateOf(initialIndex)
        private set

    var gestureActive by mutableStateOf(false)
        private set

    var settling by mutableStateOf(false)
        private set

    var primaryGestureDirection by mutableIntStateOf(0)
        private set

    var lastGestureDirection by mutableIntStateOf(0)
        private set

    private var gestureSourceIndex by mutableIntStateOf(initialIndex)
    private var gestureTargetIndex by mutableIntStateOf(initialIndex)
    private var retainGesturePairForSettle by mutableStateOf(false)

    private var pendingSettleTarget by mutableStateOf<Int?>(null)
    internal var settleGeneration by mutableIntStateOf(0)
        private set

    var motionEnabled: Boolean = true

    internal var gestureGeometryReady by mutableStateOf(true)
        private set

    val segment: PinchZoomSegment
        get() {
            if ((gestureActive || retainGesturePairForSettle) && gestureSourceIndex != gestureTargetIndex) {
                val lower = minOf(gestureSourceIndex, gestureTargetIndex)
                val upper = maxOf(gestureSourceIndex, gestureTargetIndex)
                return PinchZoomSegment(
                    lowerIndex = lower,
                    upperIndex = upper,
                    progress = (position - lower.toFloat()).coerceIn(0f, 1f),
                )
            }
            return PinchZoomMath.segment(position, presetCount)
        }

    val dominantIndex: Int
        get() = if (segment.progress < 0.5f) segment.lowerIndex else segment.upperIndex

    internal fun beginGesture(initialDirection: Int) {
        // Invalidates/cancels a pending settle LaunchedEffect before taking pointer ownership.
        pendingSettleTarget = null
        settleGeneration += 1
        val source = dominantIndex.coerceIn(0, presetCount - 1)
        gestureSourceIndex = source
        gestureTargetIndex = adjacentTarget(source, initialDirection)
        retainGesturePairForSettle = false
        gestureGeometryReady = gestureTargetIndex == source
        position = source.toFloat()
        gestureActive = true
        settling = false
        primaryGestureDirection = 0
        lastGestureDirection = 0
    }

    internal fun updateGestureDistance(
        baselineDistancePx: Float,
        distancePx: Float,
        distanceRatioPerPreset: Float,
        movementDirection: Int,
    ) {
        val netDirection = when {
            distancePx > baselineDistancePx -> 1
            distancePx < baselineDistancePx -> -1
            else -> 0
        }
        // At an end preset the first slop crossing may point outside the valid range. If the user
        // reverses before lifting, choose the valid adjacent preset once and then keep that pair.
        if (gestureTargetIndex == gestureSourceIndex && netDirection != 0) {
            val adjacent = adjacentTarget(gestureSourceIndex, netDirection)
            if (adjacent != gestureSourceIndex) {
                gestureTargetIndex = adjacent
                gestureGeometryReady = false
                return
            }
        }
        if (!gestureGeometryReady) return
        val newPosition = PinchZoomMath.lockedPositionForDistance(
            sourceIndex = gestureSourceIndex,
            targetIndex = gestureTargetIndex,
            baselineDistancePx = baselineDistancePx,
            distancePx = distancePx,
            distanceRatioPerPreset = distanceRatioPerPreset,
        )
        position = newPosition.coerceIn(0f, (presetCount - 1).coerceAtLeast(0).toFloat())
        if (movementDirection != 0) {
            if (primaryGestureDirection == 0) primaryGestureDirection = movementDirection
            lastGestureDirection = movementDirection
        }
    }


    internal fun markGestureGeometryReady() {
        if (gestureActive) gestureGeometryReady = true
    }

    private fun adjacentTarget(source: Int, direction: Int): Int {
        val step = when {
            direction > 0 -> 1
            direction < 0 -> -1
            else -> 0
        }
        val candidate = source + step
        return if (candidate in 0 until presetCount) candidate else source
    }

    internal fun requestEndGesture(radialVelocityDpPerSecond: Float) {
        if (!gestureActive) return
        gestureActive = false
        retainGesturePairForSettle = gestureSourceIndex != gestureTargetIndex
        settling = true
        pendingSettleTarget = PinchZoomMath.settleTarget(
            position = position,
            radialVelocityDpPerSecond = if (gestureGeometryReady) radialVelocityDpPerSecond else 0f,
            primaryDirection = primaryGestureDirection,
            lastDirection = lastGestureDirection,
            presetCount = presetCount,
        )
        settleGeneration += 1
    }

    internal suspend fun runPendingSettle() {
        val target = pendingSettleTarget ?: return
        val start = position
        if (motionEnabled && abs(start - target) > 0.0001f) {
            animate(
                initialValue = start,
                targetValue = target.toFloat(),
                animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
            ) { value, _ ->
                position = value
            }
        } else {
            position = target.toFloat()
        }
        if (pendingSettleTarget != target) return
        settledIndex = target
        position = target.toFloat()
        pendingSettleTarget = null
        settling = false
        retainGesturePairForSettle = false
        gestureGeometryReady = true
        gestureSourceIndex = target
        gestureTargetIndex = target
        primaryGestureDirection = 0
        lastGestureDirection = 0
        onSettledIndexChanged(target)
    }

    fun requestExternalIndex(index: Int) {
        if (gestureActive) return
        val clamped = index.coerceIn(0, (presetCount - 1).coerceAtLeast(0))
        if (settledIndex == clamped && abs(position - clamped) < 0.0001f) return
        pendingSettleTarget = clamped
        retainGesturePairForSettle = false
        gestureGeometryReady = true
        gestureSourceIndex = settledIndex
        gestureTargetIndex = settledIndex
        settling = true
        primaryGestureDirection = if (clamped > position) 1 else if (clamped < position) -1 else 0
        lastGestureDirection = primaryGestureDirection
        settleGeneration += 1
    }
}

@Composable
fun rememberPinchZoomState(
    presetCount: Int,
    initialIndex: Int,
    externalIndex: Int = initialIndex,
    motionEnabled: Boolean = true,
    stateKey: Any? = Unit,
    onSettledIndexChanged: (Int) -> Unit,
): PinchZoomState {
    require(presetCount > 0) { "presetCount must be > 0" }
    val latestOnSettled by rememberUpdatedState(onSettledIndexChanged)
    val state = remember(presetCount, stateKey) {
        PinchZoomState(
            initialIndex = initialIndex.coerceIn(0, presetCount - 1),
            presetCount = presetCount,
            onSettledIndexChanged = { latestOnSettled(it) },
        )
    }
    state.motionEnabled = motionEnabled
    LaunchedEffect(externalIndex, presetCount) {
        state.requestExternalIndex(externalIndex.coerceIn(0, presetCount - 1))
    }
    LaunchedEffect(state.settleGeneration) {
        state.runPendingSettle()
    }
    return state
}

data class PinchZoomSegment(
    val lowerIndex: Int,
    val upperIndex: Int,
    /** Progress from lower -> upper. */
    val progress: Float,
)

internal object PinchZoomMath {
    const val StrongVelocityThresholdDpPerSecond = 500f
    const val NormalCommitThreshold = 0.30f
    const val NearSourceHysteresis = 0.20f
    const val ReverseCommitThreshold = 0.80f
    const val DefaultDistanceRatioPerPreset = 1.55f

    fun positionForDistance(
        startPosition: Float,
        baselineDistancePx: Float,
        distancePx: Float,
        presetCount: Int,
        distanceRatioPerPreset: Float = DefaultDistanceRatioPerPreset,
    ): Float {
        if (presetCount <= 1) return 0f
        if (baselineDistancePx <= 0f || distancePx <= 0f || distanceRatioPerPreset <= 1f) {
            return startPosition.coerceIn(0f, (presetCount - 1).toFloat())
        }
        val presetDelta = ln(distancePx / baselineDistancePx) / ln(distanceRatioPerPreset)
        return (startPosition + presetDelta).coerceIn(0f, (presetCount - 1).toFloat())
    }

    fun lockedPositionForDistance(
        sourceIndex: Int,
        targetIndex: Int,
        baselineDistancePx: Float,
        distancePx: Float,
        distanceRatioPerPreset: Float = DefaultDistanceRatioPerPreset,
    ): Float {
        if (sourceIndex == targetIndex) return sourceIndex.toFloat()
        val raw = positionForDistance(
            startPosition = sourceIndex.toFloat(),
            baselineDistancePx = baselineDistancePx,
            distancePx = distancePx,
            presetCount = maxOf(sourceIndex, targetIndex) + 1,
            distanceRatioPerPreset = distanceRatioPerPreset,
        )
        return raw.coerceIn(
            minOf(sourceIndex, targetIndex).toFloat(),
            maxOf(sourceIndex, targetIndex).toFloat(),
        )
    }

    fun segment(position: Float, presetCount: Int): PinchZoomSegment {
        if (presetCount <= 1) return PinchZoomSegment(0, 0, 0f)
        val clamped = position.coerceIn(0f, (presetCount - 1).toFloat())
        val lower = floor(clamped).toInt().coerceIn(0, presetCount - 1)
        val upper = ceil(clamped).toInt().coerceIn(0, presetCount - 1)
        return PinchZoomSegment(lower, upper, (clamped - lower).coerceIn(0f, 1f))
    }

    fun settleTarget(
        position: Float,
        radialVelocityDpPerSecond: Float,
        primaryDirection: Int,
        lastDirection: Int,
        presetCount: Int,
    ): Int {
        if (presetCount <= 1) return 0
        val clamped = position.coerceIn(0f, (presetCount - 1).toFloat())
        val nearest = clamped.roundToInt().coerceIn(0, presetCount - 1)
        if (abs(clamped - nearest) < 0.0001f) return nearest

        val lower = floor(clamped).toInt().coerceIn(0, presetCount - 1)
        val upper = ceil(clamped).toInt().coerceIn(0, presetCount - 1)
        if (abs(radialVelocityDpPerSecond) >= StrongVelocityThresholdDpPerSecond) {
            return if (radialVelocityDpPerSecond > 0f) upper else lower
        }

        val movementDirection = when {
            lastDirection != 0 -> lastDirection.signInt()
            primaryDirection != 0 -> primaryDirection.signInt()
            else -> if (clamped - lower >= 0.5f) 1 else -1
        }
        val source = if (movementDirection > 0) lower else upper
        val target = if (movementDirection > 0) upper else lower
        val progressTowardTarget = if (movementDirection > 0) clamped - lower else upper - clamped

        if (progressTowardTarget <= NearSourceHysteresis) return source
        if (progressTowardTarget >= ReverseCommitThreshold) return target

        val reversed = primaryDirection != 0 && lastDirection != 0 &&
            primaryDirection.signInt() != lastDirection.signInt()
        val threshold = if (reversed) ReverseCommitThreshold else NormalCommitThreshold
        return if (progressTowardTarget >= threshold) target else source
    }

    fun contentCrossfadeProgress(progress: Float): Float {
        val x = ((progress.coerceIn(0f, 1f) - 0.30f) / 0.40f).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun Int.signInt(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }
}

/**
 * Low-level two-pointer detector. Gesture ownership begins only after radial movement exceeds touch
 * slop. Once pinch owns the stream, lifting one finger does not hand the remaining finger back to
 * scrolling; the tail is consumed until all pointers are up.
 */
fun Modifier.pinchZoomGesture(
    state: PinchZoomState,
    enabled: Boolean = true,
    distanceRatioPerPreset: Float = PinchZoomMath.DefaultDistanceRatioPerPreset,
    onGestureStart: (initialDirection: Int) -> Unit = {},
    onGestureEnd: () -> Unit = {},
): Modifier {
    if (!enabled) return this
    return pointerInput(state, enabled, distanceRatioPerPreset) {
        val pxDensity = density
        val touchSlopPx = viewConfiguration.touchSlop
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var baselineDistance = Float.NaN
            var active = false
            var lastDistance = Float.NaN
            var lastTimeMs = 0L
            var radialVelocityDpPerSecond = 0f
            var geometryWasReady = true

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    val first = pressed[0]
                    val second = pressed[1]
                    val distance = pointerDistance(first, second)
                    val eventTimeMs = maxOf(first.uptimeMillis, second.uptimeMillis)
                    if (baselineDistance.isNaN()) {
                        baselineDistance = distance.coerceAtLeast(1f)
                        lastDistance = distance
                        lastTimeMs = eventTimeMs
                        continue
                    }

                    val dtMs = (eventTimeMs - lastTimeMs).coerceAtLeast(1L)
                    val instantVelocity = ((distance - lastDistance) * 1000f / dtMs) / pxDensity
                    radialVelocityDpPerSecond =
                        radialVelocityDpPerSecond * 0.65f + instantVelocity * 0.35f
                    val movementDirection = (distance - lastDistance).sign.toInt()

                    if (!active && abs(distance - baselineDistance) >= touchSlopPx) {
                        active = true
                        val initialDirection = (distance - baselineDistance).sign.toInt()
                        state.beginGesture(initialDirection)
                        onGestureStart(initialDirection)
                        geometryWasReady = state.gestureGeometryReady
                    }
                    if (active) {
                        when {
                            !state.gestureGeometryReady -> {
                                // The target LazyGrid is still being anchor-aligned by composition.
                                // Hold the visible scene exactly at the source endpoint meanwhile.
                            }
                            !geometryWasReady -> {
                                // Alignment just became ready. Rebase the physical pinch here so
                                // the first morph frame starts at progress=0 instead of catching up.
                                baselineDistance = distance.coerceAtLeast(1f)
                                radialVelocityDpPerSecond = 0f
                                geometryWasReady = true
                            }
                            else -> state.updateGestureDistance(
                                baselineDistancePx = baselineDistance,
                                distancePx = distance,
                                distanceRatioPerPreset = distanceRatioPerPreset,
                                movementDirection = movementDirection,
                            )
                        }
                        event.changes.forEach { it.consume() }
                    }
                    lastDistance = distance
                    lastTimeMs = eventTimeMs
                    continue
                }

                if (active) {
                    event.changes.forEach { it.consume() }
                    state.requestEndGesture(radialVelocityDpPerSecond)
                    onGestureEnd()
                    var remainingPressed = event.changes.any { it.pressed }
                    while (remainingPressed) {
                        val tail = awaitPointerEvent(PointerEventPass.Initial)
                        tail.changes.forEach { it.consume() }
                        remainingPressed = tail.changes.any { it.pressed }
                    }
                    break
                }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

private fun pointerDistance(first: PointerInputChange, second: PointerInputChange): Float =
    distance(first.position, second.position)

private fun distance(first: Offset, second: Offset): Float =
    hypot(second.x - first.x, second.y - first.y)



data class PinchZoomItemRect(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
)

data class PinchZoomItemMorph(
    val translationXPx: Float = 0f,
    val translationYPx: Float = 0f,
    /** Slot size owned by the current LazyGrid world; kept stable so grid geometry does not feed back. */
    val baseWidthPx: Float? = null,
    val baseHeightPx: Float? = null,
    /** Interpolated container bounds. Child content is remeasured into these bounds, never texture-scaled. */
    val contentWidthPx: Float? = null,
    val contentHeightPx: Float? = null,
    val alpha: Float = 1f,
)

/**
 * Visible geometry keyed by the Lazy item stable key. This mirrors Poweramp's separation between
 * data identity and layout geometry: list/grid worlds can use different columns while the same
 * media entity is still paired by identity.
 */
fun LazyGridState.visiblePinchZoomItemRects(): Map<Any, PinchZoomItemRect> =
    layoutInfo.visibleItemsInfo.associate { item ->
        item.key to PinchZoomItemRect(
            leftPx = item.offset.x.toFloat(),
            topPx = item.offset.y.toFloat(),
            widthPx = item.size.width.toFloat(),
            heightPx = item.size.height.toFloat(),
        )
    }

internal fun calculatePinchZoomItemMorph(
    current: PinchZoomItemRect?,
    counterpart: PinchZoomItemRect?,
    progress: Float,
    fromLower: Boolean,
    transitionActive: Boolean,
): PinchZoomItemMorph {
    if (!transitionActive) return PinchZoomItemMorph()
    val p = progress.coerceIn(0f, 1f)
    val styleProgress = PinchZoomMath.contentCrossfadeProgress(p)
    val alpha = if (fromLower) 1f - styleProgress else styleProgress
    val base = current ?: return PinchZoomItemMorph(alpha = alpha)
    val other = counterpart ?: return PinchZoomItemMorph(alpha = alpha)

    val lower = if (fromLower) base else other
    val upper = if (fromLower) other else base
    val targetLeft = lerpFloat(lower.leftPx, upper.leftPx, p)
    val targetTop = lerpFloat(lower.topPx, upper.topPx, p)
    val targetWidth = lerpFloat(lower.widthPx, upper.widthPx, p).coerceAtLeast(1f)
    val targetHeight = lerpFloat(lower.heightPx, upper.heightPx, p).coerceAtLeast(1f)

    return PinchZoomItemMorph(
        translationXPx = targetLeft - base.leftPx,
        translationYPx = targetTop - base.topPx,
        baseWidthPx = base.widthPx.coerceAtLeast(1f),
        baseHeightPx = base.heightPx.coerceAtLeast(1f),
        contentWidthPx = targetWidth,
        contentHeightPx = targetHeight,
        alpha = alpha,
    )
}

/**
 * Moves the item container between source/target rectangles without scaling its rendered pixels.
 *
 * The LazyGrid still sees the current world's original slot size, so its layout remains a stable
 * geometry oracle. Only the child is remeasured to the interpolated bounds and placed at the
 * interpolated offset. Covers keep their aspect ratio and text is laid out again at the new width.
 */
fun Modifier.pinchZoomItemBoundsMorph(morph: PinchZoomItemMorph): Modifier =
    this.layout { measurable, constraints ->
        val baseWidth = morph.baseWidthPx?.roundToInt()?.coerceAtLeast(1)
        val baseHeight = morph.baseHeightPx?.roundToInt()?.coerceAtLeast(1)
        val contentWidth = morph.contentWidthPx?.roundToInt()?.coerceAtLeast(1)
        val contentHeight = morph.contentHeightPx?.roundToInt()?.coerceAtLeast(1)

        if (baseWidth == null || baseHeight == null || contentWidth == null || contentHeight == null) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeWithLayer(0, 0) { alpha = morph.alpha.coerceIn(0f, 1f) }
            }
        } else {
            val placeable = measurable.measure(Constraints.fixed(contentWidth, contentHeight))
            val slotWidth = constrainDimension(baseWidth, constraints.minWidth, constraints.maxWidth)
            val slotHeight = constrainDimension(baseHeight, constraints.minHeight, constraints.maxHeight)
            layout(slotWidth, slotHeight) {
                placeable.placeWithLayer(
                    morph.translationXPx.roundToInt(),
                    morph.translationYPx.roundToInt(),
                ) {
                    alpha = morph.alpha.coerceIn(0f, 1f)
                    clip = false
                }
            }
        }
    }

private fun constrainDimension(value: Int, min: Int, max: Int): Int {
    val atLeastMin = value.coerceAtLeast(min)
    return if (max == Constraints.Infinity) atLeastMin else atLeastMin.coerceAtMost(max)
}

private fun lerpFloat(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction


data class PinchZoomGridAnchor(
    val itemIndex: Int,
    /** Item center relative to viewport center, in px. */
    val centerDeltaPx: Int,
)

/**
 * Owns the reusable anchor lifecycle shared by the library pinch-zoom hosts.
 *
 * Rendering strategy, preset persistence and external list/grid synchronization intentionally stay
 * with each host. This object only answers one question: which preset worlds have been aligned to
 * the current visible anchor for the active gesture/external transition?
 */
@Stable
internal class PinchZoomGridAnchorCoordinator internal constructor(
    private val states: List<LazyGridState>,
    initialAlignedIndex: Int,
) {
    var anchor by mutableStateOf<PinchZoomGridAnchor?>(null)
        private set

    private var alignedPresetIndices by mutableStateOf(setOf(initialAlignedIndex))

    fun beginGesture(sourceIndex: Int) {
        if (sourceIndex !in states.indices) return
        alignedPresetIndices = setOf(sourceIndex)
        anchor = states[sourceIndex].capturePinchZoomAnchor()
    }

    suspend fun alignPresetPair(firstIndex: Int, secondIndex: Int) {
        val currentAnchor = anchor ?: return
        setOf(firstIndex, secondIndex).forEach { index ->
            if (index in states.indices && index !in alignedPresetIndices) {
                states[index].restorePinchZoomAnchor(currentAnchor)
                alignedPresetIndices = alignedPresetIndices + index
            }
        }
    }

    suspend fun alignExternalPreset(sourceIndex: Int, targetIndex: Int) {
        if (sourceIndex !in states.indices || targetIndex !in states.indices) return
        if (targetIndex in alignedPresetIndices) return
        val sourceAnchor = states[sourceIndex].capturePinchZoomAnchor() ?: return
        anchor = sourceAnchor
        states[targetIndex].restorePinchZoomAnchor(sourceAnchor)
        alignedPresetIndices = alignedPresetIndices + targetIndex
    }

    fun resetTo(index: Int) {
        if (index !in states.indices) return
        anchor = null
        alignedPresetIndices = setOf(index)
    }
}

@Composable
internal fun rememberPinchZoomGridAnchorCoordinator(
    states: List<LazyGridState>,
    initialAlignedIndex: Int,
    stateKey: Any? = Unit,
): PinchZoomGridAnchorCoordinator {
    require(states.isNotEmpty()) { "states must not be empty" }
    return remember(stateKey, states.size) {
        PinchZoomGridAnchorCoordinator(
            states = states,
            initialAlignedIndex = initialAlignedIndex.coerceIn(states.indices),
        )
    }
}

fun LazyGridState.capturePinchZoomAnchor(): PinchZoomGridAnchor? {
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return null
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val item = info.visibleItemsInfo.minByOrNull { visible ->
        abs((visible.offset.y + visible.size.height / 2) - viewportCenter)
    } ?: return null
    return PinchZoomGridAnchor(
        itemIndex = item.index,
        centerDeltaPx = (item.offset.y + item.size.height / 2) - viewportCenter,
    )
}

suspend fun LazyGridState.restorePinchZoomAnchor(anchor: PinchZoomGridAnchor) {
    scrollToItem(anchor.itemIndex.coerceAtLeast(0))
    withFrameNanos { }
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == anchor.itemIndex } ?: return
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val currentCenter = item.offset.y + item.size.height / 2
    val desiredCenter = viewportCenter + anchor.centerDeltaPx
    scrollBy((currentCenter - desiredCenter).toFloat())
}
