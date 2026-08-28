package com.mica.music.data.remote

import com.mica.music.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteArtworkUriCodecTest {
    @Test
    fun stableArtworkUriRoundTripsOpaqueIdsWithoutAuthenticationState() {
        val ref = RemoteArtworkRef("nav/source 1", "cover/id 9")

        val encoded = RemoteArtworkUriCodec.encode(ref)

        assertEquals(ref, RemoteArtworkUriCodec.decode(encoded))
        assertTrue(encoded.startsWith("content://${BuildConfig.APPLICATION_ID}.remoteart/"))
        assertTrue(!encoded.contains("password") && !encoded.contains("token"))
    }

    @Test
    fun sourceScopedDecodeRejectsArtworkFromAnotherSource() {
        val encoded = RemoteArtworkUriCodec.encode(RemoteArtworkRef("nav-1", "cover-1"))

        assertNull(RemoteArtworkUriCodec.decodeForSource(encoded, "nav-2"))
        assertNull(RemoteArtworkUriCodec.decode("https://music.example/rest/getCoverArt?id=cover-1&t=secret"))
    }
}
