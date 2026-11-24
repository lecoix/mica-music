package com.mica.music.data.remote.emby

import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.RemoteArtworkRef
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

    @Test fun catalogReadsAudioFormatFromTheSelectedMediaSource() = runTest {
        val requests = mutableListOf<String>()
        val transport = EmbyJsonTransport { url, _ ->
            requests += url
            """{"Items":[{"Id":"format","Type":"Audio","MediaSources":[
                {"DefaultAudioStreamIndex":7,"MediaStreams":[
                    {"Index":0,"Type":"Video","BitDepth":8},
                    {"Index":2,"Type":"Audio","IsDefault":true,"SampleRate":44100,"BitDepth":16,"Channels":2},
                    {"Index":7,"Type":"Audio","SampleRate":96000,"BitDepth":24,"Channels":6}
                ]},
                {"MediaStreams":[{"Index":7,"Type":"Audio","SampleRate":192000,"BitDepth":32,"Channels":8}]}
            ]}],"TotalRecordCount":1}"""
        }
        val track = EmbyCatalogClient(owner(), credentials, transport)
            .beginSession().page(0, 1).tracks.single()

        assertEquals(96000, track.sampleRateHz)
        assertEquals(24, track.bitsPerSample)
        assertEquals(6, track.channelCount)
        assertTrue(requests.single().contains("MediaSources"))
    }

    @Test fun catalogPreservesUnknownAudioFormatFields() = runTest {
        val transport = EmbyJsonTransport { _, _ ->
            """{"Items":[
                {"Id":"mp3","Type":"Audio","MediaSources":[{"MediaStreams":[
                    {"Type":"Audio","SampleRate":44100,"Channels":1}
                ]}]},
                {"Id":"default-audio","Type":"Audio","MediaSources":[{"DefaultAudioStreamIndex":0,"MediaStreams":[
                    {"Index":0,"Type":"Video","BitDepth":8},
                    {"Index":1,"Type":"Audio","SampleRate":44100,"BitDepth":16,"Channels":2},
                    {"Index":2,"Type":"Audio","IsDefault":true,"SampleRate":48000,"BitDepth":0,"Channels":-1}
                ]}]},
                {"Id":"no-audio","Type":"Audio","MediaSources":[{"MediaStreams":[{"Type":"Video","BitDepth":8}]}]},
                {"Id":"no-source","Type":"Audio"}
            ],"TotalRecordCount":4}"""
        }
        val (mp3, defaultAudio, noAudio, noSource) = EmbyCatalogClient(owner(), credentials, transport)
            .beginSession().page(0, 4).tracks

        assertEquals(44100, mp3.sampleRateHz)
        assertEquals(null, mp3.bitsPerSample)
        assertEquals(1, mp3.channelCount)
        assertEquals(48000, defaultAudio.sampleRateHz)
        for (track in listOf(defaultAudio, noAudio, noSource)) {
            assertEquals(null, track.bitsPerSample)
            assertEquals(0, track.channelCount)
        }
        assertEquals(0, noAudio.sampleRateHz)
        assertEquals(0, noSource.sampleRateHz)
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

    @Test fun mediaStreamEmbeddedImageWithoutServerImageTagsGetsLazyTrackArtReference() = runTest {
        val source = owner()
        val body = """{"Items":[
            {"Id":"real-flac","Type":"Audio","MediaSources":[{"Size":28542419,
                "MediaStreams":[{"Type":"Audio","SampleRate":44100},
                    {"Index":1,"Type":"EmbeddedImage","Codec":"mjpeg"}]}]},
            {"Id":"no-cover","Type":"Audio","MediaSources":[{"Size":9999,
                "MediaStreams":[{"Type":"Audio"}]}]},
            {"Id":"album-only","Type":"Audio","AlbumId":"album-3","AlbumPrimaryImageTag":"v1",
                "MediaSources":[{"Size":9999,"MediaStreams":[{"Type":"EmbeddedImage"}]}]}
        ],"TotalRecordCount":3}"""
        val transport = EmbyJsonTransport { _, _ -> body }
        val page = EmbyCatalogClient(source, credentials, transport).beginSession().page(0, 3)
        val embedded = RemoteEmbeddedArtworkIdCodec.decode(page.tracks[0].artworkOpaqueId)
        assertEquals("real-flac", embedded?.resourceId)
        assertEquals(28542419L, embedded?.sizeBytes)
        assertEquals("", page.tracks[1].artworkOpaqueId)
        assertEquals("album-3", page.tracks[2].artworkOpaqueId)
        val request = EmbyArtworkRequestResolver({ source }, credentials)
            .resolve(RemoteArtworkRef("emby-1", page.tracks[0].artworkOpaqueId))!!
        assertEquals(28542419L, request.embeddedSizeBytes)
        assertTrue(request.url.endsWith("/emby/Audio/real-flac/stream?static=true"))
        assertFalse(request.url.contains(token))
        assertFalse(request.toString().contains(token))
    }

    @Test fun subtitleUrlValidatesIdentifiersAndDoesNotContainCredentials() {
        val url = EmbyProtocol.lyricsSubtitle(owner().snapshot(), "t1", "mediasource_11", 2)
        assertTrue(url.endsWith("/emby/Videos/t1/mediasource_11/Subtitles/2/Stream.lrc"))
        assertFalse(url.contains(token))
        try {
            EmbyProtocol.lyricsSubtitle(owner().snapshot(), "t1", "../../evil", 2)
            error("Unsafe Emby media source id accepted")
        } catch (_: IllegalArgumentException) { }
    }

}
