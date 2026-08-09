package com.mica.music.media

import java.util.concurrent.atomic.AtomicReference

internal enum class DebugPlaybackControl {
    PLAY,
    PAUSE,
    NEXT,
    SELECT_INDEX,
    SEEK_NEAR_END,
    REPEAT_ONE,
    REPEAT_OFF,
}

internal data class DebugPlaybackControlResult(
    val currentIndex: Int,
    val currentPositionMs: Long,
    val durationMs: Long,
)

/** Process-local ADB harness seam. Release builds install no external caller. */
internal object DebugPlaybackControlRuntime {
    private val requester = AtomicReference<
        ((DebugPlaybackControl, Int) -> DebugPlaybackControlResult)?
    >(null)

    fun install(request: (DebugPlaybackControl, Int) -> DebugPlaybackControlResult) {
        requester.set(request)
    }

    fun clear() {
        requester.set(null)
    }

    fun request(control: DebugPlaybackControl, mediaIndex: Int = -1): DebugPlaybackControlResult? =
        requester.get()?.invoke(control, mediaIndex)
}
