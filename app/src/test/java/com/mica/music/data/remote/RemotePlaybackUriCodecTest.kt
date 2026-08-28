package com.mica.music.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemotePlaybackUriCodecTest {
    @Test
    fun `playback uri round trip contains only stable remote media id`() {
        val mediaId = RemoteMediaIdCodec.encode(RemoteTrackRef("source-a", "track-1"))
        val uri = RemotePlaybackUriCodec.encode(mediaId)

        assertEquals("mica-remote://track/$mediaId", uri)
        assertEquals(mediaId, RemotePlaybackUriCodec.decode(uri))
    }

    @Test
    fun `non remote playback uris are rejected`() {
        assertNull(RemotePlaybackUriCodec.decode("https://music.example/rest/stream?t=secret"))
    }
}
