package com.mica.music.data

internal object PlaybackQueueNavigator {

    fun nextIndex(
        mode: PlaybackQueueMode,
        currentIndex: Int,
        queueSize: Int,
        manualSkip: Boolean,
        randomIndex: (Int) -> Int,
    ): Int {
        if (queueSize <= 0) return 0
        val current = currentIndex.coerceIn(0, queueSize - 1)
        val last = queueSize - 1
        return when (mode) {
            PlaybackQueueMode.REPEAT_ONE ->
                if (manualSkip) wrapNext(current, last) else current
            PlaybackQueueMode.SHUFFLE ->
                randomIndex(current).coerceIn(0, last)
            PlaybackQueueMode.REPEAT_ALL ->
                wrapNext(current, last)
            PlaybackQueueMode.OFF ->
                if (manualSkip) wrapNext(current, last)
                else if (current < last) current + 1
                else current
        }
    }

    fun previousIndex(
        mode: PlaybackQueueMode,
        currentIndex: Int,
        queueSize: Int,
        randomIndex: (Int) -> Int,
    ): Int {
        if (queueSize <= 0) return 0
        val current = currentIndex.coerceIn(0, queueSize - 1)
        val last = queueSize - 1
        return when (mode) {
            PlaybackQueueMode.SHUFFLE -> randomIndex(current).coerceIn(0, last)
            else -> if (current > 0) current - 1 else last
        }
    }

    private fun wrapNext(current: Int, last: Int): Int =
        if (current < last) current + 1 else 0
}
