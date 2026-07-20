package com.mica.music.media

import com.mica.music.data.LyricsBoundaryClock

/** Pure playback-position planner for notification lyric updates. */
internal object NotificationLyricsBoundaryPlanner {
    data class Plan(
        val publishIndex: Int?,
        val wakeInMs: Long?,
    )

    fun plan(
        lineStartTimesMs: IntArray,
        positionMs: Long,
        playbackSpeed: Float,
        isAdvancing: Boolean,
        publishedIndex: Int?,
        nowRealtimeMs: Long,
        lastPublishedRealtimeMs: Long?,
    ): Plan {
        val boundaryPlan = LyricsBoundaryClock.plan(
            lineStartTimesMs = lineStartTimesMs,
            positionMs = positionMs,
            playbackSpeed = playbackSpeed,
            isAdvancing = isAdvancing,
        )
        val activeIndex = boundaryPlan.activeIndex
        val boundaryWakeInMs = boundaryPlan.wakeInMs
        val pendingPublish = activeIndex >= 0 && activeIndex != publishedIndex
        val publishCooldownMs = lastPublishedRealtimeMs
            ?.let { (MIN_PUBLISH_INTERVAL_MS - (nowRealtimeMs - it)).coerceAtLeast(0L) }
            ?: 0L
        val publishIndex = activeIndex.takeIf { pendingPublish && publishCooldownMs == 0L }
        val wakeInMs = when {
            pendingPublish && publishCooldownMs > 0L -> publishCooldownMs
            boundaryWakeInMs != null && publishCooldownMs > 0L ->
                maxOf(boundaryWakeInMs, publishCooldownMs)
            else -> boundaryWakeInMs
        }
        return Plan(
            publishIndex = publishIndex,
            wakeInMs = wakeInMs,
        )
    }

    const val MIN_PUBLISH_INTERVAL_MS = 250L
}
