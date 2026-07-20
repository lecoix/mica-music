package com.mica.music.data

import kotlin.math.ceil

/** Pure mapping between playback time and line-level lyric boundaries. */
internal object LyricsBoundaryClock {
    data class Plan(
        val activeIndex: Int,
        val wakeInMs: Long?,
    )

    fun plan(
        lineStartTimesMs: IntArray,
        positionMs: Long,
        playbackSpeed: Float,
        isAdvancing: Boolean,
    ): Plan {
        val activeIndex = activeIndex(lineStartTimesMs, positionMs)
        val nextBoundaryPositionMs = lineStartTimesMs
            .asSequence()
            .map { it.toLong() - LyricsSync.LEAD_MS }
            .firstOrNull { it > positionMs }
        val wakeInMs = if (isAdvancing && playbackSpeed > 0f && nextBoundaryPositionMs != null) {
            ceil((nextBoundaryPositionMs - positionMs) / playbackSpeed.toDouble())
                .toLong()
                .coerceAtLeast(1L)
        } else {
            null
        }
        return Plan(activeIndex = activeIndex, wakeInMs = wakeInMs)
    }

    private fun activeIndex(lineStartTimesMs: IntArray, positionMs: Long): Int {
        if (lineStartTimesMs.isEmpty() || lineStartTimesMs.none { it > 0 }) return -1
        val effectivePositionMs = positionMs + LyricsSync.LEAD_MS
        var index = 0
        for (candidate in lineStartTimesMs.indices) {
            if (lineStartTimesMs[candidate] <= effectivePositionMs) {
                index = candidate
            } else {
                break
            }
        }
        return index
    }
}
