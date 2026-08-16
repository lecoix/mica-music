package com.mica.music.media.usb.shadow

import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.PlaybackStackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamProducerHandleRegistryTest {
    private val stackId = PlaybackStackId(41)
    private val registry = StreamProducerHandleRegistry(stackId)
    private val e1 = PlaybackTopologyProducerToken(stackId, PlaybackTopologyEpoch(1))
    private val e2 = PlaybackTopologyProducerToken(stackId, PlaybackTopologyEpoch(2))

    @Test
    fun delayedOldOccurrenceRedeemsItsCapturedProducerAfterNewerProducerExists() {
        val oldOccurrence = PlaybackOccurrence("period-same-media", 301)
        val newOccurrence = PlaybackOccurrence("period-same-media", 302)
        val oldSource = registry.newSourceInstanceId()
        val newSource = registry.newSourceInstanceId()
        val old = requireNotNull(registry.capture(oldSource, e1, oldOccurrence))
        val fresh = requireNotNull(registry.capture(newSource, e2, newOccurrence))

        assertEquals(old, registry.redeem(oldOccurrence))
        assertEquals(e1, registry.redeem(oldOccurrence)?.producerToken)
        assertEquals(fresh, registry.redeem(newOccurrence))
        assertEquals(e2, registry.redeem(newOccurrence)?.producerToken)
        assertNotEquals(old.periodInstanceId, fresh.periodInstanceId)
    }

    @Test
    fun duplicateExactOccurrenceAcrossProducerInstancesIsFailClosedUntilOneReleases() {
        val occurrence = PlaybackOccurrence("reused-period", 77)
        val first = requireNotNull(
            registry.capture(registry.newSourceInstanceId(), e1, occurrence),
        )
        val second = requireNotNull(
            registry.capture(registry.newSourceInstanceId(), e2, occurrence),
        )

        assertNull(registry.redeem(occurrence))
        registry.release(second)
        assertEquals(first, registry.redeem(occurrence))
    }

    @Test
    fun releaseSourceAndWrongStackNeverTransferProducerAuthority() {
        val occurrence = PlaybackOccurrence("period-a", 9)
        val source = registry.newSourceInstanceId()
        val handle = requireNotNull(registry.capture(source, e1, occurrence))
        assertEquals(handle, registry.redeem(occurrence))

        registry.releaseSource(source)
        assertNull(registry.redeem(occurrence))
        assertNull(
            registry.capture(
                registry.newSourceInstanceId(),
                PlaybackTopologyProducerToken(PlaybackStackId(99), PlaybackTopologyEpoch(1)),
                occurrence,
            ),
        )
    }
}
