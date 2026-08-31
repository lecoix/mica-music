package com.mica.music.media

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.TransientPlaybackCatalog
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrustedMediaItemResolverTest {
    @Test
    fun externalMetadataAndUriAreRebuiltFromLibraryId() = runTest {
        val song = SongFixtures.song("library-song", mime = "audio/flac")
        val requested = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(Uri.parse("content://attacker/not-library-song"))
            .build()
        val resolver = resolver(song)

        val resolved = resolver.resolve(mutableListOf(requested)).mediaItems.single()

        assertEquals(song.mediaUri, resolved.localConfiguration?.uri?.toString())
        assertEquals(song.id, resolved.mediaId)
    }

    @Test
    fun unknownIdsAreDroppedInsteadOfFallingBackToCallerItem() = runTest {
        val requested = MediaItem.Builder()
            .setMediaId("not-in-library")
            .setUri(Uri.parse("content://attacker/audio"))
            .build()

        val resolved = resolver(null).resolve(mutableListOf(requested))

        assertTrueEmpty(resolved.mediaItems)
        assertNull(resolved.resolvedStartIndex)
    }

    @Test
    fun resolvedExternalItemUsesAllowlistedMetadataInsteadOfCallerMetadata() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val song = SongFixtures.song("library-song").copy(
            albumArtUri = "content://attacker/private-cover",
        )
        val requested = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(Uri.parse("content://attacker/not-library-song"))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setArtworkUri(Uri.parse("content://attacker/private-cover"))
                    .build(),
            )
            .build()
        val resolver = TrustedMediaItemResolver(
            transientSongById = { null },
            librarySongsById = { mapOf(song.id to song) },
            mediaItemFactory = { ExternalMediaItemCodec.encode(context, it) },
        )

        val resolved = resolver.resolve(mutableListOf(requested)).mediaItems.single()

        assertEquals(song.mediaUri, resolved.localConfiguration?.uri?.toString())
        assertNull(resolved.mediaMetadata.artworkUri)
        assertNull(resolved.mediaMetadata.extras?.getString("mica.song.mediaUri"))
        assertNull(resolved.mediaMetadata.extras?.getString("mica.song.filePath"))
    }

    @Test
    fun transientIdNeverFallsBackToPersistentLibrary() = runTest {
        val id = "${TransientPlaybackCatalog.TRANSIENT_ID_PREFIX}song"
        val persistentSong = SongFixtures.song(id).copy(source = SongSource.LIBRARY)
        val transientSong = SongFixtures.song(id, title = "Session only").copy(
            source = SongSource.TRANSIENT_EXTERNAL,
        )
        val resolver = TrustedMediaItemResolver(
            transientSongById = { transientSong },
            librarySongsById = { mapOf(id to persistentSong) },
        )

        val resolved = resolver.resolve(
            mutableListOf(MediaItem.Builder().setMediaId(id).build()),
        ).mediaItems.single()

        assertEquals("Session only", resolved.mediaMetadata.title?.toString())
    }

    @Test
    fun requestedStartIndexFollowsDroppedItems() = runTest {
        val first = SongFixtures.song("first")
        val third = SongFixtures.song("third")
        val resolver = TrustedMediaItemResolver(
            transientSongById = { null },
            librarySongsById = { ids ->
                listOf(first, third).filter { it.id in ids }.associateBy(Song::id)
            },
        )
        val resolution = resolver.resolve(
            requestedItems = mutableListOf(
                MediaItem.Builder().setMediaId("missing").build(),
                MediaItem.Builder().setMediaId(first.id).build(),
                MediaItem.Builder().setMediaId(third.id).build(),
            ),
            requestedStartIndex = 2,
        )

        assertEquals(listOf(first.id, third.id), resolution.mediaItems.map(MediaItem::mediaId))
        assertEquals(1, resolution.resolvedStartIndex)
    }

    @Test
    fun remoteIdUsesTrustedRemoteProviderAndIgnoresCallerUri() = runTest {
        val remoteId = RemoteMediaIdCodec.encode(RemoteTrackRef("nav-1", "track-9"))
        val requested = MediaItem.Builder()
            .setMediaId(remoteId)
            .setUri(Uri.parse("content://attacker/not-remote-track"))
            .build()
        val trusted = MediaItem.Builder()
            .setMediaId(remoteId)
            .setUri(Uri.parse("https://trusted.example/rest/stream?id=track-9"))
            .build()
        var libraryLookupIds: List<String>? = null
        val resolver = TrustedMediaItemResolver(
            transientSongById = { null },
            librarySongsById = { ids ->
                libraryLookupIds = ids
                emptyMap()
            },
            remoteMediaItemsById = { ids ->
                assertEquals(listOf(remoteId), ids)
                mapOf(remoteId to trusted)
            },
        )

        val resolved = resolver.resolve(listOf(requested)).mediaItems.single()

        assertEquals(emptyList<String>(), libraryLookupIds)
        assertEquals("https://trusted.example/rest/stream?id=track-9", resolved.localConfiguration?.uri?.toString())
    }

    @Test
    fun unknownRemoteIdIsDroppedWithoutLibraryFallback() = runTest {
        val remoteId = RemoteMediaIdCodec.encode(RemoteTrackRef("nav-1", "missing"))
        var libraryLookupIds: List<String>? = null
        val resolver = TrustedMediaItemResolver(
            transientSongById = { null },
            librarySongsById = { ids ->
                libraryLookupIds = ids
                emptyMap()
            },
            remoteMediaItemsById = { emptyMap() },
        )

        val resolution = resolver.resolve(listOf(MediaItem.Builder().setMediaId(remoteId).build()))

        assertEquals(emptyList<String>(), libraryLookupIds)
        assertTrueEmpty(resolution.mediaItems)
        assertNull(resolution.resolvedStartIndex)
    }
    private fun resolver(song: Song?): TrustedMediaItemResolver = TrustedMediaItemResolver(
        transientSongById = { null },
        librarySongsById = { ids ->
            song?.takeIf { it.id in ids }?.let { mapOf(it.id to it) }.orEmpty()
        },
    )

    private fun assertTrueEmpty(items: List<MediaItem>) = assertEquals(emptyList<MediaItem>(), items)
}
