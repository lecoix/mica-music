package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.mica.music.media.usb.protocol.PlaybackStackId
import com.mica.music.media.usb.shadow.PlaybackTopologyEpoch
import com.mica.music.media.usb.shadow.PlaybackTopologyMutationReservation
import com.mica.music.media.usb.shadow.PlaybackTopologyProducerToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(provenance.queueIdentityEquivalent(listOf(tagged), listOf(preserved)))
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

    private fun item(uri: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId("same-media-id")
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
}
