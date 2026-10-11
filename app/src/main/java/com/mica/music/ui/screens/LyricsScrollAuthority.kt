package com.mica.music.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * B3 v1: one owner for who may request LazyColumn scrolling.
 *
 * Motion, interlude dots and row decoration are not scroll authorities.  All
 * asynchronous requests carry [generation], so an old follow/return-to-live
 * cannot steal a newer gesture or explicit seek.
 *
 * This is local UI state, never player time or persisted lyrics data.
 */
internal enum class LyricsScrollMode {
    FOLLOWING, BROWSING_DRAG, BROWSING_FLING, BROWSING_HOLD, RECOVERING,
}

internal class LyricsScrollAuthority {
    var mode: LyricsScrollMode by mutableStateOf(LyricsScrollMode.FOLLOWING)
        private set
    var generation by mutableIntStateOf(0)
        private set
    var returnToCurrentRevision by mutableIntStateOf(0)
        private set

    val browsing: Boolean get() = mode != LyricsScrollMode.FOLLOWING &&
        mode != LyricsScrollMode.RECOVERING

    val mayFollow: Boolean get() = !browsing

    fun beginGesture() {
        mode = LyricsScrollMode.BROWSING_DRAG
        generation++
    }

    fun handOffToFling(): Int {
        if (mode == LyricsScrollMode.BROWSING_DRAG) {
            mode = LyricsScrollMode.BROWSING_FLING
        }
        return generation
    }

    fun settleGesture(token: Int): Boolean {
        if (token != generation ||
            mode != LyricsScrollMode.BROWSING_DRAG &&
            mode != LyricsScrollMode.BROWSING_FLING
        ) return false
        mode = LyricsScrollMode.BROWSING_HOLD
        return true
    }

    fun returnToCurrent(token: Int): Boolean {
        if (token != generation || mode != LyricsScrollMode.BROWSING_HOLD) return false
        mode = LyricsScrollMode.RECOVERING
        returnToCurrentRevision++
        return true
    }

    fun followSettled() {
        if (mode == LyricsScrollMode.RECOVERING) mode = LyricsScrollMode.FOLLOWING
    }

    /** Explicit seek or document replacement always outranks a browse. */
    fun resetToFollowing() {
        generation++
        mode = LyricsScrollMode.FOLLOWING
        returnToCurrentRevision++
    }

    /** Reject completion from a stale generation after another gesture or seek. */
    fun isCurrent(token: Int): Boolean = token == generation
}
