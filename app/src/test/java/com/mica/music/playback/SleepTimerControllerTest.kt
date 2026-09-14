package com.mica.music.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {

    @Test
    fun cancelRestoresVolumeCapturedBeforeTimer() = runTest {
        val player = mockk<PlayerController>(relaxed = true)
        every { player.trackTransitionEvents } returns MutableSharedFlow(extraBufferCapacity = 1)
        every { player.playbackVolume } returns 0.35f
        val timer = SleepTimerController(this, player, context())

        timer.start(1)
        timer.cancel()

        assertFalse(timer.isActive)
        verify(exactly = 1) { player.setPlaybackVolume(0.35f) }
    }

    @Test
    fun expiryPausesAndRestoresBaselineAndEmitsEvent() = runTest {
        var now = 0L
        val player = mockk<PlayerController>(relaxed = true)
        every { player.trackTransitionEvents } returns MutableSharedFlow(extraBufferCapacity = 1)
        every { player.playbackVolume } returns 0.6f
        every { player.playbackSurfaceState } returns PlaybackSurfaceState(isPlaying = true)
        val timer = SleepTimerController(this, player, context()) { now }
        val events = mutableListOf<Unit>()
        val collector = launch {
            timer.expiredEvents.collect { events += it }
        }

        timer.start(1)
        now = 60_000L
        runCurrent()

        assertFalse(timer.isActive)
        assertEquals(1, events.size)
        verify(exactly = 1) { player.pauseIfPlaying() }
        verify(exactly = 1) { player.setPlaybackVolume(0.6f) }
        collector.cancel()
    }

    @Test
    fun extendToTrackEndWaitsAtDeadlineAndStopsOnAutomaticTransition() = runTest {
        var now = 0L
        val transitions = MutableSharedFlow<PlaybackTrackTransition>(extraBufferCapacity = 1)
        val player = mockk<PlayerController>(relaxed = true)
        val song = mockk<com.mica.music.data.Song>()
        every { song.id } returns "song-a"
        every { player.trackTransitionEvents } returns transitions
        every { player.playbackVolume } returns 0.6f
        every { player.playbackSurfaceState } returns PlaybackSurfaceState(currentSong = song, isPlaying = true)
        every { player.playbackProgressState } returns PlaybackProgressState(positionMs = 10_000, durationMs = 180_000)
        val timer = SleepTimerController(this, player, context()) { now }

        timer.start(1, extendToTrackEnd = true)
        now = 60_000L
        runCurrent()

        assertTrue(timer.isActive)
        assertTrue(timer.isWaitingForTrackEnd)
        verify(exactly = 0) { player.pauseIfPlaying() }

        transitions.emit(
            PlaybackTrackTransition(
                previousSongId = "song-a",
                newSongId = "song-b",
                kind = PlaybackMediaTransition.Automatic,
            ),
        )
        runCurrent()

        assertFalse(timer.isActive)
        verify(exactly = 1) { player.pauseIfPlaying() }
        verify(exactly = 1) { player.setPlaybackVolume(0.6f) }
    }

    @Test
    fun extendToTrackEndDoesNotStartWallClockFadeBeforeDeadline() = runTest {
        var now = 0L
        val player = mockk<PlayerController>(relaxed = true)
        every { player.trackTransitionEvents } returns MutableSharedFlow(extraBufferCapacity = 1)
        every { player.playbackVolume } returns 0.8f
        val timer = SleepTimerController(this, player, context()) { now }

        timer.start(1, extendToTrackEnd = true)
        now = 30_000L
        runCurrent()

        assertTrue(timer.isActive)
        assertFalse(timer.state?.isFading == true)
        verify(exactly = 0) { player.setPlaybackVolume(any()) }
        timer.cancel()
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()
}
