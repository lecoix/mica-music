package com.mica.music.ui.zoom

import kotlin.math.ceil

/**
 * Converts a scene's visible text width back into the unscaled width used to measure text before
 * the child layer applies [scale]. The measured child may be wider than its parent because scaling
 * it down is what makes the final visible line box match [visibleWidthPx].
 */
internal fun compensatedTextMeasureWidth(
    visibleWidthPx: Float,
    scale: Float,
): Int {
    val safeScale = scale.coerceAtLeast(0.1f)
    return ceil(visibleWidthPx.coerceAtLeast(1f) / safeScale)
        .toInt()
        .coerceAtLeast(1)
}
