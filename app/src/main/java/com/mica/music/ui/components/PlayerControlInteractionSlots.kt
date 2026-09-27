package com.mica.music.ui.components

internal data class PlayerControlInteractionSlot(
    val startDp: Float,
    val endDp: Float,
) {
    val widthDp: Float
        get() = endDp - startDp

    val centerDp: Float
        get() = (startDp + endDp) / 2f
}

/**
 * Partitions the whole available control width at the visual-center midpoints.
 *
 * Returning null means a single row cannot preserve the minimum target width and the caller
 * must reflow instead of overlapping hit regions.
 */
internal fun equalPlayerControlInteractionSlots(
    availableWidthDp: Float,
    count: Int = 5,
    minimumTouchTargetDp: Float = 48f,
): List<PlayerControlInteractionSlot>? {
    if (count <= 0 || availableWidthDp <= 0f || minimumTouchTargetDp <= 0f) return null
    val slotWidth = availableWidthDp / count
    if (slotWidth + 0.001f < minimumTouchTargetDp) return null
    return List(count) { index ->
        PlayerControlInteractionSlot(
            startDp = slotWidth * index,
            endDp = slotWidth * (index + 1),
        )
    }
}
