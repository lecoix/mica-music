package com.mica.music.data.remote.emby

import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.SecureRemoteCredentialStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmbyProtocolTest {
    private fun owner() = RemoteSourceOwner(RemoteSourceInstance("emby-1", RemoteSourceType.EMBY, "Home", "https://music.example", "secure-ref"))
    private val token = "TOKEN_SHOULD_NOT_BE_PERSISTED"
    private val credentials = SecureRemoteCredentialStore { ref -> RemoteCredentialSnapshot(ref, 1L, RemoteCredentialMaterial.BearerToken("", token)) }

    @Test fun directStreamUsesStaticWithoutSecretInUrl() {
        val url = EmbyProtocol.stream(owner().snapshot(), "track-1")
        assertEquals("https://music.example/emby/Audio/track-1/stream?static=true", url)
        assertFalse(url.contains(token))
    }

    @Test fun apiKeyExistsOnlyInEphemeralHeaders() = runTest {
        val source = owner()
        val mediaId = RemoteMediaIdCodec.encode(RemoteTrackRef("emby-1", "track-1"))
        val resolver = EmbyStreamRequestResolver({ source }, credentials)
        val request = resolver.resolve(mediaId)!!
        assertEquals(token, request.requestHeaders[EmbyProtocol.TOKEN_HEADER])
        assertFalse(request.url.contains(token))
        assertFalse(request.toString().contains(token))
        assertFalse(mediaId.contains(token))
        assertEquals(null, resolver.resolve("untrusted-media-id"))
        source.replace(source.snapshot().instance.copy(enabled = false))
        assertEquals(null, resolver.resolve(mediaId))
    }

    @Test fun embyCatalogReadsOnlyAudioAndRejectsStaleSource() = runTest {
        val source = owner()
        val requests = mutableListOf<String>()
        val transport = EmbyJsonTransport { url, key ->
            requests += url
            assertEquals(token, key)
            """{"Items":[{"Id":"track-1","Type":"Audio","Name":"Song","Album":"Album","Artists":["Artist"],"RunTimeTicks":720000000,"ImageTags":{"Primary":"v1"}},{"Id":"video-1","Type":"Video","Name":"Video"}],"TotalRecordCount":2}"""
        }
        val session = EmbyCatalogClient(source, credentials, transport).beginSession()
        val page = session.page(0, 2)
        assertEquals(2, page.rawCount)
        assertEquals(1, page.tracks.size)
        assertEquals("Artist", page.tracks[0].artist)
        assertEquals(72, page.tracks[0].durationSec)
        assertEquals("track-1", page.tracks[0].artworkOpaqueId)
        assertTrue(requests.single().contains("IncludeItemTypes=Audio"))
        assertFalse(requests.single().contains(token))
        source.replace(source.snapshot().instance.copy(displayName = "Updated"))
        try { session.page(0, 2); error("stale session accepted") }
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("changed")) }
    }

    @Test fun albumPrimaryArtworkIsUsedWhenAudioHasNoPrimary() = runTest {
        val requests = mutableListOf<String>()
        val transport = EmbyJsonTransport { url, key ->
            assertEquals(token, key)
            requests += url
            """{"Items":[
                {"Id":"tone-11","Type":"Audio","AlbumId":"album-37","AlbumPrimaryImageTag":"album-v1","ImageTags":{}},
                {"Id":"tone-12","Type":"Audio","AlbumId":"album-38","ImageTags":{}},
                {"Id":"tone-13","Type":"Audio","AlbumPrimaryImageTag":"album-v2","ImageTags":{}}
            ],"TotalRecordCount":3}"""
        }
        val page = EmbyCatalogClient(owner(), credentials, transport).beginSession().page(0, 3)
        assertEquals("album-37", page.tracks[0].artworkOpaqueId)
        assertEquals("", page.tracks[1].artworkOpaqueId)
        assertEquals("", page.tracks[2].artworkOpaqueId)
        assertTrue(requests.single().contains("AlbumPrimaryImageTag"))
        assertFalse(requests.single().contains(token))
        assertTrue(EmbyProtocol.artwork(owner().snapshot(), page.tracks[0].artworkOpaqueId)
            .endsWith("/emby/Items/album-37/Images/Primary?maxWidth=800"))
    }

    @Test fun audioPrimaryArtworkTakesPrecedenceOverAlbumPrimary() = runTest {
        val transport = EmbyJsonTransport { _, key ->
            assertEquals(token, key)
            """{"Items":[{"Id":"tone-1","Type":"Audio","AlbumId":"album-33",
                "ImageTags":{"Primary":"track-v1"},"AlbumPrimaryImageTag":"album-v1"}],"TotalRecordCount":1}"""
        }
        val page = EmbyCatalogClient(owner(), credentials, transport).beginSession().page(0, 1)
        assertEquals("tone-1", page.tracks.single().artworkOpaqueId)
        assertTrue(EmbyProtocol.artwork(owner().snapshot(), page.tracks.single().artworkOpaqueId)
            .endsWith("/emby/Items/tone-1/Images/Primary?maxWidth=800"))
    }
}
