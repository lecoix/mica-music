package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.mica.music.media.usb.shadow.StreamProducerHandle
import com.mica.music.media.usb.shadow.StreamProducerHandleRegistry
import com.mica.music.media.usb.shadow.StreamProducerHandleSampleStream
import com.mica.music.media.usb.shadow.StreamSourceInstanceId
import com.mica.music.media.usb.shadow.UsbExclusiveShadowMedia3Facts
import java.util.IdentityHashMap

/**
 * Media3 source wrapper that captures producer provenance at createPeriod, before renderer callback
 * scheduling can reorder observations. No structural/current-timeline lookup participates.
 */
@UnstableApi
internal class PlaybackTopologyMediaSourceFactory(
    private val delegate: MediaSource.Factory,
    private val provenance: PlaybackTopologyMedia3Provenance,
    private val streamProducerHandles: StreamProducerHandleRegistry,
) : MediaSource.Factory by delegate {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val source = delegate.createMediaSource(mediaItem)
        return PlaybackTopologyMediaSource(
            delegate = source,
            producerToken = provenance.producerTokenOf(mediaItem),
            streamProducerHandles = streamProducerHandles,
            sourceInstanceId = streamProducerHandles.newSourceInstanceId(),
        )
    }
}

@UnstableApi
private class PlaybackTopologyMediaSource(
    delegate: MediaSource,
    private val producerToken: com.mica.music.media.usb.shadow.PlaybackTopologyProducerToken?,
    private val streamProducerHandles: StreamProducerHandleRegistry,
    private val sourceInstanceId: StreamSourceInstanceId,
) : WrappingMediaSource(delegate) {
    private val wrappedPeriods = IdentityHashMap<MediaPeriod, StreamProducerHandleMediaPeriod>()

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod {
        val period = super.createPeriod(id, allocator, startPositionUs)
        val handle = producerToken?.let { token ->
            streamProducerHandles.capture(
                sourceInstanceId = sourceInstanceId,
                producerToken = token,
                occurrence = UsbExclusiveShadowMedia3Facts.occurrence(id),
            )
        } ?: return period
        val wrapped = StreamProducerHandleMediaPeriod(period, handle)
        wrappedPeriods[wrapped] = wrapped
        return wrapped
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val wrapped = wrappedPeriods.remove(mediaPeriod)
        if (wrapped != null) {
            streamProducerHandles.release(wrapped.handle)
            super.releasePeriod(wrapped.delegate)
        } else {
            super.releasePeriod(mediaPeriod)
        }
    }

    override fun releaseSourceInternal() {
        try {
            super.releaseSourceInternal()
        } finally {
            wrappedPeriods.values.forEach { wrapped ->
                streamProducerHandles.release(wrapped.handle)
            }
            wrappedPeriods.clear()
            streamProducerHandles.releaseSource(sourceInstanceId)
        }
    }
}

@UnstableApi
internal class StreamProducerHandleMediaPeriod(
    val delegate: MediaPeriod,
    val handle: StreamProducerHandle,
) : MediaPeriod by delegate {
    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        val innerStreams = Array(streams.size) { index -> streams[index].unwrapProducerHandle() }
        val result = delegate.selectTracks(
            selections,
            mayRetainStreamFlags,
            innerStreams,
            streamResetFlags,
            positionUs,
        )
        for (index in streams.indices) {
            val inner = innerStreams[index]
            val previous = streams[index]
            streams[index] = when {
                inner == null -> null
                previous is StreamProducerHandleSampleStream &&
                    previous.handle == handle &&
                    previous.delegate === inner -> previous
                else -> StreamProducerHandleSampleStream(handle, inner)
            }
        }
        return result
    }
}

@UnstableApi
private fun SampleStream?.unwrapProducerHandle(): SampleStream? {
    var current = this
    while (current is StreamProducerHandleSampleStream) {
        current = current.delegate
    }
    return current
}
