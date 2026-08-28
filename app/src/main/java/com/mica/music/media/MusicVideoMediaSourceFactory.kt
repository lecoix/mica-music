package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** Builds one Media3 timeline whose audio is the song and whose optional video is a local sidecar. */
@UnstableApi
internal class MusicVideoMediaSourceFactory(
    private val delegate: MediaSource.Factory,
    private val isEnabledFor: (MediaItem) -> Boolean,
) : MediaSource.Factory by delegate {

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val audioSource = delegate.createMediaSource(mediaItem)
        val musicVideoUri = mediaItem.mediaMetadata.extras
            ?.getString(MUSIC_VIDEO_URI_EXTRA)
            ?.takeIf(String::isNotBlank)
        if (musicVideoUri == null || !isEnabledFor(mediaItem)) return audioSource

        val videoItem = MediaItem.Builder()
            .setMediaId("${mediaItem.mediaId}:music-video")
            .setUri(musicVideoUri)
            .build()
        val audioOnly = FilteringMediaSource(audioSource, C.TRACK_TYPE_AUDIO)
        val videoOnly = FilteringMediaSource(
            delegate.createMediaSource(videoItem),
            C.TRACK_TYPE_VIDEO,
        )
        return MergingMediaSource(
            /* adjustPeriodTimeOffsets = */ true,
            /* clipDurations = */ false,
            audioOnly,
            videoOnly,
        )
    }

    companion object {
        internal const val MUSIC_VIDEO_URI_EXTRA = "mica.musicVideoUri"
    }
}
