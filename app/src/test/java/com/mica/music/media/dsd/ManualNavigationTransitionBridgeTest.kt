package com.mica.music.media.dsd

import androidx.media3.common.Format
import androidx.media3.exoplayer.source.MediaSource
import com.mica.music.media.dsf.DsfExtractorPacketFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ManualNavigationTransitionBridgeTest {
    private val dsd128 = DsfExtractorPacketFacts(
        sourceSampleRateHz = 5_644_800,
        channelCount = 2,
        sourceBitOrder = DsdSourceBitOrder.LSB_FIRST,
    )
    private val pcm96 = Format.Builder()
        .setSampleMimeType("audio/raw")
        .setSampleRate(96_000)
        .setChannelCount(2)
        .build()

    @Test
    fun publishSupersedesOlderEpochAndIdsIncrease() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "period-A")
        val source = observePlaying(bridge, "period-A", 1)

        val first = bridge.publish(
            "B",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-B",
        )
        val second = bridge.publish(
            "C",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-C",
        )

        assertTrue(second.requestId > first.requestId)
        assertEquals(source, second.sourcePlaybackIdentity)
        assertEquals(second, bridge.snapshot())
        assertFalse(bridge.cancel(first.requestId, "stale"))
        assertEquals(second, bridge.snapshot())
    }

    @Test
    fun directRetirementObservationDoesNotConsumeEpoch() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "period-A")
        observePlaying(bridge, "period-A", 1)
        val epoch = bridge.publish(
            "B",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-B",
        )

        assertEquals(epoch, bridge.observeDirectRetirementStop())
        assertEquals(epoch, bridge.snapshot())
    }

    @Test
    fun directDestinationRequiresLogicalTargetAndPlaybackGenerationAndCompletesOnce() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "period-A")
        observePlaying(bridge, "period-A", 1)
        val epoch = bridge.publish(
            "B",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-B",
        )
        val target = identity("period-B", 2)

        assertNull(bridge.bindDirectDestination(dsd128, target))
        bridge.updateApplicationCurrentness("B", "period-B")
        val bound = requireNotNull(bridge.bindDirectDestination(dsd128, target))
        val facts = requireNotNull(bound.targetFacts)
        assertEquals(epoch.requestId, bound.requestId)
        assertEquals(target, bound.targetPlaybackIdentity)
        assertTrue(bridge.isCurrentDestination(bound.requestId, facts, target))
        assertTrue(bridge.complete(bound.requestId, DirectDsdTrackTransportFamily.DOP))
        assertNull(bridge.snapshot())
        assertFalse(bridge.complete(bound.requestId, DirectDsdTrackTransportFamily.DOP))
    }

    @Test
    fun readAheadTargetDoesNotReplaceAuthoritativePlayingSource() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "period-A")
        val authoritativeA = observePlaying(bridge, "period-A", 1)

        // Media3 may advance the reading renderer to B while A is still the playing/current item.
        val readAheadB = observe(bridge, "period-B", 2)
        val epoch = bridge.publish(
            "B",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-B",
        )

        assertEquals(authoritativeA, epoch.sourcePlaybackIdentity)
        bridge.updateApplicationCurrentness("B", "period-B")
        val bound = requireNotNull(bridge.bindDirectDestination(dsd128, readAheadB))
        assertEquals(readAheadB, bound.targetPlaybackIdentity)
        assertTrue(bridge.complete(bound.requestId, DirectDsdTrackTransportFamily.DOP))
    }

    @Test
    fun sameUidFutureReadingOccurrenceCannotOverwritePlayingOccurrence() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "uid-A")
        val playingA = observePlaying(bridge, "uid-A", 1)

        observe(bridge, "uid-A", 2)
        val epoch = bridge.publish(
            "B",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "uid-B",
        )

        assertEquals(playingA, epoch.sourcePlaybackIdentity)
        assertEquals(1L, epoch.sourcePlaybackIdentity?.windowSequenceNumber)
    }

    @Test
    fun repeatedSameUidReadAheadOccurrenceRemainsLegalManualTarget() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "uid-A")
        val playingA = observePlaying(bridge, "uid-A", 1)
        val readAheadRepeat = observe(bridge, "uid-A", 2)

        val epoch = bridge.publish(
            "A-repeat",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "uid-A",
        )
        assertEquals(playingA, epoch.sourcePlaybackIdentity)
        bridge.updateApplicationCurrentness(
            "A-repeat",
            "uid-A",
            invalidatePlayingOccurrence = true,
        )

        val bound = requireNotNull(bridge.bindDirectDestination(dsd128, readAheadRepeat))
        assertEquals(readAheadRepeat, bound.targetPlaybackIdentity)
        assertEquals(2L, bound.targetPlaybackIdentity?.windowSequenceNumber)
    }

    @Test
    fun unresolvedApplicationPeriodNeverPromotesReadingHeadToSourceAuthority() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", null)
        observe(bridge, "period-A", 7)

        val epoch = bridge.publish(
            "B",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-B",
        )

        assertNull(epoch.sourcePlaybackIdentity)
    }

    @Test
    fun applicationPeriodChangeClearsOldSourceUntilMatchingObservationArrives() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "period-A")
        observePlaying(bridge, "period-A", 10)

        bridge.updateApplicationCurrentness("B", "period-B")
        val withoutFreshBObservation = bridge.publish(
            "C",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-C",
        )
        assertNull(withoutFreshBObservation.sourcePlaybackIdentity)

        bridge.abort("test-reset")
        val authoritativeB = observePlaying(bridge, "period-B", 11)
        val withFreshBObservation = bridge.publish(
            "C",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-C",
        )
        assertEquals(authoritativeB, withFreshBObservation.sourcePlaybackIdentity)
    }

    @Test
    fun rapidSupersedeRejectsStaleSameFactsPlaybackGeneration() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "period-A")
        observePlaying(bridge, "period-A", 10)
        bridge.publish(
            "B",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-B",
        )
        val latest = bridge.publish(
            "C",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-C",
        )
        bridge.updateApplicationCurrentness("C", "period-C")

        val staleB = identity("period-B", 11)
        assertNull(bridge.bindDirectDestination(dsd128, staleB))
        assertNull(bridge.snapshot()?.targetFacts)
        assertNull(bridge.snapshot()?.targetPlaybackIdentity)

        val trueC = identity("period-C", 12)
        val bound = requireNotNull(bridge.bindDirectDestination(dsd128, trueC))
        assertEquals(latest.requestId, bound.requestId)
        assertEquals(trueC, bound.targetPlaybackIdentity)
    }

    @Test
    fun samePeriodUidStillRequiresNewWindowSequenceOccurrence() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "shared-period")
        val oldOccurrence = observePlaying(bridge, "shared-period", 21)
        bridge.publish(
            "A-copy",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "shared-period",
        )
        bridge.updateApplicationCurrentness("A-copy", "shared-period")

        assertNull(bridge.bindDirectDestination(dsd128, oldOccurrence))
        val newOccurrence = identity("shared-period", 22)
        assertEquals(
            newOccurrence,
            requireNotNull(bridge.bindDirectDestination(dsd128, newOccurrence)).targetPlaybackIdentity,
        )
    }

    @Test
    fun staleOrWrongFamilyCompletionFailsClosed() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "period-A")
        observePlaying(bridge, "period-A", 1)
        val epoch = bridge.publish(
            "B",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-B",
        )
        bridge.updateApplicationCurrentness("B", "period-B")
        val bound = requireNotNull(bridge.bindDirectDestination(dsd128, identity("period-B", 2)))

        assertFalse(bridge.complete(epoch.requestId + 1, DirectDsdTrackTransportFamily.DOP))
        assertFalse(bridge.complete(bound.requestId, DirectDsdTrackTransportFamily.PCM))
        assertEquals(bound, bridge.snapshot())
    }

    @Test
    fun pcmDestinationUsesSamePlaybackGenerationAndAbortClearsAuthority() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("D", "period-D")
        observePlaying(bridge, "period-D", 30)
        val epoch = bridge.publish(
            "P",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-P",
        )
        bridge.updateApplicationCurrentness("P", "period-P")
        val target = identity("period-P", 31)

        val bound = requireNotNull(bridge.bindPcmDestination(pcm96, target))
        assertEquals(epoch.requestId, bound.requestId)
        assertEquals(target, bound.targetPlaybackIdentity)
        assertEquals(DirectDsdTrackTransportFamily.PCM, bound.targetFacts?.family)
        bridge.abort("stack-rebuild")
        assertNull(bridge.snapshot())
    }

    @Test
    fun applicationThreadPublishesCurrentnessAndPlaybackThreadConsumesOnlyBridgeState() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.updateApplicationCurrentness("A", "period-A")
        observePlaying(bridge, "period-A", 40)
        val epoch = bridge.publish(
            "B",
            true,
            DirectDsdTrackTransportFamily.DOP,
            expectedTargetPeriodUid = "period-B",
        )
        val target = identity("period-B", 41)
        val firstPlaybackResult = AtomicReference<ManualNavigationTransitionEpoch?>()
        val secondPlaybackResult = AtomicReference<ManualNavigationTransitionEpoch?>()
        val failure = AtomicReference<Throwable?>()
        val firstDone = CountDownLatch(1)
        val applicationDone = CountDownLatch(1)

        val playbackThread = Thread({
            try {
                firstPlaybackResult.set(bridge.bindDirectDestination(dsd128, target))
                firstDone.countDown()
                check(applicationDone.await(5, TimeUnit.SECONDS))
                val bound = bridge.bindDirectDestination(dsd128, target)
                secondPlaybackResult.set(bound)
                val facts = requireNotNull(bound?.targetFacts)
                check(bridge.isCurrentDestination(epoch.requestId, facts, target))
                check(bridge.complete(epoch.requestId, DirectDsdTrackTransportFamily.DOP))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "directive65-playback-thread")
        playbackThread.start()

        assertTrue(firstDone.await(5, TimeUnit.SECONDS))
        assertNull(firstPlaybackResult.get())
        val applicationThread = Thread({
            bridge.updateApplicationCurrentness("B", "period-B")
            applicationDone.countDown()
        }, "directive65-application-thread")
        applicationThread.start()
        applicationThread.join(5_000)
        playbackThread.join(5_000)

        failure.get()?.let { throw AssertionError("cross-thread bridge operation failed", it) }
        assertEquals(epoch.requestId, secondPlaybackResult.get()?.requestId)
        assertNull(bridge.snapshot())
    }

    private fun observe(
        bridge: ManualNavigationTransitionBridge,
        periodUid: Any,
        windowSequenceNumber: Long,
    ): ManualNavigationPlaybackIdentity = bridge.observePlaybackStream(
        MediaSource.MediaPeriodId(periodUid, windowSequenceNumber),
    )

    private fun observePlaying(
        bridge: ManualNavigationTransitionBridge,
        periodUid: Any,
        windowSequenceNumber: Long,
    ): ManualNavigationPlaybackIdentity {
        val mediaPeriodId = MediaSource.MediaPeriodId(periodUid, windowSequenceNumber)
        bridge.updateApplicationPlayingOccurrence(mediaPeriodId)
        return ManualNavigationPlaybackIdentity.from(mediaPeriodId)
    }

    private fun identity(periodUid: Any, windowSequenceNumber: Long) =
        ManualNavigationPlaybackIdentity(periodUid, windowSequenceNumber)
}
