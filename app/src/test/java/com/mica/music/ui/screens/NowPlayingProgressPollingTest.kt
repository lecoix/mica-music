package com.mica.music.ui.screens

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingProgressPollingTest {

    @Test
    fun activePollingSyncsImmediatelyThenEveryHalfSecond() = runTest {
        var syncCount = 0
        val job = launch {
            pollNowPlayingProgress(isPlaying = true) { syncCount += 1 }
        }

        runCurrent()
        assertEquals(1, syncCount)
        advanceTimeBy(499)
        runCurrent()
        assertEquals(1, syncCount)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, syncCount)

        job.cancelAndJoin()
    }

    @Test
    fun pausedPollingOnlyPerformsImmediateSync() = runTest {
        var syncCount = 0

        pollNowPlayingProgress(isPlaying = false) { syncCount += 1 }
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, syncCount)
    }

    @Test
    fun wordSyncedLyricsUseFastPollingOnlyWhenNeeded() {
        assertEquals(50L, nowPlayingProgressPollIntervalMs(hasWordSyncedLyrics = true))
        assertEquals(500L, nowPlayingProgressPollIntervalMs(hasWordSyncedLyrics = false))
    }
}
