package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
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
import java.io.IOException

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
        assertEquals(AudioOffloadFallbackReason.BUFFERING_STALL, breaker.lastFallbackReason)
        assertEquals(1, fallbackCount)
    }

    @Test
    fun analyticsAudioTrackInitializedOffloadTriggersBufferingStallAndPersistsAfterPcmRecovery() {
        breaker.onAudioTrackInitialized(testEventTime(), audioTrackConfig(offload = true))
        assertTrue(breaker.currentlyOffloaded)

        scheduler.runLast()
        assertTrue(breaker.sessionDisabled)
        assertEquals(AudioOffloadFallbackReason.BUFFERING_STALL, breaker.lastFallbackReason)
        assertEquals(1, fallbackCount)

        breaker.onAudioTrackInitialized(testEventTime(), audioTrackConfig(offload = false))
        breaker.onAudioTrackReleased(testEventTime(), audioTrackConfig(offload = true))
        assertFalse(breaker.currentlyOffloaded)

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
    fun asyncReleaseOfPreviousOffloadTrackDoesNotClearNewOffloadTrack() {
        breaker.onAudioTrackInitialized(testEventTime(), audioTrackConfig(offload = true))
        breaker.onAudioTrackInitialized(testEventTime(), audioTrackConfig(offload = true))
        // Stale async release from the previous track arrives after the new offload track initialized.
        breaker.onAudioTrackReleased(testEventTime(), audioTrackConfig(offload = true))

        assertTrue(breaker.currentlyOffloaded)
        scheduler.runLast()
        assertTrue(breaker.sessionDisabled)
        assertEquals(1, fallbackCount)
    }

    @Test
    fun pcmAudioTrackInitImmediatelyClearsOffloadBeforeOldOffloadReleases() {
        breaker.onAudioTrackInitialized(testEventTime(), audioTrackConfig(offload = true))
        assertTrue(breaker.currentlyOffloaded)

        breaker.onAudioTrackInitialized(testEventTime(), audioTrackConfig(offload = false))
        assertFalse(breaker.currentlyOffloaded)
        assertTrue(scheduler.tasks.isEmpty())

        // Stale offload release finishes asynchronously afterwards.
        breaker.onAudioTrackReleased(testEventTime(), audioTrackConfig(offload = true))
        assertFalse(breaker.currentlyOffloaded)
    }

    @Test
    fun readyStateWithFrozenPositionTriggersFallbackAndPersistsAfterPcmAdvances() {
        snapshot = eligibleSnapshot().copy(
            playbackState = Player.STATE_READY,
            isPlaying = true,
            currentPositionMs = 0L,
        )
        breaker.onAudioTrackInitialized(testEventTime(), audioTrackConfig(offload = true))

        assertEquals(1, scheduler.tasks.size)
        scheduler.runLast()

        assertTrue(breaker.sessionDisabled)
        assertEquals(AudioOffloadFallbackReason.READY_POSITION_STALL, breaker.lastFallbackReason)
        assertEquals(1, fallbackCount)

        breaker.onAudioTrackInitialized(testEventTime(), audioTrackConfig(offload = false))
        snapshot = snapshot.copy(currentPositionMs = 200L)
        breaker.onIsPlayingChanged(true)
        snapshot = snapshot.copy(currentPositionMs = 2_500L)
        scheduler.runLast()

        val persisted = AudioOffloadPreferences.state(context, TEST_BUILD_TOKEN, builtInDenied = false)
        assertFalse(persisted.enabled)
        assertEquals(AudioOffloadDisabledReason.VERIFIED_RUNTIME_FAILURE, persisted.disabledReason)
    }

    @Test
    fun readyStateWithAdvancingPositionDoesNotTrip() {
        snapshot = eligibleSnapshot().copy(
            playbackState = Player.STATE_READY,
            isPlaying = true,
            currentPositionMs = 0L,
        )
        breaker.onAudioTrackInitialized(testEventTime(), audioTrackConfig(offload = true))

        snapshot = snapshot.copy(currentPositionMs = 3_800L)
        scheduler.runLast()

        assertFalse(breaker.sessionDisabled)
        assertEquals(0, fallbackCount)
    }

    @Test
    fun staleReadyStallAfterSeekOrExternalBoundaryCannotTripNewPlayback() {
        snapshot = eligibleSnapshot().copy(
            playbackState = Player.STATE_READY,
            isPlaying = true,
            currentPositionMs = 0L,
        )
        breaker.onAudioTrackInitialized(testEventTime(), audioTrackConfig(offload = true))
        val staleTask = scheduler.lastTask()

        // Seek resets the baseline; running the stale pre-seek task must have no effect.
        snapshot = snapshot.copy(currentPositionMs = 15_000L)
        breaker.onPositionDiscontinuity(
            positionInfo(0L),
            positionInfo(15_000L),
            Player.DISCONTINUITY_REASON_SEEK,
        )
        scheduler.run(staleTask)

        assertFalse(breaker.sessionDisabled)
        assertEquals(0, fallbackCount)

        // External boundary (e.g., route change or DSP toggle) invalidates the post-seek watch too.
        val postSeekTask = scheduler.lastTask()
        breaker.invalidateExternalBoundary()
        scheduler.run(postSeekTask)

        assertFalse(breaker.sessionDisabled)
        assertEquals(0, fallbackCount)
    }

    @Test
    fun repeatedRefreshDuringOngoingStallDoesNotResetWatchdog() {
        breaker.onAudioTrackInitialized(testEventTime(), audioTrackConfig(offload = true))
        val initialTask = scheduler.lastTask()

        breaker.onPlaybackStateChanged(Player.STATE_BUFFERING)
        breaker.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

        assertEquals(1, scheduler.tasks.size)
        assertTrue(scheduler.tasks.single() === initialTask)
    }

    @Test
    fun offloadAudioTrackInitAndWriteErrorsTriggerImmediateFallbackOnce() {
        val initError = ExoPlaybackException.createForSource(
            IOException("offload init failed"),
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        )
        val writeError = ExoPlaybackException.createForSource(
            IOException("offload write failed"),
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        )
        val decodeError = ExoPlaybackException.createForSource(
            IOException("bad file"),
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        assertFalse(breaker.tryRecoverFromPlayerError(decodeError))
        assertEquals(0, fallbackCount)

        assertTrue(breaker.tryRecoverFromPlayerError(initError))
        assertTrue(breaker.sessionDisabled)
        assertEquals(AudioOffloadFallbackReason.OFFLOAD_AUDIO_TRACK_ERROR, breaker.lastFallbackReason)
        assertEquals(1, fallbackCount)

        // Second offload error while already disabled must not loop.
        assertFalse(breaker.tryRecoverFromPlayerError(writeError))
        assertEquals(1, fallbackCount)

        // PCM recovery after error still verifies progress before persisting.
        snapshot = snapshot.copy(
            playbackState = Player.STATE_READY,
            isPlaying = true,
            currentPositionMs = 500L,
        )
        breaker.onIsPlayingChanged(true)
        snapshot = snapshot.copy(currentPositionMs = 2_800L)
        scheduler.runLast()

        val persisted = AudioOffloadPreferences.state(context, TEST_BUILD_TOKEN, builtInDenied = false)
        assertFalse(persisted.enabled)
        assertEquals(AudioOffloadDisabledReason.VERIFIED_RUNTIME_FAILURE, persisted.disabledReason)
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
    fun seekDuringPcmConfirmationResetsBaselineInsteadOfFakingProgress() {
        breaker.onAudioTrackInitialized(testEventTime(), audioTrackConfig(offload = true))
        scheduler.runLast()
        assertEquals(1, fallbackCount)

        breaker.onAudioTrackInitialized(testEventTime(), audioTrackConfig(offload = false))
        snapshot = snapshot.copy(
            isPlaying = true,
            playbackState = Player.STATE_READY,
            currentPositionMs = 100L,
        )
        breaker.onIsPlayingChanged(true)
        val staleConfirmation = scheduler.lastTask()

        // User seeks to 30s, then PCM is still frozen at 30s.
        snapshot = snapshot.copy(currentPositionMs = 30_000L)
        breaker.onPositionDiscontinuity(
            positionInfo(100L),
            positionInfo(30_000L),
            Player.DISCONTINUITY_REASON_SEEK,
        )
        scheduler.run(staleConfirmation)
        scheduler.runLast()

        assertTrue(AudioOffloadPreferences.state(context, TEST_BUILD_TOKEN, builtInDenied = false).enabled)
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

    private fun testEventTime() = AnalyticsListener.EventTime(
        0L,
        Timeline.EMPTY,
        0,
        null,
        0L,
        Timeline.EMPTY,
        0,
        null,
        0L,
        0L,
    )

    private fun audioTrackConfig(offload: Boolean) = AudioSink.AudioTrackConfig(
        C.ENCODING_MP3,
        44_100,
        android.media.AudioFormat.CHANNEL_OUT_STEREO,
        false,
        offload,
        16_384,
    )

    private fun positionInfo(positionMs: Long) = Player.PositionInfo(
        null,
        0,
        null,
        null,
        0,
        positionMs,
        positionMs,
        C.INDEX_UNSET,
        C.INDEX_UNSET,
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
