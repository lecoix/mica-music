package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.mica.music.media.usb.shadow.PlaybackTopologyMutationReservation
import com.mica.music.media.usb.shadow.PlaybackTopologyProducerToken

/**
 * Carries the app-owned playback-topology provenance through Media3's asynchronous Timeline and
 * MediaItem callbacks. Presentation [MediaMetadata] is deliberately excluded from source identity.
 *
 * A pending record exists before canonical Exo dispatch so callbacks emitted synchronously by that
 * dispatch already resolve to the reserved token. Commit/abort only changes which record remains
 * authoritative; neither operation calls into Exo or holds a framework/native side effect.
 */
@UnstableApi
internal class PlaybackTopologyMedia3Provenance(
    initialToken: PlaybackTopologyProducerToken,
) {
    private data class ProducerTag(
        val token: PlaybackTopologyProducerToken,
        val originalTag: Any?,
    )

    private data class TimelineIdentity(
        val items: List<MediaItem>,
    )

    private var currentToken: PlaybackTopologyProducerToken = initialToken
    private val committed = linkedMapOf<PlaybackTopologyProducerToken, TimelineIdentity>()
    private val pending = linkedMapOf<PlaybackTopologyProducerToken, TimelineIdentity>()

    init {
        committed[initialToken] = TimelineIdentity(emptyList())
    }

    fun currentToken(): PlaybackTopologyProducerToken = currentToken

    fun playbackSourceEquivalent(left: MediaItem, right: MediaItem): Boolean =
        sourceComparable(left) == sourceComparable(right)

    fun preserveProducerTag(previous: MediaItem, replacement: MediaItem): MediaItem {
        val tag = previous.localConfiguration?.tag
        return replacement.buildUpon().setTag(tag).build()
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

    fun prepare(
        reservation: PlaybackTopologyMutationReservation,
        expectedItems: List<MediaItem>,
    ): Boolean {
        if (reservation.baseToken != currentToken) return false
        if (pending.isNotEmpty()) return false
        pending[reservation.producerToken] = TimelineIdentity(expectedItems.map(::producerComparable))
        return true
    }

    fun canCommit(reservation: PlaybackTopologyMutationReservation): Boolean =
        reservation.baseToken == currentToken && reservation.producerToken in pending

    fun commit(reservation: PlaybackTopologyMutationReservation): Boolean {
        if (!canCommit(reservation)) return false
        val identity = pending.remove(reservation.producerToken) ?: return false
        committed[reservation.producerToken] = identity
        currentToken = reservation.producerToken
        while (committed.size > MAX_COMMITTED_HISTORY) {
            val oldest = committed.keys.firstOrNull { it != currentToken } ?: break
            committed.remove(oldest)
        }
        return true
    }

    fun abort(reservation: PlaybackTopologyMutationReservation): Boolean =
        pending.remove(reservation.producerToken) != null

    fun resolve(timeline: Timeline): PlaybackTopologyProducerToken? {
        val identity = timelineIdentity(timeline)
        val matches = buildList {
            pending.forEach { (token, expected) -> if (expected == identity) add(token) }
            committed.forEach { (token, expected) -> if (expected == identity) add(token) }
        }.distinct()
        return matches.singleOrNull()
    }

    fun queueIdentityEquivalent(left: List<MediaItem>, right: List<MediaItem>): Boolean =
        TimelineIdentity(left.map(::producerComparable)) ==
            TimelineIdentity(right.map(::producerComparable))

    private fun timelineIdentity(timeline: Timeline): TimelineIdentity = TimelineIdentity(
        (0 until timeline.windowCount).map { index ->
            producerComparable(timeline.getWindow(index, Timeline.Window()).mediaItem)
        },
    )

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

    private fun producerComparable(item: MediaItem): MediaItem =
        item.buildUpon()
            .setMediaMetadata(MediaMetadata.EMPTY)
            .build()

    private companion object {
        const val MAX_COMMITTED_HISTORY = 8
    }
}
