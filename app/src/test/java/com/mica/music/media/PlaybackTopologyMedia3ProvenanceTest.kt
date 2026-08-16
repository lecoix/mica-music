package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.mica.music.media.usb.protocol.PlaybackStackId
import com.mica.music.media.usb.protocol.ProtocolTopologyReservation
import com.mica.music.media.usb.protocol.TopologyCommitKind
import com.mica.music.media.usb.protocol.TopologyReservationId
import com.mica.music.media.usb.shadow.PlaybackTopologyEpoch
import com.mica.music.media.usb.shadow.PlaybackTopologyMutationReservation
import com.mica.music.media.usb.shadow.PlaybackTopologyProducerToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackTopologyMedia3ProvenanceTest {
    private val stackId = PlaybackStackId(7)
    private val epoch1 = PlaybackTopologyProducerToken(stackId, PlaybackTopologyEpoch(1))

    @Test
    fun metadataOnlyReplacementPreservesProducerAndDoesNotRequireTopologyAdvance() {
        val provenance = PlaybackTopologyMedia3Provenance(epoch1)
        val original = item(
            uri = "file:///music/a.flac",
            title = "old title",
        )
        val tagged = provenance.tagForProducer(original, epoch1)
        val metadataOnly = item(
            uri = "file:///music/a.flac",
            title = "new title and artwork overlay",
        )

        assertTrue(provenance.playbackSourceEquivalent(tagged, metadataOnly))
        val preserved = provenance.preserveProducerTag(tagged, metadataOnly)
        assertEquals(epoch1, provenance.producerTokenOf(preserved))
        assertEquals(epoch1, provenance.currentToken())
        assertTrue(provenance.queuePlaybackSourceEquivalent(listOf(tagged), listOf(preserved)))
    }

    @Test
    fun truePlaybackSourceReplacementRequiresAndCommitsFreshProducerExactlyOnce() {
        val provenance = PlaybackTopologyMedia3Provenance(epoch1)
        val original = provenance.tagForProducer(
            item(uri = "file:///music/a.flac", title = "same logical row"),
            epoch1,
        )
        val replacement = item(
            uri = "file:///music/b.flac",
            title = "same logical row",
        )
        assertFalse(provenance.playbackSourceEquivalent(original, replacement))

        val epoch2 = PlaybackTopologyProducerToken(stackId, PlaybackTopologyEpoch(2))
        val reservation = PlaybackTopologyMutationReservation(
            producerToken = epoch2,
            baseToken = epoch1,
            seam = "replace-media-item",
            protocolReservation = protocolReservation(epoch2, "replace-media-item"),
        )
        val taggedReplacement = provenance.tagForProducer(replacement, epoch2)
        assertTrue(provenance.prepare(reservation, listOf(taggedReplacement)))
        assertEquals(epoch1, provenance.currentToken())
        assertTrue(provenance.commit(reservation))
        assertEquals(epoch2, provenance.currentToken())
        assertEquals(epoch2, provenance.producerTokenOf(taggedReplacement))
        assertFalse(provenance.commit(reservation))
        assertEquals(epoch2, provenance.currentToken())
    }

    @Test
    fun queueCarrierRejectsTokenlessAndMixedProducerRows() {
        val provenance = PlaybackTopologyMedia3Provenance(epoch1)
        val epoch2 = PlaybackTopologyProducerToken(stackId, PlaybackTopologyEpoch(2))
        val a = item(uri = "file:///music/a.flac", title = "A")
        val b = item(uri = "file:///music/b.flac", title = "B")
        val a1 = provenance.tagForProducer(a, epoch1)
        val b1 = provenance.tagForProducer(b, epoch1)
        val b2 = provenance.tagForProducer(b, epoch2)

        assertEquals(epoch1, provenance.producerTokenOf(listOf(a1, b1)))
        assertNull(provenance.producerTokenOf(emptyList()))
        assertNull(provenance.producerTokenOf(listOf(a1, b)))
        assertNull(provenance.producerTokenOf(listOf(a1, b2)))
    }

    @Test
    fun historicalRepresentationRecurrenceStillGetsFreshQueueWideProducerStamp() {
        val provenance = PlaybackTopologyMedia3Provenance(epoch1)
        val a = item(uri = "file:///music/a.flac", title = "A")
        val b = item(uri = "file:///music/b.flac", title = "B")
        val e2 = PlaybackTopologyProducerToken(stackId, PlaybackTopologyEpoch(2))
        val e3 = PlaybackTopologyProducerToken(stackId, PlaybackTopologyEpoch(3))
        val e4 = PlaybackTopologyProducerToken(stackId, PlaybackTopologyEpoch(4))

        val queueE2 = provenance.tagForProducer(listOf(a, b), e2)
        val movedE3 = provenance.tagForProducer(listOf(b, a), e3)
        val movedBackE4 = provenance.tagForProducer(listOf(a, b), e4)

        assertTrue(provenance.queuePlaybackSourceEquivalent(queueE2, movedBackE4))
        assertEquals(e2, provenance.producerTokenOf(queueE2))
        assertEquals(e3, provenance.producerTokenOf(movedE3))
        assertEquals(e4, provenance.producerTokenOf(movedBackE4))
    }

    @Test
    fun addThenRemoveBackToHistoricalQueueStillCarriesFreshProducer() {
        val provenance = PlaybackTopologyMedia3Provenance(epoch1)
        val a = item(uri = "file:///music/a.flac", title = "A")
        val b = item(uri = "file:///music/b.flac", title = "B")
        val e2 = PlaybackTopologyProducerToken(stackId, PlaybackTopologyEpoch(2))
        val e3 = PlaybackTopologyProducerToken(stackId, PlaybackTopologyEpoch(3))
        val e4 = PlaybackTopologyProducerToken(stackId, PlaybackTopologyEpoch(4))

        val queueE2 = provenance.tagForProducer(listOf(a), e2)
        val addedE3 = provenance.tagForProducer(listOf(a, b), e3)
        val removedBackE4 = provenance.tagForProducer(listOf(a), e4)

        assertTrue(provenance.queuePlaybackSourceEquivalent(queueE2, removedBackE4))
        assertEquals(e2, provenance.producerTokenOf(queueE2))
        assertEquals(e3, provenance.producerTokenOf(addedE3))
        assertEquals(e4, provenance.producerTokenOf(removedBackE4))
    }

    private fun protocolReservation(
        token: PlaybackTopologyProducerToken,
        seam: String,
    ): ProtocolTopologyReservation = ProtocolTopologyReservation(
        reservationId = TopologyReservationId(token.epoch.value),
        stackId = token.stackId,
        seam = seam,
        kind = TopologyCommitKind.TOPOLOGY_ONLY,
        targetMediaId = null,
        reservedMutationId = null,
    )

    private fun item(uri: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId("same-media-id")
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
}
