package com.mica.music.data.scanner

import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.testutil.SongFixtures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumArtCacheTest {

    @Test
    fun fileForKeyUsesNoBackupDirectoryForNewArtwork() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = AlbumArtCache.fileForKey(context, "new-art")

        assertEquals(
            File(context.noBackupFilesDir, ScanCacheManager.DIR_ALBUM_ART).absolutePath,
            file.parentFile?.absolutePath,
        )
    }

    @Test
    fun reusableCachedSongRejectsMissingCachedAlbumArtFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val missing = File(context.cacheDir, "${ScanCacheManager.DIR_ALBUM_ART}/missing.jpg")
        val cached = matchingCachedSong(missing.toUri().toString())
        val draft = matchingDraft()

        assertNull(draft.reusableCachedSong(context, mapOf(cached.id to cached)))
    }

    @Test
    fun reusableCachedSongAcceptsExistingCachedAlbumArtFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val existing = File(context.cacheDir, "${ScanCacheManager.DIR_ALBUM_ART}/existing.jpg")
        existing.parentFile?.mkdirs()
        existing.writeBytes(byteArrayOf(1, 2, 3))
        val cached = matchingCachedSong(existing.toUri().toString())
        val draft = matchingDraft()

        assertSame(cached, draft.reusableCachedSong(context, mapOf(cached.id to cached)))
    }

    @Test
    fun forcedArtworkRefreshRejectsSongsWithoutArtworkOrWithCachedEmbeddedArtwork() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val embedded = File(context.cacheDir, "${ScanCacheManager.DIR_ALBUM_ART}/embedded.jpg")
        embedded.parentFile?.mkdirs()
        embedded.writeBytes(byteArrayOf(1, 2, 3))
        val draft = matchingDraft()

        assertNull(
            draft.reusableCachedSong(
                context = context,
                cachedById = mapOf(draft.scanSongId() to matchingCachedSong(null)),
                forceRefreshArtwork = true,
            ),
        )
        assertNull(
            draft.reusableCachedSong(
                context = context,
                cachedById = mapOf(draft.scanSongId() to matchingCachedSong(embedded.toUri().toString())),
                forceRefreshArtwork = true,
            ),
        )
        val storeArt = matchingCachedSong("content://media/external/audio/albums/1")
        assertSame(
            storeArt,
            draft.reusableCachedSong(
                context = context,
                cachedById = mapOf(draft.scanSongId() to storeArt),
                forceRefreshArtwork = true,
            ),
        )
    }

    @Test
    fun healthCountsMissingCachedAlbumArtFiles() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val existing = File(context.cacheDir, "${ScanCacheManager.DIR_ALBUM_ART}/health-existing.jpg")
        val missing = File(context.cacheDir, "${ScanCacheManager.DIR_ALBUM_ART}/health-missing.jpg")
        existing.parentFile?.mkdirs()
        existing.writeBytes(byteArrayOf(1))
        val songs = listOf(
            SongFixtures.song("existing").copy(albumArtUri = existing.toUri().toString()),
            SongFixtures.song("missing").copy(albumArtUri = missing.toUri().toString()),
            SongFixtures.song("store").copy(albumArtUri = "content://media/external/audio/albums/1"),
        )

        val health = AlbumArtCache.health(context, songs)

        assertEquals(3, health.songs)
        assertEquals(3, health.albumArtUris)
        assertEquals(2, health.cachedArtUris)
        assertEquals(0, health.currentCachedArtUris)
        assertEquals(2, health.legacyCachedArtUris)
        assertEquals(1, health.missingCachedArtUris)
        assertEquals(true, health.needsRepair)
        assertEquals(listOf("missing:health-missing.jpg"), health.missingSamples)
    }

    private fun matchingCachedSong(albumArtUri: String?) =
        SongFixtures.song(id = "ms_42").copy(
            mediaUri = "content://media/external/audio/media/42",
            sizeBytes = 1234L,
            dateModifiedMs = 5678L,
            albumArtUri = albumArtUri,
        )

    private fun matchingDraft() = TrackDraft(
        mediaStoreId = 42L,
        title = "Track",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationSec = 100,
        mimeType = "audio/flac",
        displayName = "track.flac",
        sizeBytes = 1234L,
        bitrateBpsFromStore = 0,
        mediaUri = "content://media/external/audio/media/42",
        coverColorArgb = 0xFF334455.toInt(),
        dateModifiedMs = 5678L,
    )
}
