package com.mica.music.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMediaIdCodecTest {
    @Test
    fun `same opaque id on different sources stays distinct`() {
        val a = RemoteMediaIdCodec.encode(RemoteTrackRef("source-a", "track-1"))
        val b = RemoteMediaIdCodec.encode(RemoteTrackRef("source-b", "track-1"))

        assertTrue(a != b)
    }

    @Test
    fun `round trip preserves arbitrary opaque ids`() {
        val ref = RemoteTrackRef("家里 NAS / 一号", "album/01 - 曲目.flac?opaque=yes")
        val encoded = RemoteMediaIdCodec.encode(ref)

        assertEquals(ref, RemoteMediaIdCodec.decode(encoded))
        assertTrue(RemoteMediaIdCodec.isRemoteId(encoded))
    }

    @Test
    fun `non remote and malformed ids are rejected`() {
        assertFalse(RemoteMediaIdCodec.isRemoteId("library-song"))
        assertNull(RemoteMediaIdCodec.decode("library-song"))
        assertNull(RemoteMediaIdCodec.decode("mica.remote.v1.invalid"))
        assertNull(RemoteMediaIdCodec.decode("mica.remote.v1.a.b.c"))
    }
}
