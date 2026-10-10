package com.mica.music.ui.screens

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
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

        fun cancel() {
            animation?.cancel()
            animation = null
        }
    }

    private var lastGeometry = emptyMap<String, OwnedLyricGeometry>()
    private var totalScrollTravelPx = 0f
    private var sampledScrollTravelPx = 0f
    private val visibleRows = mutableStateMapOf<String, RowMotion>()

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

    /**
     * One measured layout snapshot = one authority commit. Stale rows are retired
     * as soon as LazyColumn recycles them, so no detached row can animate down.
     */
    fun accept(
        measured: List<OwnedLyricGeometry>,
        animate: Boolean,
        stiffness: Float,
        scrolling: Boolean,
    ) {
        val scrollTravel = totalScrollTravelPx - sampledScrollTravelPx
        val next = measured.associateBy { it.key }
        val stale = visibleRows.keys.filter { it !in next }
        stale.forEach { key ->
            visibleRows.remove(key)?.cancel()
        }
        measured.forEach { after ->
            val before = lastGeometry[after.key]
            val row = visibleRows.getOrPut(after.key) { RowMotion() }
            if (before != null && animate) {
                val observedMove = (after.offsetPx - before.offsetPx).toFloat()
                // When a scroll bypasses nested dispatch (e.g. scrollToItem), skip
                // compensation rather than mistake scrolling for remeasurement.
                val untrackedScroll = scrolling && abs(scrollTravel) < 0.5f &&
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
                    row.animation = scope.launch {
                        val curve = TargetBasedAnimation(
                            animationSpec = spring<Float>(
                                stiffness = stiffness,
                                dampingRatio = 1.16f,
                            ),
                            typeConverter = Float.VectorConverter,
                            initialValue = continuation,
                            targetValue = 0f,
                        )
                        val start = withFrameNanos { it }
                        while (true) {
                            val elapsed = (withFrameNanos { it } - start).coerceAtLeast(0L)
                            row.translationPx = curve.getValueFromNanos(
                                elapsed.coerceAtMost(curve.durationNanos),
                            )
                            if (elapsed >= curve.durationNanos) break
                        }
                        row.translationPx = 0f
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

    /** Hard seek / new document / viewport reset: never carry an obsolete spring. */
    fun reset() {
        visibleRows.values.forEach { it.cancel() }
        visibleRows.clear()
        lastGeometry = emptyMap()
        sampledScrollTravelPx = totalScrollTravelPx
    }

    fun dispose() = reset()
}
