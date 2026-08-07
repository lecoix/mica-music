package com.mica.music.media

import androidx.media3.common.Player
import com.mica.music.data.preferences.AudioOffloadDisabledReason
import com.mica.music.data.preferences.AudioOffloadPreferences
import com.mica.music.data.preferences.PreferencesTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioOffloadCircuitBreakerTest {
    private val context = PreferencesTestFixtures.context()
    private lateinit var scheduler: ManualScheduler
    private lateinit var snapshot: AudioOffloadPlaybackSnapshot
    private var fallbackCount = 0
    private lateinit var breaker: AudioOffloadCircuitBreaker

    @Before
    fun setUp() {
        PreferencesTestFixtures.clearMicaSettings(context)
        scheduler = ManualScheduler()
        snapshot = eligibleSnapshot()
        fallbackCount = 0
        breaker = AudioOffloadCircuitBreaker(
            snapshot = { snapshot },
            scheduler = scheduler,
            onFallbackToPcm = { fallbackCount++ },
            onVerifiedFailure = {
                AudioOffloadPreferences.recordVerifiedFailure(context, TEST_BUILD_TOKEN)
            },
        )
    }

    @Test
    fun actualOffloadStallFallsBackOnlyOnce() {
        breaker.onOffloadedPlayback(true)

        scheduler.runLast()
        breaker.onPlaybackStateChanged(Player.STATE_BUFFERING)

        assertTrue(breaker.sessionDisabled)
        assertEquals(1, fallbackCount)
    }

    @Test
    fun staleStallFromPreviousMediaCannotDisableNewPlayback() {
        breaker.onOffloadedPlayback(true)
        val staleTask = scheduler.lastTask()

        snapshot = snapshot.copy(mediaId = "song-b", isPlaying = true)
        breaker.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        scheduler.run(staleTask)

        assertFalse(breaker.sessionDisabled)
        assertEquals(0, fallbackCount)
    }

    @Test
    fun pcmRecoveryMustAdvanceBeforeFailureIsPersisted() {
        breaker.onOffloadedPlayback(true)
        scheduler.runLast()
        assertEquals(1, fallbackCount)

        snapshot = snapshot.copy(
            isPlaying = true,
            playbackState = Player.STATE_READY,
            currentPositionMs = 100L,
        )
        breaker.onIsPlayingChanged(true)
        snapshot = snapshot.copy(currentPositionMs = 2_600L)
        scheduler.runLast()

        val persisted = AudioOffloadPreferences.state(context, TEST_BUILD_TOKEN, builtInDenied = false)
        assertFalse(persisted.enabled)
        assertEquals(AudioOffloadDisabledReason.VERIFIED_RUNTIME_FAILURE, persisted.disabledReason)
    }

    @Test
    fun stalePcmConfirmationCannotPersistAfterMediaChanges() {
        breaker.onOffloadedPlayback(true)
        scheduler.runLast()
        snapshot = snapshot.copy(isPlaying = true, currentPositionMs = 100L)
        breaker.onIsPlayingChanged(true)
        val staleConfirmation = scheduler.lastTask()

        snapshot = snapshot.copy(mediaId = "song-b", currentPositionMs = 5_000L)
        breaker.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        scheduler.run(staleConfirmation)

        assertTrue(AudioOffloadPreferences.state(context, TEST_BUILD_TOKEN, builtInDenied = false).enabled)
    }

    @Test
    fun unbufferedOrSuppressedPlaybackDoesNotTrip() {
        snapshot = snapshot.copy(totalBufferedDurationMs = 0L)
        breaker.onOffloadedPlayback(true)
        scheduler.runLast()
        assertEquals(0, fallbackCount)

        breaker.resetForManualRetry()
        snapshot = eligibleSnapshot().copy(playbackSuppressionReason = 1)
        breaker.onOffloadedPlayback(true)
        assertTrue(scheduler.tasks.isEmpty())
        assertEquals(0, fallbackCount)
    }

    @Test
    fun remotePlaybackIsNotTreatedAsAnOffloadStall() {
        snapshot = eligibleSnapshot().copy(uriScheme = "https")

        breaker.onOffloadedPlayback(true)

        assertTrue(scheduler.tasks.isEmpty())
    }

    private fun eligibleSnapshot() = AudioOffloadPlaybackSnapshot(
        mediaId = "song-a",
        uriScheme = "content",
        playbackState = Player.STATE_BUFFERING,
        playWhenReady = true,
        isPlaying = false,
        playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
        totalBufferedDurationMs = 5_000L,
        currentPositionMs = 0L,
    )

    private class ManualScheduler : AudioOffloadWatchdogScheduler {
        val tasks = mutableListOf<Runnable>()

        override fun postDelayed(task: Runnable, delayMs: Long) {
            tasks += task
        }

        override fun remove(task: Runnable) {
            tasks -= task
        }

        fun lastTask(): Runnable = tasks.last()

        fun runLast() {
            run(lastTask())
        }

        fun run(task: Runnable) {
            tasks -= task
            task.run()
        }
    }

    private companion object {
        const val TEST_BUILD_TOKEN = "1:test-build"
    }
}
