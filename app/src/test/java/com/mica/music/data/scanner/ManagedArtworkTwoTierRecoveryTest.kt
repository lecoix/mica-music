package com.mica.music.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil.size.Dimension
import coil.size.Size
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.imaging.ManagedArtworkFetcher
import com.mica.music.media.SystemMediaArtworkResolver
import com.mica.music.testutil.SongFixtures
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManagedArtworkTwoTierRecoveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        ManagedArtworkRecovery.resetLoaderForTest()
    }

    @Test
    fun scanStoresCompactThumbnailWithoutDumpingFullOriginalToDisk() {
        val rawPng = createTestImageBytes(width = 800, height = 800, color = Color.rgb(180, 40, 60))
        val uri = AlbumArtCache.storeManagedArtworkForScan(context, "scan-thumb-song", rawPng)
        val originalFile = AlbumArtCache.fileForManagedArtwork(context, uri)!!
        val thumbFile = AlbumArtCache.thumbFileForManagedArtwork(context, uri)!!
        val huawei = SystemMediaArtworkResolver.DeviceProfile(
            manufacturer = "HUAWEI",
            model = "OXF-AN10",
            sdkInt = 31,
        )

        try {
            assertFalse("Scan must not dump full-res original into album_art/", originalFile.exists())
            assertTrue("Scan must create compact WebP thumbnail in album_art_thumb/", thumbFile.isFile && thumbFile.length() > 0L)
            assertTrue("Thumbnail should use .webp extension", thumbFile.name.endsWith(".webp"))
            assertEquals(
                Uri.fromFile(thumbFile),
                SystemMediaArtworkResolver.resolve(context, uri, huawei),
            )
        } finally {
            originalFile.delete()
            thumbFile.delete()
        }
    }

    @Test
    fun concurrentEnsureOriginalInterleavingNeverReturnsFalseWhenWinnerAlreadyRestored() = runBlocking {
        val rawBytes = createTestImageBytes(width = 640, height = 640, color = Color.rgb(30, 120, 200))
        val contentKey = AlbumArtCache.contentKeyFor(rawBytes)
        val uri = AlbumArtCache.buildManagedArtworkUri(context, "race-song", contentKey)
        val originalFile = AlbumArtCache.fileForManagedArtwork(context, uri)!!
        val thumbFile = AlbumArtCache.thumbFileForManagedArtwork(context, uri)!!
        originalFile.delete()
        thumbFile.delete()

        val loaderCalls = AtomicInteger(0)
        val enteredLoader = CompletableDeferred<Unit>()
        val releaseLoader = CompletableDeferred<Unit>()
        ManagedArtworkRecovery.embeddedArtworkLoader = { _, songId ->
            assertEquals("race-song", songId)
            loaderCalls.incrementAndGet()
            enteredLoader.complete(Unit)
            releaseLoader.await()
            rawBytes
        }

        try {
            val first = async { ManagedArtworkRecovery.ensureOriginal(context, uri) }
            enteredLoader.await()
            // Second caller joins while first is in-flight.
            val joined = async { ManagedArtworkRecovery.ensureOriginal(context, uri) }
            releaseLoader.complete(Unit)

            assertTrue(first.await())
            assertTrue(joined.await())
            // Third caller arrives strictly AFTER first finished and exited inFlight (the old race
            // window where verifyAndRepair returned false and AlbumArtContentProvider threw FNF).
            assertTrue(ManagedArtworkRecovery.ensureOriginal(context, uri))
            assertEquals(1, loaderCalls.get())
            assertArrayEquals(rawBytes, originalFile.readBytes())
        } finally {
            originalFile.delete()
            thumbFile.delete()
        }
    }

    @Test
    fun transientRestoreFailureDoesNotPermanentlyBlacklistContentKey() = runBlocking {
        val rawBytes = createTestImageBytes(width = 512, height = 512, color = Color.rgb(90, 150, 70))
        val contentKey = AlbumArtCache.contentKeyFor(rawBytes)
        val uri = AlbumArtCache.buildManagedArtworkUri(context, "transient-song", contentKey)
        val originalFile = AlbumArtCache.fileForManagedArtwork(context, uri)!!
        val thumbFile = AlbumArtCache.thumbFileForManagedArtwork(context, uri)!!
        originalFile.delete()
        thumbFile.delete()

        val attempt = AtomicInteger(0)
        ManagedArtworkRecovery.embeddedArtworkLoader = { _, _ ->
            if (attempt.incrementAndGet() == 1) null else rawBytes
        }

        try {
            assertFalse(ManagedArtworkRecovery.repairAfterLoadFailure(context, uri))
            assertTrue(
                "Transient failure must not permanently mark contentKey unreadable",
                AlbumArtCache.isCachedArtReadable(context, uri),
            )
            assertTrue(ManagedArtworkRecovery.ensureOriginal(context, uri))
            assertArrayEquals(rawBytes, originalFile.readBytes())
            assertTrue(thumbFile.isFile && thumbFile.length() > 0L)
        } finally {
            originalFile.delete()
            thumbFile.delete()
        }
    }

    @Test
    fun repairAfterLoadFailureRebuildsCorruptOriginalOnDisk() = runBlocking {
        val rawBytes = createTestImageBytes(width = 600, height = 600, color = Color.rgb(210, 160, 40))
        val uri = AlbumArtCache.storeManagedArtwork(context, "corrupt-song", rawBytes)
        val originalFile = AlbumArtCache.fileForManagedArtwork(context, uri)!!
        val thumbFile = AlbumArtCache.thumbFileForManagedArtwork(context, uri)!!

        ManagedArtworkRecovery.embeddedArtworkLoader = { _, _ -> rawBytes }

        try {
            originalFile.writeBytes(rawBytes.copyOf().also { it[0] = (it[0] + 1).toByte() })
            assertFalse(AlbumArtCache.managedArtworkFileIsValid(context, uri))

            assertTrue(ManagedArtworkRecovery.repairAfterLoadFailure(context, uri))
            assertTrue(AlbumArtCache.managedArtworkFileIsValid(context, uri))
            assertArrayEquals(rawBytes, originalFile.readBytes())
        } finally {
            originalFile.delete()
            thumbFile.delete()
        }
    }

    @Test
    fun pruneUnreferencedCleansBothOriginalAndThumbnailDirectories() {
        val keepBytes = createTestImageBytes(width = 480, height = 480, color = Color.RED)
        val orphanBytes = createTestImageBytes(width = 480, height = 480, color = Color.BLUE)
        val keepUri = AlbumArtCache.storeManagedArtwork(context, "keep-song", keepBytes)
        val orphanUri = AlbumArtCache.storeManagedArtwork(context, "orphan-song", orphanBytes)

        val keepOrig = AlbumArtCache.fileForManagedArtwork(context, keepUri)!!
        val keepThumb = AlbumArtCache.thumbFileForManagedArtwork(context, keepUri)!!
        val orphanOrig = AlbumArtCache.fileForManagedArtwork(context, orphanUri)!!
        val orphanThumb = AlbumArtCache.thumbFileForManagedArtwork(context, orphanUri)!!
        listOf(keepOrig, keepThumb, orphanOrig, orphanThumb).forEach { it.setLastModified(1L) }

        try {
            AlbumArtCache.pruneUnreferenced(
                context = context,
                songs = listOf(SongFixtures.song("keep-song").copy(albumArtUri = keepUri)),
                minimumAgeMs = 0L,
                nowMs = { System.currentTimeMillis() + 60_000L },
            )

            assertTrue(keepOrig.exists())
            assertTrue(keepThumb.exists())
            assertFalse(orphanOrig.exists())
            assertFalse(orphanThumb.exists())
        } finally {
            listOf(keepOrig, keepThumb, orphanOrig, orphanThumb).forEach(File::delete)
        }
    }

    @Test
    fun fetcherTierRoutingUsesThumbnailForCompactAndBackgroundAndOriginalForHero() {
        val compact = CoverDecodeTarget.forCompactCover()
        assertTrue(
            ManagedArtworkFetcher.shouldUseThumbnailTier(
                Size(Dimension(compact.widthPx), Dimension(compact.heightPx)),
            ),
        )
        assertTrue(
            ManagedArtworkFetcher.shouldUseThumbnailTier(
                Size(Dimension(384), Dimension(384)),
            ),
        )
        assertFalse(
            ManagedArtworkFetcher.shouldUseThumbnailTier(
                Size(Dimension(1080), Dimension(1080)),
            ),
        )
        assertFalse(ManagedArtworkFetcher.shouldUseThumbnailTier(Size.ORIGINAL))
    }

    @Test
    fun artCacheKeyPreventsMediaStoreSharedAlbumIdCrossArtistPollution() {
        val sharedAlbumId = 6539316500227728566L
        val jayInMusic = sampleDraft(
            id = 1L,
            artist = "周杰伦",
            album = "Music",
            albumId = sharedAlbumId,
            folderPath = "Music",
        )
        val richieInMusic = sampleDraft(
            id = 2L,
            artist = "任贤齐",
            album = "Music",
            albumId = sharedAlbumId,
            folderPath = "Music",
        )
        assertFalse(AudioMetadataProbe.isSafeForAlbumArtSharing(jayInMusic))
        assertFalse(AudioMetadataProbe.isSafeForAlbumArtSharing(richieInMusic))
        assertNotEquals(
            AudioMetadataProbe.artCacheKey(jayInMusic),
            AudioMetadataProbe.artCacheKey(richieInMusic),
        )

        val unknownAlbumA = sampleDraft(id = 3L, artist = "艺人A", album = "未知专辑", albumId = 99L)
        val unknownAlbumB = sampleDraft(id = 4L, artist = "艺人A", album = "未知专辑", albumId = 99L)
        assertFalse(AudioMetadataProbe.isSafeForAlbumArtSharing(unknownAlbumA))
        assertNotEquals(
            AudioMetadataProbe.artCacheKey(unknownAlbumA),
            AudioMetadataProbe.artCacheKey(unknownAlbumB),
        )

        val sameAlbumDiffArtistA = sampleDraft(id = 5L, artist = "艺人A", album = "精选集", albumId = 77L)
        val sameAlbumDiffArtistB = sampleDraft(id = 6L, artist = "艺人B", album = "精选集", albumId = 77L)
        assertNotEquals(
            AudioMetadataProbe.artCacheKey(sameAlbumDiffArtistA),
            AudioMetadataProbe.artCacheKey(sameAlbumDiffArtistB),
        )

        val sameAlbumSameArtistA = sampleDraft(id = 7L, artist = "周杰伦", album = "范特西", albumId = 88L)
        val sameAlbumSameArtistB = sampleDraft(id = 8L, artist = "周杰伦", album = "范特西", albumId = 88L)
        assertTrue(AudioMetadataProbe.isSafeForAlbumArtSharing(sameAlbumSameArtistA))
        assertEquals(
            AudioMetadataProbe.artCacheKey(sameAlbumSameArtistA),
            AudioMetadataProbe.artCacheKey(sameAlbumSameArtistB),
        )
    }

    private fun createTestImageBytes(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        val bytes = out.toByteArray()
        assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        return bytes
    }

    private fun sampleDraft(
        id: Long,
        artist: String,
        album: String,
        albumId: Long,
        folderPath: String = "Music/Albums",
    ) = TrackDraft(
        mediaStoreId = id,
        title = "Song $id",
        artist = artist,
        album = album,
        albumId = albumId,
        durationSec = 200,
        mimeType = "audio/mpeg",
        displayName = "song_$id.mp3",
        sizeBytes = 5_000_000L,
        bitrateBpsFromStore = 320_000,
        mediaUri = "content://media/external/audio/media/$id",
        coverColorArgb = CoverColorExtractor.FALLBACK_ARGB,
        folderPath = folderPath,
    )
}
