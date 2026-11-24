package com.mica.music.data.remote.emby

import com.mica.music.data.LyricsOrigin
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.TrackMetadata
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmbyLyricsLoaderTest {
    private val secret = "NEVER_SHOW_MY_KEY"
    private val credentials = SecureRemoteCredentialStore {
        ref -> RemoteCredentialSnapshot(ref, 3L, RemoteCredentialMaterial.BearerToken("", secret))
    }
    private fun owner() = RemoteSourceOwner(
        RemoteSourceInstance("emby-test", RemoteSourceType.EMBY, "Home", "https://music.example", "credential"),
    )
    private fun song(id: String = "track-one") = Song(
        id = RemoteMediaIdCodec.encode(RemoteTrackRef("emby-test", id)),
        title = "Track", artist = "Artist", album = "Album", durationSec = 65,
        metadata = TrackMetadata("FLAC", 44_100, 16, 1000, 2, "audio/flac"),
        albumArtUri = null, coverColorArgb = 0, mediaUri = "content://remote",
        lyricsLoaded = false, source = SongSource.REMOTE,
    )

    @Test fun embeddedLyricsComeFromTheSelectedMediaStreamWithoutAdditionalHttp() = runTest {
        val owner = owner()
        var requested = ""
        var streamCalls = 0
        val transport = EmbyJsonTransport { url, apiKey ->
            requested = url
            assertEquals(secret, apiKey)
            """{"Items":[{"Id":"track-one","Type":"Audio","MediaSources":[{"Id":"ms-one",
                "MediaStreams":[{"Index":0,"Type":"Audio"},{"Index":1,"Type":"Subtitle",
                    "Codec":"text","Title":"Lyrics","Extradata":"[00:01.00]Real embedded lyrics"}]}]}]}"""
        }
        val subtitles = EmbySubtitleTransport { _, _ -> streamCalls++; error("Unnecessary request") }
        val document = EmbyLyricsLoader({ owner }, credentials, transport, subtitles).load(song())
        assertEquals(0, streamCalls)
        assertEquals(LyricsOrigin.EMBEDDED, document.origin)
        assertEquals("Real embedded lyrics", document.lines.first().parts.first().text)
        assertTrue(requested.contains("Ids=track-one"))
        assertFalse(requested.contains(secret))
    }

    @Test fun externalLrcUsesAuthenticatedEmbySubtitleEndpointWithNoPathExposure() = runTest {
        val owner = owner()
        var fetchedUrl = ""
        var calls = 0
        val transport = EmbyJsonTransport { url, key ->
            assertEquals(secret, key)
            assertFalse(url.contains(secret))
            """{"Items":[{"Id":"track-one","Type":"Audio","MediaSources":[{"Id":"mediasource_10117",
                "MediaStreams":[{"Index":2,"Type":"Subtitle","Codec":"lrc",
                    "IsExternal":true,"Path":"E:\\Private\\Audio\\song.lrc"}]}]}]}"""
        }
        val subtitles = EmbySubtitleTransport { url, key ->
            calls++
            fetchedUrl = url
            assertEquals(secret, key)
            "[00:10.00]Actual sidecar text".toByteArray()
        }
        val document = EmbyLyricsLoader({ owner }, credentials, transport, subtitles).load(song())
        assertEquals(1, calls)
        assertEquals(LyricsOrigin.EXTERNAL, document.origin)
        assertEquals("Actual sidecar text", document.lines.single().parts.single().text)
        assertTrue(fetchedUrl.endsWith("/emby/Videos/track-one/mediasource_10117/Subtitles/2/Stream.lrc"))
        assertFalse(fetchedUrl.contains(secret))
        assertFalse(fetchedUrl.contains("Private"))
    }

    @Test fun missingLyricsAreAnEmptyDocumentAndDoNotFetchSubtitles() = runTest {
        val owner = owner()
        val json = EmbyJsonTransport { _, _ ->
            """{"Items":[{"Id":"track-one","Type":"Audio","MediaSources":[{"Id":"ms",
                "MediaStreams":[{"Type":"Subtitle","Title":"English","Codec":"subrip"}]}]}]}"""
        }
        val document = EmbyLyricsLoader({ owner }, credentials, json,
            EmbySubtitleTransport { _, _ -> error("Unexpected request") }).load(song())
        assertTrue(document.lines.isEmpty())
    }

    @Test fun invalidOrStaleMetadataCannotBeCommittedAfterSourceChange() = runTest {
        val owner = owner()
        val wrongItem = EmbyJsonTransport { _, _ ->
            """{"Items":[{"Id":"other-song","Type":"Audio","MediaSources":[]}]}"""
        }
        assertTrue(EmbyLyricsLoader({ owner }, credentials, wrongItem).load(song()).lines.isEmpty())
        val stale = EmbyJsonTransport { _, _ ->
            owner.invalidateOperations()
            """{"Items":[{"Id":"track-one","Type":"Audio","MediaSources":[]}]}"""
        }
        try {
            EmbyLyricsLoader({ owner }, credentials, stale).load(song())
            error("Stale Emby lyrics operation accepted")
        } catch (ex: IOException) {
            assertTrue(ex.message.orEmpty().contains("changed"))
        }
    }
}
