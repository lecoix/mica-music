package com.mica.music.ui.screens

/**
 * Optional real ExpandedLyricsPanel geometry probe, emitted only for explicit tests.
 * measuredViewportYPx is in LazyList's padding-shifted item-offset space;
 * rootGeometryYPx / visualRootYPx are physical Compose root pixels.
 * Scroll totals are consumed list.scrollBy distances (positive = content rises).
 */
internal data class ExpandedLyricsMotionTrace(
    val frameNanos: Long,
    val positionMs: Int,
    val playing: Boolean,
    val interludeKey: String?,
    val event: String,
    val followConsumedPx: Float,
    val interludeConsumedPx: Float,
    val listRootYPx: Float,
    val firstVisibleIndex: Int,
    val firstVisibleScrollOffset: Int,
    val items: List<ExpandedLyricsRowTrace>,
    val scrollMode: String = "UNKNOWN",
    val scrollInProgress: Boolean = false,
    val followMode: String = "none",
    val followDeltaPx: Float = Float.NaN,
    val followTargetIndex: Int = -1,
    val viewportStartOffsetPx: Int = 0,
    val viewportEndOffsetPx: Int = 0,
    val viewportPhysicalHeightPx: Int = 0,
    val afterContentPaddingPx: Int = -1,
    val beforeContentPaddingPx: Int = -1,
)

internal data class ExpandedLyricsRowTrace(
    val key: String,
    val index: Int,
    val measuredViewportYPx: Int,
    val measuredHeightPx: Int,
    val rootGeometryYPx: Float,
    val ownedTranslationPx: Float,
    val staggerTranslationPx: Float,
) {
    val visualRootYPx: Float get() = rootGeometryYPx + ownedTranslationPx + staggerTranslationPx
}

/**
 * Compare a SAME-KEY next-line layout receipt across the interlude insertion.
 * List.scrollBy(+x) moves a row's placement -x, therefore +follow consumption
 * removes natural follow travel from the raw geometry difference.
 *
 * A keyed LazyColumn may already have re-anchored its visible row. In that
 * case the measured positive shift is zero and we must not scroll again.
 */
internal fun expandedLyricsMeasuredInterludeCorrectionPx(
    beforeItemOffsetPx: Int,
    afterItemOffsetPx: Int,
    followScrollSinceBeforePx: Float,
    maxInsertionHeightPx: Float,
): Float =
    (afterItemOffsetPx - beforeItemOffsetPx + followScrollSinceBeforePx)
        .coerceIn(0f, maxInsertionHeightPx.coerceAtLeast(0f))

/** Pre-insertion committed item layout + visual Y (same Lazy viewport). */
internal data class ExpandedLyricsInterludeReceipt(
    val itemOffsetPx: Int,
    val visualViewportYPx: Float,
    val followConsumedPx: Float,
)

/** Cumulative, shared only with the optional telemetry probe. */
internal class ExpandedLyricsScrollLedger {
    var followPx: Float = 0f
    var interludePx: Float = 0f
    var followMode: String = "none"
    var followDeltaPx: Float = Float.NaN
    var followTargetIndex: Int = -1
}
