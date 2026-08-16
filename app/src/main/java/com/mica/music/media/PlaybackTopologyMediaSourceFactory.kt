package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.Allocator
import com.mica.music.media.usb.shadow.StreamProducerHandle
import com.mica.music.media.usb.shadow.StreamProducerHandleRegistry
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
    private val periodHandles = IdentityHashMap<MediaPeriod, StreamProducerHandle>()

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod {
        val period = super.createPeriod(id, allocator, startPositionUs)
        if (producerToken != null) {
            streamProducerHandles.capture(
                sourceInstanceId = sourceInstanceId,
                producerToken = producerToken,
                occurrence = UsbExclusiveShadowMedia3Facts.occurrence(id),
            )?.let { handle -> periodHandles[period] = handle }
        }
        return period
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        periodHandles.remove(mediaPeriod)?.let(streamProducerHandles::release)
        super.releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        try {
            super.releaseSourceInternal()
        } finally {
            periodHandles.values.toList().forEach(streamProducerHandles::release)
            periodHandles.clear()
            streamProducerHandles.releaseSource(sourceInstanceId)
        }
    }
}
