package com.mica.music.data.scanner

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoCoverPosterPrefetcherTest {

    @Test
    fun prefetchSkipsCachedDedupesAndStoresExtracted() {
        val cached = setOf("content://cached")
        val extracted = mutableListOf<String>()
        val stored = mutableListOf<String>()
        val stats = VideoCoverPosterPrefetcher.prefetchVideoCoverPosters(
            uris = listOf(
                "content://cached",
                "content://fresh",
                "content://fresh",
                "",
                "content://fail",
            ),
            isCached = { it in cached },
            extract = { uri ->
                extracted += uri
                if (uri.endsWith("fail")) null
                else Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            },
            store = { uri, _ -> stored += uri },
        )
        assertEquals(listOf("content://fresh", "content://fail"), extracted)
        assertEquals(listOf("content://fresh"), stored)
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
    fun prefetchStopsWhenCancelled() {
        var calls = 0
        var go = true
        VideoCoverPosterPrefetcher.prefetchVideoCoverPosters(
            uris = listOf("content://a", "content://b", "content://c"),
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
        val stored = mutableListOf<String>()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        try {
            val stats = VideoCoverPosterPrefetcher.prefetchVideoCoverPosters(
                uris = listOf("content://cancel-during-extract"),
                isCached = { false },
                extract = {
                    go = false
                    bitmap
                },
                store = { uri, _ -> stored += uri },
                shouldContinue = { go },
            )

            assertEquals(0, stats.stored)
            assertEquals(emptyList<String>(), stored)
            assertTrue(bitmap.isRecycled)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test
    fun corruptedDiskPosterIsNotReportedAsCached() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = "content://video/corrupt-${System.nanoTime()}"
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(context.cacheDir, "video_cover_posters/$hex.jpg")
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf())

        try {
            assertTrue(file.isFile)
            assertTrue(!VideoCoverPosterStore.isCached(context, uri))
        } finally {
            file.delete()
        }
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
