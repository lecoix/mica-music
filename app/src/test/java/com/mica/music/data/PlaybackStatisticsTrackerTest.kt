package com.mica.music.data

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatisticsTrackerTest {
    @Test
    fun playStartRequiresArmedMatchingPlaybackAndPublishesOnce() {
        val tracker = tracker()
        tracker.reset("song-a")
        tracker.requestPlayback("song-b")

        assertTrue(tracker.onTransition("song-b", Player.MEDIA_ITEM_TRANSITION_REASON_SEEK))
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
        assertNull(tracker.publishPlayStartedIfReady("song-b", playing = false))
        assertEquals("song-b", tracker.publishPlayStartedIfReady("song-b", playing = true))
        assertNull(tracker.publishPlayStartedIfReady("song-b", playing = true))
    }

    @Test
    fun repeatCountsSameSongButAutoAndPlaylistRefreshDoNot() {
        val tracker = tracker()
        tracker.reset("song-a")

        assertFalse(tracker.onTransition("song-a", Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
        assertFalse(
            tracker.onTransition("song-a", Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED),
        )
        assertTrue(tracker.onTransition("song-a", Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT))
    }

    @Test
    fun listeningPublishesWholeSecondsWithoutRestartingSameSession() {
        var nowMs = 1_000L
        val listened = mutableListOf<Pair<String, Long>>()
        val tracker = tracker(
            nowMs = { nowMs },
            onListenSecondsAdded = { songId, seconds -> listened += songId to seconds },
        )

        tracker.observePlayback("song-a", playing = true)
        nowMs += 5_000L
        tracker.observePlayback("song-a", playing = true)
        nowMs += 60_400L
        tracker.observePlayback("song-b", playing = true)
        nowMs -= 1_000L
        tracker.observePlayback("song-b", playing = false)

        assertEquals(listOf("song-a" to 65L), listened)
    }

    private fun tracker(
        nowMs: () -> Long = { 0L },
        onListenSecondsAdded: (String, Long) -> Unit = { _, _ -> },
    ) = PlaybackStatisticsTracker(
        monotonicNowMs = nowMs,
        onListenSecondsAdded = onListenSecondsAdded,
    )
}
