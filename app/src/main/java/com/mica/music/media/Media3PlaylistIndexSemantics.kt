package com.mica.music.media

/**
 * Media3 1.9.0 playlist current-index result for [androidx.media3.common.Player.removeMediaItems]
 * under REPEAT_OFF and shuffle-off.
 *
 * Matches `ExoPlayerImpl.getPeriodPositionUsAfterTimelineChanged`: keep the current window when
 * its period still exists; otherwise take the first subsequent surviving window; otherwise fall
 * back to `Timeline.getFirstWindowIndex` (index 0). Removing the current last item therefore
 * selects index 0, not the last remaining survivor.
 */
internal object Media3PlaylistIndexSemantics {
    fun currentIndexAfterRemove(
        queueSize: Int,
        currentIndex: Int,
        fromIndex: Int,
        effectiveToIndex: Int,
    ): Int {
        val remaining = queueSize - (effectiveToIndex - fromIndex)
        if (remaining <= 0) return 0
        val removedCurrent = currentIndex in fromIndex until effectiveToIndex
        return when {
            removedCurrent && effectiveToIndex < queueSize -> fromIndex
            removedCurrent -> 0
            currentIndex >= effectiveToIndex -> currentIndex - (effectiveToIndex - fromIndex)
            currentIndex >= 0 -> currentIndex
            else -> 0
        }.coerceIn(0, remaining - 1)
    }
}
