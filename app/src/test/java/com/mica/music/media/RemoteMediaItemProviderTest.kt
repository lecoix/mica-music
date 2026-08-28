package com.mica.music.media

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.SongSource
import com.mica.music.data.remote.RemoteArtworkRef
import com.mica.music.data.remote.RemoteArtworkUriCodec
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteMediaMetadataExtras
import com.mica.music.data.remote.RemotePlaybackUriCodec
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.RemoteTrackSummaryLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            fileName = "track-9.flac",
            suffix = "flac",
            sizeBytes = 987654L,
            year = 2026,
            trackNumber = 9,
            discNumber = 2,
            artworkOpaqueId = "cover-9",
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
        assertEquals(
            RemoteArtworkUriCodec.encode(RemoteArtworkRef("nav-1", "cover-9")),
            item.mediaMetadata.artworkUri?.toString(),
        )
        assertTrue(item.mediaMetadata.artworkUri?.toString()?.contains("credential") == false)
        val extras = requireNotNull(item.mediaMetadata.extras)
        assertTrue(RemoteMediaMetadataExtras.isTrustedProjection(extras))
        assertEquals("audio/flac", RemoteMediaMetadataExtras.mimeType(extras))
        assertEquals("track-9.flac", RemoteMediaMetadataExtras.fileName(extras))
        assertEquals("flac", RemoteMediaMetadataExtras.suffix(extras))
        assertEquals(987654L, RemoteMediaMetadataExtras.sizeBytes(extras))
        assertEquals(2026, RemoteMediaMetadataExtras.year(extras))
        assertEquals(9, RemoteMediaMetadataExtras.trackNumber(extras))
        assertEquals(2, RemoteMediaMetadataExtras.discNumber(extras))
        assertEquals(
            setOf(
                "mica.remote.metadata.version",
                "mica.remote.metadata.mime",
                "mica.remote.metadata.fileName",
                "mica.remote.metadata.suffix",
                "mica.remote.metadata.sizeBytes",
                "mica.remote.metadata.year",
                "mica.remote.metadata.trackNumber",
                "mica.remote.metadata.discNumber",
            ),
            extras.keySet(),
        )
    }

    @Test
    fun `trusted remote media item decodes to safe remote song projection`() {
        val summary = RemoteTrackSummary(
            ref = RemoteTrackRef("nav-1", "track-9"),
            title = "Remote Song",
            artist = "Artist",
            album = "Album",
            albumArtist = "Album Artist",
            durationSec = 123,
            mimeTypeHint = "audio/flac",
            fileName = "track-9.flac",
            suffix = "flac",
            sizeBytes = 987654L,
            year = 2026,
            trackNumber = 9,
            discNumber = 2,
            artworkOpaqueId = "cover-9",
        )
        val item = RemoteMediaItemCodec.encode(summary)

        val decoded = requireNotNull(RemoteMediaItemCodec.decode(item))

        assertEquals(SongSource.REMOTE, decoded.source)
        assertEquals(summary.mediaId, decoded.id)
        assertEquals(RemotePlaybackUriCodec.encode(summary.mediaId), decoded.mediaUri)
        assertNull(decoded.playbackUri)
        assertEquals("audio/flac", decoded.metadata.playbackMimeType)
        assertEquals("FLAC", decoded.metadata.containerName)
        assertEquals("track-9.flac", decoded.fileName)
        assertEquals(987654L, decoded.sizeBytes)
        assertEquals(2026, decoded.year)
        assertEquals(9, decoded.trackNumber)
        assertEquals(2, decoded.discNumber)
        assertEquals(
            RemoteArtworkUriCodec.encode(RemoteArtworkRef("nav-1", "cover-9")),
            decoded.albumArtUri,
        )
        assertTrue(!decoded.lyricsLoaded)
    }

    @Test
    fun `remote decoder rejects authenticated transport uri and cross-source artwork`() {
        val summary = RemoteTrackSummary(
            ref = RemoteTrackRef("nav-1", "track-9"),
            title = "Remote Song",
            artworkOpaqueId = "cover-9",
        )
        val trusted = RemoteMediaItemCodec.encode(summary)
        val authenticatedTransport = trusted.buildUpon()
            .setUri("https://music.example/rest/stream?id=track-9&t=secret&s=salt")
            .build()
        val wrongArtwork = trusted.buildUpon()
            .setMediaMetadata(
                trusted.mediaMetadata.buildUpon()
                    .setArtworkUri(
                        Uri.parse(
                            RemoteArtworkUriCodec.encode(RemoteArtworkRef("nav-2", "cover-9")),
                        ),
                    )
                    .build(),
            )
            .build()

        assertNull(RemoteMediaItemCodec.decode(authenticatedTransport))
        assertNull(RemoteMediaItemCodec.decode(wrongArtwork))
    }

    @Test
    fun `unknown summary is dropped`() = runTest {
        val ref = RemoteTrackRef("nav-1", "missing")
        val mediaId = RemoteMediaIdCodec.encode(ref)
        val provider = TrustedRemoteMediaItemProvider(RemoteTrackSummaryLookup { emptyMap() })

        assertEquals(emptyMap<String, androidx.media3.common.MediaItem>(), provider.resolve(listOf(mediaId)))
    }
}
