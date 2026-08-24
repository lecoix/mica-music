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
    maxPollIntervalMs: Long = 500L,
) {
    if (!isAdvancing) return
    val boundaryWakeInMs = LyricsBoundaryClock.plan(
        lineStartTimesMs = lineStartTimesMs,
        positionMs = positionMs.toLong(),
        playbackSpeed = playbackSpeed,
        isAdvancing = isAdvancing,
        effectiveOffsetMs = effectiveOffsetMs,
    ).wakeInMs
    val wakeInMs = minOf(
        boundaryWakeInMs ?: Long.MAX_VALUE,
        maxPollIntervalMs.coerceAtLeast(50L),
    )
    delay(wakeInMs)
    syncPosition()
}
