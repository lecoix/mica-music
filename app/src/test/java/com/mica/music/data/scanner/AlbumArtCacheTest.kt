package com.mica.music.data.scanner

import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.testutil.SongFixtures
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumArtCacheTest {

    @Test
    fun identicalEmbeddedArtworkUsesOneContentAddressedFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = "same-cover-${System.nanoTime()}".toByteArray()

        val first = AlbumArtCache.storeEmbeddedPicture(context, bytes)
        val second = AlbumArtCache.storeEmbeddedPicture(context, bytes.copyOf())

        try {
            assertEquals(first.absolutePath, second.absolutePath)
            assertArrayEquals(bytes, first.readBytes())
        } finally {
            first.delete()
        }
    }

    @Test
    fun managedArtworkUriKeepsSourceIdentityAndResolvesSharedContentFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = "managed-cover-${System.nanoTime()}".toByteArray()

        val firstUri = AlbumArtCache.storeManagedArtwork(context, "song/one", bytes)
        val secondUri = AlbumArtCache.storeManagedArtwork(context, "song/two", bytes.copyOf())
        val first = AlbumArtCache.parseManagedArtworkUri(context, firstUri)
        val second = AlbumArtCache.parseManagedArtworkUri(context, secondUri)

        try {
            assertEquals("song/one", first?.songId)
            assertEquals("song/two", second?.songId)
            assertEquals(first?.contentKey, second?.contentKey)
            assertEquals(
                AlbumArtCache.fileForManagedArtwork(context, firstUri)?.absolutePath,
                AlbumArtCache.fileForManagedArtwork(context, secondUri)?.absolutePath,
            )
        } finally {
            AlbumArtCache.fileForManagedArtwork(context, firstUri)?.delete()
        }
    }

    @Test
    fun managedArtworkProviderReadsResidentContent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = ByteArray(4096) { index -> (index * 13).toByte() }
        val uri = AlbumArtCache.storeManagedArtwork(context, "provider-song", bytes)

        try {
            val restored = context.contentResolver.openInputStream(android.net.Uri.parse(uri))
                ?.use { it.readBytes() }
            assertArrayEquals(bytes, restored)
        } finally {
            AlbumArtCache.fileForManagedArtwork(context, uri)?.delete()
        }
    }

    @Test
    fun managedArtworkRejectsSameLengthCorruptionAndCanBeRewritten() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = ByteArray(4096) { index -> (index * 7).toByte() }
        val uri = AlbumArtCache.storeManagedArtwork(context, "corruptible", bytes)
        val file = AlbumArtCache.fileForManagedArtwork(context, uri)!!

        try {
            file.writeBytes(bytes.copyOf().also { it[0] = (it[0] + 1).toByte() })
            assertFalse(AlbumArtCache.managedArtworkFileIsValid(context, uri))
            assertFalse(AlbumArtCache.isCachedArtReadable(context, uri))

            AlbumArtCache.storeManagedArtwork(context, "corruptible", bytes)
            assertTrue(AlbumArtCache.managedArtworkFileIsValid(context, uri))
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            file.delete()
        }
    }

    @Test
    fun intentionallyEvictedManagedArtworkDoesNotForceWholeLibraryRepair() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val contentKey = "content_v1_${"0".repeat(64)}"
        val uri = AlbumArtCache.buildManagedArtworkUri(context, "ms_42", contentKey)
        AlbumArtCache.fileForManagedArtwork(context, uri)?.delete()
        val cached = matchingCachedSong(uri)

        assertSame(cached, matchingDraft().reusableCachedSong(context, mapOf(cached.id to cached)))
        assertEquals(0, AlbumArtCache.health(context, listOf(cached)).missingCachedArtUris)
    }

    @Test
    fun trimToBudgetEvictsOldestContentButProtectsCurrentWrite() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val files = (1..3).map { index ->
            AlbumArtCache.storeEmbeddedPicture(
                context,
                ByteArray(1024) { offset -> (index * 17 + offset).toByte() },
            ).also { it.setLastModified(index.toLong()) }
        }

        try {
            AlbumArtCache.trimToBudget(
                context = context,
                maxBytes = 2L * 1024L,
                protectedFile = files.last(),
            )

            assertEquals(false, files.first().exists())
            assertEquals(false, files[1].exists())
            assertEquals(true, files.last().exists())
        } finally {
            files.forEach(File::delete)
        }
    }

    @Test
    fun pruneDeletesOldUnreferencedArtworkButKeepsReferencedArtwork() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val contentKey = "content_v1_${"0".repeat(64)}"
        val keepUri = AlbumArtCache.buildManagedArtworkUri(context, "prune-keep", contentKey)
        val keep = AlbumArtCache.fileForManagedArtwork(context, keepUri)!!
        val orphan = AlbumArtCache.fileForKey(context, "prune-orphan-${System.nanoTime()}")
        keep.parentFile?.mkdirs()
        keep.writeBytes(byteArrayOf(1))
        orphan.writeBytes(byteArrayOf(2))
        val song = SongFixtures.song("prune-keep").copy(albumArtUri = keepUri)
        keep.setLastModified(1L)
        orphan.setLastModified(1L)

        try {
            AlbumArtCache.pruneUnreferenced(
                context = context,
                songs = listOf(song),
                minimumAgeMs = 0L,
                nowMs = { System.currentTimeMillis() + 60_000L },
            )

            assertTrue(keep.exists())
            assertFalse(orphan.exists())
        } finally {
            keep.delete()
            orphan.delete()
        }
    }

    @Test
    fun pruneGracePeriodKeepsFreshArtwork() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fresh = AlbumArtCache.storeEmbeddedPicture(
            context,
            "fresh-prune-art-${System.nanoTime()}".toByteArray(),
        )

        try {
            AlbumArtCache.pruneUnreferenced(context, emptyList())

            assertTrue(fresh.exists())
        } finally {
            fresh.delete()
        }
    }

    @Test
    fun artworkStoreWaitsForPruneDeleteAndSurvivesAfterMaintenance() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = "prune-write-race-${System.nanoTime()}".toByteArray()
        val existing = AlbumArtCache.storeEmbeddedPicture(context, bytes)
        existing.setLastModified(1L)
        val pruneEntered = CountDownLatch(1)
        val releasePrune = CountDownLatch(1)
        val storeStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val prune = executor.submit(Callable {
                AlbumArtCache.pruneUnreferenced(
                    context = context,
                    songs = emptyList(),
                    minimumAgeMs = 0L,
                    nowMs = { System.currentTimeMillis() + 60_000L },
                    beforeDelete = { file ->
                        if (file.absolutePath == existing.absolutePath) {
                            pruneEntered.countDown()
                            check(releasePrune.await(5, TimeUnit.SECONDS))
                        }
                    },
                )
            })
            assertTrue(pruneEntered.await(5, TimeUnit.SECONDS))

            val store = executor.submit(Callable {
                storeStarted.countDown()
                AlbumArtCache.storeEmbeddedPicture(context, bytes.copyOf())
            })
            assertTrue(storeStarted.await(5, TimeUnit.SECONDS))
            assertFalse(store.isDone)

            releasePrune.countDown()
            prune.get(5, TimeUnit.SECONDS)
            val stored = store.get(5, TimeUnit.SECONDS)
            assertTrue(stored.exists())
            assertArrayEquals(bytes, stored.readBytes())
        } finally {
            releasePrune.countDown()
            executor.shutdownNow()
            existing.delete()
        }
    }

    @Test
    fun concurrentIdenticalArtworkWritesPublishOneCompleteFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = ByteArray(256 * 1024) { index -> (index * 31).toByte() }
        val executor = Executors.newFixedThreadPool(8)

        val files = try {
            executor.invokeAll(
                List(24) {
                    Callable { AlbumArtCache.storeEmbeddedPicture(context, bytes.copyOf()) }
                },
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        try {
            assertEquals(1, files.map(File::getAbsolutePath).distinct().size)
            assertArrayEquals(bytes, files.first().readBytes())
        } finally {
            files.firstOrNull()?.delete()
        }
    }

    @Test
    fun fileForKeyUsesEvictableCacheDirectoryForNewArtwork() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = AlbumArtCache.fileForKey(context, "new-art")

        assertEquals(
            File(context.cacheDir, ScanCacheManager.DIR_ALBUM_ART).absolutePath,
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
