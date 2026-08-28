package com.mica.music.media

import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MusicVideoMediaSourceFactoryTest {

    @Test
    fun enabledMatchedSongCreatesOneAudioVideoTimeline() {
        val requestedUris = mutableListOf<String>()
        val delegate = object : MediaSource.Factory {
            override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = this

            override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = this

            override fun getSupportedTypes(): IntArray = intArrayOf()

            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                requestedUris += mediaItem.localConfiguration?.uri.toString()
                return mockk {
                    every { this@mockk.mediaItem } returns mediaItem
                }
            }
        }
        val item = MediaItem.Builder()
            .setMediaId("song-1")
            .setUri("content://music/song.flac")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setExtras(bundleOf(MusicVideoMediaSourceFactory.MUSIC_VIDEO_URI_EXTRA to "content://music/song.mp4"))
                    .build(),
            )
            .build()

        val source = MusicVideoMediaSourceFactory(delegate) { true }.createMediaSource(item)

        assertTrue(
            "expected merged source, actual=${source::class.java.name}, requests=$requestedUris, " +
                "extra=${item.mediaMetadata.extras?.getString(MusicVideoMediaSourceFactory.MUSIC_VIDEO_URI_EXTRA)}",
            source is MergingMediaSource,
        )
        assertEquals(item, source.mediaItem)
        assertEquals(
            listOf("content://music/song.flac", "content://music/song.mp4"),
            requestedUris,
        )
    }
}
