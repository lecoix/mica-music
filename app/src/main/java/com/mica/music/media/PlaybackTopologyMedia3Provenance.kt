package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.mica.music.media.usb.shadow.PlaybackTopologyMutationReservation
import com.mica.music.media.usb.shadow.PlaybackTopologyProducerToken

/**
 * App-owned producer provenance carried by the Media3 representation itself.
 *
 * The producer token is deliberately excluded from playback-source equivalence. A real topology
 * mutation stamps the whole resulting queue with one token before Exo dispatch, while a
 * presentation-only update preserves the existing stamp. Callback attribution is extracted only
 * from that callback's own stamped representation; structural timeline history is never an epoch
 * classifier.
 */
@UnstableApi
internal class PlaybackTopologyMedia3Provenance(
    initialToken: PlaybackTopologyProducerToken,
) {
    private data class ProducerTag(
        val token: PlaybackTopologyProducerToken,
        val originalTag: Any?,
    )

    private var currentToken: PlaybackTopologyProducerToken = initialToken
    private var pendingReservation: PlaybackTopologyMutationReservation? = null

    fun currentToken(): PlaybackTopologyProducerToken = currentToken

    fun playbackSourceEquivalent(left: MediaItem, right: MediaItem): Boolean =
        sourceComparable(left) == sourceComparable(right)

    fun queuePlaybackSourceEquivalent(left: List<MediaItem>, right: List<MediaItem>): Boolean =
        left.size == right.size && left.indices.all { playbackSourceEquivalent(left[it], right[it]) }

    fun preserveProducerTag(previous: MediaItem, replacement: MediaItem): MediaItem {
        val producerTag = previous.localConfiguration?.tag
        return replacement.buildUpon().setTag(producerTag).build()
    }

    fun tagForProducer(item: MediaItem, token: PlaybackTopologyProducerToken): MediaItem =
        item.buildUpon()
            .setTag(ProducerTag(token, originalTag(item)))
            .build()

    fun tagForProducer(
        items: List<MediaItem>,
        token: PlaybackTopologyProducerToken,
    ): List<MediaItem> = items.map { tagForProducer(it, token) }

    fun producerTokenOf(item: MediaItem?): PlaybackTopologyProducerToken? =
        (item?.localConfiguration?.tag as? ProducerTag)?.token

    fun producerTokenOf(items: List<MediaItem>): PlaybackTopologyProducerToken? {
        if (items.isEmpty()) return null
        var token: PlaybackTopologyProducerToken? = null
        items.forEach { item ->
            val itemToken = producerTokenOf(item) ?: return null
            val previous = token
            if (previous != null && previous != itemToken) return null
            token = itemToken
        }
        return token
    }

    /**
     * Extracts one exact producer token from a callback-owned non-empty Timeline. Tokenless or
     * mixed queues are intentionally not attributable; no structural comparison/history fallback
     * is allowed.
     */
    fun producerTokenOf(timeline: Timeline): PlaybackTopologyProducerToken? {
        if (timeline.windowCount <= 0) return null
        return producerTokenOf(
            (0 until timeline.windowCount).map { index ->
                timeline.getWindow(index, Timeline.Window()).mediaItem
            },
        )
    }

    /**
     * Installs only producer-side pending state. The protocol reservation is the authority gate;
     * this method merely proves that every resulting queue item already carries its reserved token.
     */
    fun prepare(
        reservation: PlaybackTopologyMutationReservation,
        expectedItems: List<MediaItem>,
    ): Boolean {
        if (reservation.baseToken != currentToken || pendingReservation != null) return false
        if (expectedItems.any { producerTokenOf(it) != reservation.producerToken }) return false
        pendingReservation = reservation
        return true
    }

    fun canCommit(reservation: PlaybackTopologyMutationReservation): Boolean =
        pendingReservation == reservation && reservation.baseToken == currentToken

    fun commit(reservation: PlaybackTopologyMutationReservation): Boolean {
        if (!canCommit(reservation)) return false
        pendingReservation = null
        currentToken = reservation.producerToken
        return true
    }

    fun abort(reservation: PlaybackTopologyMutationReservation): Boolean {
        if (pendingReservation != reservation) return false
        pendingReservation = null
        return true
    }

    private fun originalTag(item: MediaItem): Any? =
        when (val tag = item.localConfiguration?.tag) {
            is ProducerTag -> tag.originalTag
            else -> tag
        }

    private fun sourceComparable(item: MediaItem): MediaItem =
        item.buildUpon()
            .setMediaMetadata(MediaMetadata.EMPTY)
            .setTag(originalTag(item))
            .build()
}
