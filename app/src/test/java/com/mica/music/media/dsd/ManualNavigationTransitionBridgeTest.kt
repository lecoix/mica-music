package com.mica.music.media.dsd

import androidx.media3.common.Format
import com.mica.music.media.dsd.DsdSourceBitOrder
import com.mica.music.media.dsf.DsfExtractorPacketFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualNavigationTransitionBridgeTest {
    private val dsd128 = DsfExtractorPacketFacts(
        sourceSampleRateHz = 5_644_800,
        channelCount = 2,
        sourceBitOrder = DsdSourceBitOrder.LSB_FIRST,
    )

    @Test
    fun publishSupersedesOlderEpochAndIdsIncrease() {
        val bridge = ManualNavigationTransitionBridge()
        var current = "A"
        bridge.bindCurrentMediaIdProvider { current }

        val first = bridge.publish("B", true, DirectDsdTrackTransportFamily.DOP)
        val second = bridge.publish("C", true, DirectDsdTrackTransportFamily.DOP)

        assertTrue(second.requestId > first.requestId)
        assertEquals(second, bridge.snapshot())
        assertFalse(bridge.cancel(first.requestId, "stale"))
        assertEquals(second, bridge.snapshot())
    }

    @Test
    fun directRetirementObservationDoesNotConsumeEpoch() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.bindCurrentMediaIdProvider { "B" }
        val epoch = bridge.publish("B", true, DirectDsdTrackTransportFamily.DOP)

        assertEquals(epoch, bridge.observeDirectRetirementStop())
        assertEquals(epoch, bridge.snapshot())
    }

    @Test
    fun directDestinationBindsOnlyAfterLogicalTargetIsCurrentAndCompletesOnce() {
        val bridge = ManualNavigationTransitionBridge()
        var current = "A"
        bridge.bindCurrentMediaIdProvider { current }
        val epoch = bridge.publish("B", true, DirectDsdTrackTransportFamily.DOP)

        assertNull(bridge.bindDirectDestination(dsd128))
        current = "B"
        val bound = requireNotNull(bridge.bindDirectDestination(dsd128))
        val facts = requireNotNull(bound.targetFacts)
        assertEquals(epoch.requestId, bound.requestId)
        assertTrue(bridge.isCurrentDestination(bound.requestId, facts))
        assertTrue(bridge.complete(bound.requestId, DirectDsdTrackTransportFamily.DOP))
        assertNull(bridge.snapshot())
        assertFalse(bridge.complete(bound.requestId, DirectDsdTrackTransportFamily.DOP))
    }

    @Test
    fun staleOrWrongFamilyCompletionFailsClosed() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.bindCurrentMediaIdProvider { "B" }
        val epoch = bridge.publish("B", true, DirectDsdTrackTransportFamily.DOP)
        val bound = requireNotNull(bridge.bindDirectDestination(dsd128))

        assertFalse(bridge.complete(epoch.requestId + 1, DirectDsdTrackTransportFamily.DOP))
        assertFalse(bridge.complete(bound.requestId, DirectDsdTrackTransportFamily.PCM))
        assertEquals(bound, bridge.snapshot())
    }

    @Test
    fun pcmDestinationUsesSameEpochAndAbortClearsAuthority() {
        val bridge = ManualNavigationTransitionBridge()
        bridge.bindCurrentMediaIdProvider { "P" }
        val epoch = bridge.publish("P", true, DirectDsdTrackTransportFamily.DOP)
        val pcm = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setSampleRate(96_000)
            .setChannelCount(2)
            .build()

        val bound = requireNotNull(bridge.bindPcmDestination(pcm))
        assertEquals(epoch.requestId, bound.requestId)
        assertEquals(DirectDsdTrackTransportFamily.PCM, bound.targetFacts?.family)
        bridge.abort("stack-rebuild")
        assertNull(bridge.snapshot())
    }
}
