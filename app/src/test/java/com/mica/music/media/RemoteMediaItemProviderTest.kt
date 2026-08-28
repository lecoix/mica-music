package com.mica.music.media

import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemotePlaybackUriCodec
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.RemoteTrackSummaryLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteMediaItemProviderTest {
    @Test
    fun `remote media item stores stable internal uri instead of authenticated transport url`() = runTest {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        val ref = RemoteTrackRef("nav-1", "track-9")
        val summary = RemoteTrackSummary(
            ref = ref,
            title = "Remote Song",
            artist = "Artist",
            album = "Album",
            durationSec = 123,
            mimeTypeHint = "audio/flac",
        )
        val mediaId = RemoteMediaIdCodec.encode(ref)
        val provider = TrustedRemoteMediaItemProvider(
            RemoteTrackSummaryLookup { refs ->
                assertEquals(listOf(ref), refs)
                mapOf(ref to summary)
            },
        )

        val item = provider.resolve(listOf(mediaId)).getValue(mediaId)

        assertEquals(mediaId, item.mediaId)
        assertEquals(RemotePlaybackUriCodec.encode(mediaId), item.localConfiguration?.uri?.toString())
        assertEquals("audio/flac", item.localConfiguration?.mimeType)
        assertEquals("Remote Song", item.mediaMetadata.title?.toString())
        assertNull(item.mediaMetadata.extras)
    }

    @Test
    fun `unknown summary is dropped`() = runTest {
        val ref = RemoteTrackRef("nav-1", "missing")
        val mediaId = RemoteMediaIdCodec.encode(ref)
        val provider = TrustedRemoteMediaItemProvider(RemoteTrackSummaryLookup { emptyMap() })

        assertEquals(emptyMap<String, androidx.media3.common.MediaItem>(), provider.resolve(listOf(mediaId)))
    }
}
