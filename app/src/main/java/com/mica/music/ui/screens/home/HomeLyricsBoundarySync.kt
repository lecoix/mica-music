package com.mica.music.ui.screens.home

import com.mica.music.data.LyricsBoundaryClock
import kotlinx.coroutines.delay

internal suspend fun awaitNextHomeLyricBoundary(
    lineStartTimesMs: IntArray,
    positionMs: Int,
    playbackSpeed: Float,
    isAdvancing: Boolean,
    syncPosition: () -> Unit,
    effectiveOffsetMs: Int = 0,
) {
    val wakeInMs = LyricsBoundaryClock.plan(
        lineStartTimesMs = lineStartTimesMs,
        positionMs = positionMs.toLong(),
        playbackSpeed = playbackSpeed,
        isAdvancing = isAdvancing,
        effectiveOffsetMs = effectiveOffsetMs,
    ).wakeInMs ?: return
    delay(wakeInMs)
    syncPosition()
}
