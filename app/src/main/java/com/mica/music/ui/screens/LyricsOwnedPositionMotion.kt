package com.mica.music.ui.screens

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A visible row's authority geometry in LazyColumn placement coordinates.
 * Never mix this offset with positionInRoot or firstVisibleItemScrollOffset.
 */
internal data class OwnedLyricGeometry(
    val key: String,
    val offsetPx: Int,
    val heightPx: Int,
)

/**
 * FLIP residual: layout owns final placement; scroll already moved the real row.
 * Carry only the difference caused by remeasurement / insertion.
 *
 * Screen scroll travel has the same sign as a row's on-screen movement.
 */
internal fun ownedLyricStructuralDelta(
    before: OwnedLyricGeometry,
    after: OwnedLyricGeometry,
    screenScrollTravelPx: Float,
): Float {
    require(before.key == after.key) { "Cannot animate across lyric identities" }
    return after.offsetPx - before.offsetPx - screenScrollTravelPx
}

internal fun ownedLyricContinuationPx(
    previousVisualTranslationPx: Float,
    structuralDeltaPx: Float,
): Float = previousVisualTranslationPx - structuralDeltaPx

/**
 * The sole owner of structural position animation for expanded lyrics.
 * LazyColumn owns layout, scroll, clipping and virtualization. This layer does not
 * use animateItem, does not invent offscreen moving-away targets, and never changes
 * the measured size or scroll position.
 */
