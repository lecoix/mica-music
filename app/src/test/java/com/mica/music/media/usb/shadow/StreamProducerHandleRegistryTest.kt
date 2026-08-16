package com.mica.music.media.usb.shadow

import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.PlaybackStackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StreamProducerHandleRegistryTest {
    private val stackId = PlaybackStackId(41)
    private val registry = StreamProducerHandleRegistry(stackId)
    private val e1 = PlaybackTopologyProducerToken(stackId, PlaybackTopologyEpoch(1))
    private val e2 = PlaybackTopologyProducerToken(stackId, PlaybackTopologyEpoch(2))

    @Test
    fun delayedOldHandleKeepsCapturedProducerAfterNewerSameOccurrenceExists() {
        val occurrence = PlaybackOccurrence("period-same-media", 77)
        val old = requireNotNull(registry.capture(registry.newSourceInstanceId(), e1, occurrence))
        val fresh = requireNotNull(registry.capture(registry.newSourceInstanceId(), e2, occurrence))

        assertEquals(old, registry.redeem(old.periodInstanceId))
        assertEquals(e1, registry.redeem(old.periodInstanceId)?.producerToken)
        assertEquals(fresh, registry.redeem(fresh.periodInstanceId))
        assertEquals(e2, registry.redeem(fresh.periodInstanceId)?.producerToken)
        assertNotEquals(old.periodInstanceId, fresh.periodInstanceId)
        assertNotEquals(old.sourceInstanceId, fresh.sourceInstanceId)
    }

    @Test
    fun releaseThenReuseSameOccurrenceDoesNotTransferOldHandleIdentity() {
        val occurrence = PlaybackOccurrence("reused-period", 77)
        val first = requireNotNull(registry.capture(registry.newSourceInstanceId(), e1, occurrence))
        registry.release(first)
        assertNull(registry.redeem(first.periodInstanceId))

        val second = requireNotNull(registry.capture(registry.newSourceInstanceId(), e2, occurrence))
        assertNull(registry.redeem(first.periodInstanceId))
        assertEquals(second, registry.redeem(second.periodInstanceId))
        assertNotEquals(first.periodInstanceId, second.periodInstanceId)
        assertSame(e1, first.producerToken)
        assertEquals(e2, second.producerToken)
    }

    @Test
    fun duplicateExactOccurrenceLiveHandlesRemainDistinctByPeriodInstance() {
        val occurrence = PlaybackOccurrence("reused-period", 77)
        val first = requireNotNull(registry.capture(registry.newSourceInstanceId(), e1, occurrence))
        val second = requireNotNull(registry.capture(registry.newSourceInstanceId(), e2, occurrence))
        assertEquals(first, registry.redeem(first.periodInstanceId))
        assertEquals(second, registry.redeem(second.periodInstanceId))
        registry.release(second)
        assertEquals(first, registry.redeem(first.periodInstanceId))
        assertNull(registry.redeem(second.periodInstanceId))
    }

    @Test
    fun releaseSourceAndWrongStackNeverTransferProducerAuthority() {
        val occurrence = PlaybackOccurrence("period-a", 9)
        val source = registry.newSourceInstanceId()
        val handle = requireNotNull(registry.capture(source, e1, occurrence))
        assertEquals(handle, registry.redeem(handle.periodInstanceId))

        registry.releaseSource(source)
        assertNull(registry.redeem(handle.periodInstanceId))
        assertNull(
            registry.capture(
                registry.newSourceInstanceId(),
                PlaybackTopologyProducerToken(PlaybackStackId(99), PlaybackTopologyEpoch(1)),
                occurrence,
            ),
        )
    }
}
