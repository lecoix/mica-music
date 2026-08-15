package com.mica.music.media.dsd

import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.MediaSource
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class TransitionAwarePcmNavigationGenerationTest {
    private val pcm96 = Format.Builder()
        .setSampleMimeType("audio/raw")
        .setSampleRate(96_000)
        .setChannelCount(2)
        .build()

    @Test
    fun readAheadBDoesNotPoisonSourceAndCanBecomeTruePcmTargetAfterCurrentnessAndRelease() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "period-A")
        val authoritativeA = bridge.observePlaybackStream(MediaSource.MediaPeriodId("period-A", 1L))
        val coordinator = DirectDsdTrackTransitionCoordinator()
        coordinator.beforeDirectAccept(isPlaying = true)
        val delegate = mockk<AudioSink>(relaxed = true)
        val projection = ManualNavigationPlaybackPeriodProjection(bridge)
        val sink = TransitionAwarePcmAudioSink(delegate, coordinator, bridge, projection)

        // Reading head is already B, while application/playing current remains A.
        projection.onStreamChanged(MediaSource.MediaPeriodId("period-B", 2L))
        val epoch = bridge.publish(
            targetMediaId = "B",
            requestedPlaying = true,
            sourceFamily = DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-B",
        )
        assertEquals(authoritativeA, epoch.sourcePlaybackIdentity)

        sink.configure(pcm96, 0, null)
        verify(exactly = 0) { delegate.configure(any(), any(), any()) }

        coordinator.onDirectReleased(wasPaused = false)
        bridge.updateApplicationCurrentness("B", "period-B")
        sink.handleBuffer(ByteBuffer.allocate(0), 0L, 1)

        verify(exactly = 1) { delegate.configure(pcm96, 0, null) }
        verify(exactly = 1) { delegate.handleBuffer(any(), 0L, 1) }
        assertEquals(DirectDsdTrackTransportFamily.PCM, coordinator.snapshot().activeFamily)
        assertNull(bridge.snapshot())
    }

    @Test
    fun staleBConfigureCannotAcceptLatestCAndTrueCReplacesPendingGeneration() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "period-A")
        bridge.observePlaybackStream(MediaSource.MediaPeriodId("period-A", 1L))
        val coordinator = DirectDsdTrackTransitionCoordinator()
        coordinator.beforeDirectAccept(isPlaying = true)

        bridge.publish(
            targetMediaId = "B",
            requestedPlaying = true,
            sourceFamily = DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-B",
        )
        val latest = bridge.publish(
            targetMediaId = "C",
            requestedPlaying = true,
            sourceFamily = DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-C",
        )
        bridge.updateApplicationCurrentness("C", "period-C")

        val delegate = mockk<AudioSink>(relaxed = true)
        val projection = ManualNavigationPlaybackPeriodProjection(bridge)
        val sink = TransitionAwarePcmAudioSink(delegate, coordinator, bridge, projection)

        projection.onStreamChanged(MediaSource.MediaPeriodId("period-B", 2L))
        sink.configure(pcm96, 0, null)
        verify(exactly = 0) { delegate.configure(any(), any(), any()) }
        assertNull(bridge.snapshot()?.targetFacts)
        assertNull(bridge.snapshot()?.targetPlaybackIdentity)

        coordinator.onDirectReleased(wasPaused = false)
        projection.onStreamChanged(MediaSource.MediaPeriodId("period-C", 3L))
        sink.configure(pcm96, 0, null)

        verify(exactly = 1) { delegate.configure(pcm96, 0, null) }
        assertEquals(DirectDsdTrackTransportFamily.PCM, coordinator.snapshot().activeFamily)
        assertNull(bridge.snapshot())

        // The superseded B PendingConfiguration must not resurrect after C was accepted.
        sink.handleBuffer(ByteBuffer.allocate(0), 0L, 1)
        verify(exactly = 1) { delegate.configure(pcm96, 0, null) }
        verify(exactly = 1) { delegate.handleBuffer(any(), 0L, 1) }
    }

    @Test
    fun sameFactsWrongWindowSequenceCannotReplaceAlreadyBoundTargetOccurrence() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "period-A")
        bridge.observePlaybackStream(MediaSource.MediaPeriodId("period-A", 7L))
        val epoch = bridge.publish(
            targetMediaId = "C",
            requestedPlaying = true,
            sourceFamily = DirectDsdTrackTransportFamily.NONE,
            expectedTargetPeriodUid = "period-C",
        )
        bridge.updateApplicationCurrentness("C", "period-C")

        val first = ManualNavigationPlaybackIdentity("period-C", 8L)
        requireNotNull(bridge.bindPcmDestination(pcm96, first))
        val wrongOccurrence = ManualNavigationPlaybackIdentity("period-C", 9L)
        assertNull(bridge.bindPcmDestination(pcm96, wrongOccurrence))
        assertEquals(epoch.requestId, bridge.snapshot()?.requestId)
        assertEquals(first, bridge.snapshot()?.targetPlaybackIdentity)
    }
}