internal class OwnedLyricsPositionMotion(
    private val scope: CoroutineScope,
) {
    private class RowMotion {
        var translationPx by mutableFloatStateOf(0f)
        var animation: Job? = null
        var revision: Long = 0L
        var followCompensation: FollowCompensation? = null

        fun cancel() {
            revision++
            animation?.cancel()
            animation = null
            followCompensation = null
        }
    }

    private var lastGeometry = emptyMap<String, OwnedLyricGeometry>()
    private var totalScrollTravelPx = 0f
    private var sampledScrollTravelPx = 0f
    private val visibleRows = mutableStateMapOf<String, RowMotion>()
    private val pinnedKeys = mutableSetOf<String>()
    private var committedLayout: LazyListLayoutInfo? = null
    private var committedAnimate = false
    private data class FollowFrame(val playTimeNanos: Long, val delays: Map<String, Long>)
    private data class FollowCompensation(
        val curve: TargetBasedAnimation<Float, AnimationVector1D>,
        val multiplier: Float,
        val delayNanos: Long,
        val stiffness: Float,
    )
    private var followFrame: FollowFrame? = null
    private var followRevision = 0L

    fun beginFollow(): Long {
        clearFollowFrame(followRevision)
        return ++followRevision
    }

    fun recordFollowFrame(token: Long, playTimeNanos: Long, delays: Map<String, Long>): Boolean {
        if (token != followRevision) return false
        followFrame = FollowFrame(playTimeNanos, delays)
        visibleRows.values.forEach { row ->
            row.followCompensation?.let { compensation ->
                val time = (playTimeNanos - compensation.delayNanos).coerceAtLeast(0L)
                row.translationPx = compensation.curve.getValueFromNanos(
                    time.coerceAtMost(compensation.curve.durationNanos),
                ) * compensation.multiplier
            }
        }
        return true
    }

    fun clearFollowFrame(token: Long): Boolean {
        if (token != followRevision) return false
        followRevision++
        followFrame = null
        // A user drag may interrupt follow. Continue any remaining compensation
        // from its current value instead of dropping it at the cancellation boundary.
        visibleRows.values.forEach { row ->
            row.followCompensation?.let { startSpring(row, row.translationPx, it.stiffness) }
        }
        return true
    }

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            // Post-consumed reports actual physical motion, not the requested delta.
            // Do not record it again from animateScrollBy / scrollBy.
            totalScrollTravelPx += consumed.y
            return Offset.Zero
        }
    }

    fun translationFor(key: String): Float = visibleRows[key]?.translationPx ?: 0f

    /** Interlude-only handoff: keep the previous next-line visual Y while the
     * real list consumes exactly the measured insertion scroll. A pinned row's
     * ordinary FLIP spring is cancelled rather than competing with the scroll.
     */
    fun pin(key: String, translationPx: Float) {
        val row = visibleRows.getOrPut(key) { RowMotion() }
        pinnedKeys.add(key)
        row.cancel()
        row.translationPx = translationPx
    }

    fun releasePins() {
        pinnedKeys.clear()
    }

    /**
     * Called from row placement BEFORE its layer is drawn, with the current list receipt.
     * Multiple rows and the asynchronous observer share one commit; observing the same
     * layout again must not restart springs or account for scroll a second time.
     * Reset retires this receipt along with the row animations. Main/UI thread only.
     */
    fun acceptLayout(
        layout: LazyListLayoutInfo,
        animate: Boolean,
        stiffness: Float,
        scrolling: Boolean,
    ) {
        if (committedLayout === layout && committedAnimate == animate) return
        acceptMeasured(
            layout.visibleItemsInfo.map { OwnedLyricGeometry(it.key.toString(), it.offset, it.size) },
            animate, stiffness, scrolling,
            structureChanged = committedLayout?.let { it.totalItemsCount != layout.totalItemsCount } == true,
        )
        committedLayout = layout
        committedAnimate = animate
    }

    /**
     * One measured layout snapshot = one authority commit. Stale rows are retired
     * as soon as LazyColumn recycles them, so no detached row can animate down.
     */
    fun accept(
        measured: List<OwnedLyricGeometry>,
        animate: Boolean,
        stiffness: Float,
        scrolling: Boolean,
    ) = acceptMeasured(measured, animate, stiffness, scrolling, structureChanged = false)

    private fun acceptMeasured(
        measured: List<OwnedLyricGeometry>,
        animate: Boolean,
        stiffness: Float,
        scrolling: Boolean,
        structureChanged: Boolean,
    ) {
        val scrollTravel = totalScrollTravelPx - sampledScrollTravelPx
        val next = measured.associateBy { it.key }
        val stale = visibleRows.keys.filter { it !in next }
        stale.forEach { key ->
            visibleRows.remove(key)?.cancel()
            pinnedKeys.remove(key)
        }
        measured.forEach { after ->
            val before = lastGeometry[after.key]
            val row = visibleRows.getOrPut(after.key) { RowMotion() }
            if (after.key in pinnedKeys) {
                // The interlude bridge holds this key; no concurrent FLIP spring.
            } else if (before != null && animate) {
                val observedMove = (after.offsetPx - before.offsetPx).toFloat()
                // When a scroll bypasses nested dispatch (e.g. scrollToItem), skip
                // compensation rather than mistake scrolling for remeasurement.
                // Item insertion/removal is a real structural change even mid-scroll.
                val untrackedScroll = !structureChanged && scrolling && abs(scrollTravel) < 0.5f &&
                    abs(observedMove) > 0.5f
                // In an interlude insertion, the compensated NEXT row is already
                // visually stationary (raw delta ~= 0). Do not undo that anchor.
                val structural = if (untrackedScroll || abs(observedMove) < 0.75f) 0f else {
                    ownedLyricStructuralDelta(before, after, scrollTravel)
                }
                if (abs(structural) > 0.75f) {
                    val continuation = ownedLyricContinuationPx(row.translationPx, structural)
                    row.cancel()
                    row.translationPx = continuation
                    // Apply removal compensation in the SAME follow frame as scroll,
                    // with the same phase, instead of adding another frame coroutine.
                    val follow = followFrame?.takeIf { structureChanged && scrolling }
                    val curve = positionCurve(continuation, stiffness)
                    val delay = follow?.delays?.get(after.key) ?: 0L
                    val phase = ((follow?.playTimeNanos ?: 0L) - delay).coerceIn(0L, curve.durationNanos)
                    val phaseValue = curve.getValueFromNanos(phase)
                    if (follow != null && abs(phaseValue) > 0.001f) {
                        row.followCompensation = FollowCompensation(curve, continuation / phaseValue, delay, stiffness)
                    } else {
                        startSpring(row, continuation, stiffness)
                    }
                }
            } else if (!animate) {
                row.cancel()
                row.translationPx = 0f
            }
        }
        lastGeometry = next
        sampledScrollTravelPx = totalScrollTravelPx
    }

    private fun positionCurve(value: Float, stiffness: Float) = TargetBasedAnimation(
        animationSpec = spring<Float>(stiffness = stiffness, dampingRatio = 1.16f),
        typeConverter = Float.VectorConverter,
        initialValue = value,
        targetValue = 0f,
    )

    private fun startSpring(row: RowMotion, value: Float, stiffness: Float) {
        row.cancel()
        row.translationPx = value
        if (abs(value) < 0.001f) return
        val revision = row.revision
        row.animation = scope.launch {
            val curve = positionCurve(value, stiffness)
            val start = withFrameNanos { it }
            if (row.revision != revision) return@launch
            while (true) {
                val elapsed = (withFrameNanos { it } - start).coerceAtLeast(0L)
                if (row.revision != revision) return@launch
                row.translationPx = curve.getValueFromNanos(elapsed.coerceAtMost(curve.durationNanos))
                if (elapsed >= curve.durationNanos) break
            }
            row.translationPx = 0f
        }
    }

    /** Hard seek / new document / viewport reset: never carry an obsolete spring. */
    fun reset() {
        visibleRows.values.forEach { it.cancel() }
        visibleRows.clear()
        pinnedKeys.clear()
        lastGeometry = emptyMap()
        committedLayout = null
        followFrame = null
        followRevision++
        sampledScrollTravelPx = totalScrollTravelPx
    }

    fun dispose() = reset()
}
