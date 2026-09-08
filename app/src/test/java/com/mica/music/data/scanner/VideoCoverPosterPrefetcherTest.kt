package com.mica.music.data.scanner

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoCoverPosterPrefetcherTest {

    @Test
    fun prefetchSkipsCachedDedupesAndStoresExtracted() {
        val cached = setOf(VideoCoverPosterRef("content://cached", "r1"))
        val extracted = mutableListOf<VideoCoverPosterRef>()
        val stored = mutableListOf<VideoCoverPosterRef>()
        val fresh = VideoCoverPosterRef("content://fresh", "r2")
        val stats = VideoCoverPosterPrefetcher.prefetchVideoCoverPosters(
            refs = listOf(
                VideoCoverPosterRef("content://cached", "r1"),
                fresh,
                fresh,
                VideoCoverPosterRef("", ""),
                VideoCoverPosterRef("content://fail", "r3"),
            ),
            isCached = { it in cached },
            extract = { ref ->
                extracted += ref
                if (ref.uri.endsWith("fail")) null
                else Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            },
            store = { ref, _ -> stored += ref },
        )
        assertEquals(
            listOf(fresh, VideoCoverPosterRef("content://fail", "r3")),
            extracted,
        )
        assertEquals(listOf(fresh), stored)
        assertEquals(
            VideoCoverPosterPrefetcher.PrefetchStats(
                total = 3,
                skipped = 1,
                stored = 1,
                failed = 1,
            ),
            stats,
        )
    }

    @Test
    fun sameUriDifferentRevisionIsNotDeduped() {
        val uri = "content://same"
        val extracted = mutableListOf<VideoCoverPosterRef>()
        val stats = VideoCoverPosterPrefetcher.prefetchVideoCoverPosters(
            refs = listOf(
                VideoCoverPosterRef(uri, "old"),
                VideoCoverPosterRef(uri, "new"),
            ),
            isCached = { false },
            extract = { ref ->
                extracted += ref
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            },
            store = { _, bitmap -> if (!bitmap.isRecycled) bitmap.recycle() },
        )

        assertEquals(2, stats.total)
        assertEquals(listOf("old", "new"), extracted.map { it.revision })
    }

    @Test
    fun prefetchStopsWhenCancelled() {
        var calls = 0
        var go = true
        VideoCoverPosterPrefetcher.prefetchVideoCoverPosters(
            refs = listOf(
                VideoCoverPosterRef("content://a", "a"),
                VideoCoverPosterRef("content://b", "b"),
                VideoCoverPosterRef("content://c", "c"),
            ),
            isCached = { false },
            extract = {
                calls++
                go = false
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            },
            store = { _, _ -> },
            shouldContinue = { go },
        )
        assertEquals(1, calls)
    }

    @Test
    fun prefetchDoesNotStoreWhenCancelledDuringExtraction() {
        var go = true
        val stored = mutableListOf<VideoCoverPosterRef>()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        try {
            val stats = VideoCoverPosterPrefetcher.prefetchVideoCoverPosters(
                refs = listOf(VideoCoverPosterRef("content://cancel-during-extract", "r1")),
                isCached = { false },
                extract = {
                    go = false
                    bitmap
                },
                store = { ref, _ -> stored += ref },
                shouldContinue = { go },
            )

            assertEquals(0, stats.stored)
            assertEquals(emptyList<VideoCoverPosterRef>(), stored)
            assertTrue(bitmap.isRecycled)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test
    fun corruptedDiskPosterIsNotReportedAsCached() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = "content://video/corrupt-${System.nanoTime()}"
        val revision = "same-uri-revision"
        val file = VideoCoverPosterStore.fileForTest(context, uri, revision)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf())

        try {
            assertTrue(file.isFile)
            assertTrue(!VideoCoverPosterStore.isCached(context, uri, revision))
        } finally {
            file.delete()
        }
    }

    @Test
    fun sameUriDifferentRevisionUsesDifferentDiskCacheFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = "content://video/same"

        val oldFile = VideoCoverPosterStore.fileForTest(context, uri, "old|1|2")
        val newFile = VideoCoverPosterStore.fileForTest(context, uri, "new|3|4")

        assertNotEquals(oldFile.absolutePath, newFile.absolutePath)
    }

    @Test
    fun posterDiskBudgetEvictsOldestFilesToTrimTargetAndProtectsCurrentWrite() {
        val directory = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "video_cover_posters/budget-${System.nanoTime()}",
        ).also { it.mkdirs() }
        val oldest = File(directory, "oldest.jpg").also {
            it.writeBytes(ByteArray(60))
            it.setLastModified(1L)
        }
        val middle = File(directory, "middle.jpg").also {
            it.writeBytes(ByteArray(60))
            it.setLastModified(2L)
        }
        val current = File(directory, "current.jpg").also {
            it.writeBytes(ByteArray(60))
            it.setLastModified(3L)
        }

        try {
            VideoCoverPosterStore.trimToBudgetForTest(
                directory = directory,
                protectedFile = current,
                maxBytes = 120L,
            )

            assertFalse(oldest.exists())
            assertFalse(middle.exists())
            assertTrue(current.exists())
            assertTrue(directory.listFiles().orEmpty().sumOf(File::length) <= 90L)
        } finally {
            directory.deleteRecursively()
        }
    }
}
