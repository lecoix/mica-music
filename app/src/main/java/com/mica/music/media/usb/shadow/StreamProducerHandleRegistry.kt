@file:UnstableApi

package com.mica.music.media.usb.shadow

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.SampleStream
import com.mica.music.media.usb.protocol.PlaybackOccurrence
import com.mica.music.media.usb.protocol.PlaybackStackId

@JvmInline
internal value class StreamSourceInstanceId(val value: Long)

@JvmInline
internal value class StreamPeriodInstanceId(val value: Long)

/**
 * Immutable causal carrier captured when Media3 assigns an exact MediaPeriodId to one stamped
 * MediaSource. Renderer callbacks may use only the handle/opaque id attached to that stream
 * assignment; occurrence-only lookup is never an authority path.
 */
internal data class StreamProducerHandle(
    val stackId: PlaybackStackId,
    val producerToken: PlaybackTopologyProducerToken,
    val occurrence: PlaybackOccurrence,
    val sourceInstanceId: StreamSourceInstanceId,
    val periodInstanceId: StreamPeriodInstanceId,
)

@UnstableApi
internal class StreamProducerHandleSampleStream(
    val handle: StreamProducerHandle,
    val delegate: SampleStream,
) : SampleStream by delegate

@UnstableApi
internal fun SampleStream?.producerHandle(): StreamProducerHandle? {
    var current = this
    while (current is StreamProducerHandleSampleStream) {
        return current.handle
    }
    return null
}

internal class StreamProducerHandleRegistry(
    private val stackId: PlaybackStackId,
) {
    private var nextSourceInstance = 0L
    private var nextPeriodInstance = 0L
    private val active = linkedMapOf<StreamPeriodInstanceId, StreamProducerHandle>()

    @Synchronized
    fun newSourceInstanceId(): StreamSourceInstanceId = StreamSourceInstanceId(++nextSourceInstance)

    @Synchronized
    fun capture(
        sourceInstanceId: StreamSourceInstanceId,
        producerToken: PlaybackTopologyProducerToken,
        occurrence: PlaybackOccurrence,
    ): StreamProducerHandle? {
        if (producerToken.stackId != stackId) return null
        val handle = StreamProducerHandle(
            stackId = stackId,
            producerToken = producerToken,
            occurrence = occurrence,
            sourceInstanceId = sourceInstanceId,
            periodInstanceId = StreamPeriodInstanceId(++nextPeriodInstance),
        )
        active[handle.periodInstanceId] = handle
        while (active.size > MAX_ACTIVE_HANDLES) {
            active.remove(active.keys.first())
        }
        return handle
    }

    @Synchronized
    fun redeem(periodInstanceId: StreamPeriodInstanceId): StreamProducerHandle? =
        active[periodInstanceId]

    @Synchronized
    fun release(handle: StreamProducerHandle) {
        if (active[handle.periodInstanceId] == handle) active.remove(handle.periodInstanceId)
    }

    @Synchronized
    fun releaseSource(sourceInstanceId: StreamSourceInstanceId) {
        active.entries.removeAll { it.value.sourceInstanceId == sourceInstanceId }
    }

    @Synchronized
    fun activeCount(): Int = active.size

    private companion object {
        const val MAX_ACTIVE_HANDLES = 128
    }
}
